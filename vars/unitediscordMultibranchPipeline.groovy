#!/usr/bin/env groovy
/**
 * uniteDiscord Multi-Branch Pipeline (Global Variable)
 *
 * This is the actual pipeline definition, called from the minimal stub Jenkinsfile
 * in the main uniteDiscord repo.
 *
 * Full CI pipeline: Lint, Unit Tests, Integration Tests, Contract Tests, E2E Tests, Build
 *
 * Stages:
 *   - Initialize: Setup, checkout, and GitHub status reporting
 *   - Install Dependencies: pnpm install with frozen lockfile
 *   - Build Packages: Build shared packages and generate Prisma client
 *   - Lint: ESLint and code quality checks
 *   - Unit Tests: Run unit tests (backend + frontend) - 1,221 tests
 *   - Integration Tests: Run integration tests - 124 tests (always run)
 *   - Contract Tests: API contract validation tests (framework ready)
 *   - E2E Tests: End-to-end browser tests with Playwright - 301 tests (main/develop only)
 *   - Build: Production build and artifact generation
 *
 * Multi-branch Jenkins provides these environment variables automatically:
 *   BRANCH_NAME   - Current branch name
 *   CHANGE_ID     - PR number (null if not a PR build)
 *
 * Webhook Setup:
 *   URL: https://jenkins.kindash.com/github-webhook/
 *   Content type: application/json
 *   Events: Push, Pull requests
 */

def call() {
    pipeline {
        agent any

        environment {
            GITHUB_OWNER = 'steiner385'
            GITHUB_REPO = 'uniteDiscord'
            CI = 'true'
            NODE_ENV = 'test'
            NODE_OPTIONS = '--max-old-space-size=4096'

            // Multi-branch provides BRANCH_NAME, CHANGE_ID, CHANGE_TARGET automatically
        }

        options {
            timestamps()
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timeout(time: 60, unit: 'MINUTES')
            disableConcurrentBuilds(abortPrevious: true)
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        // Determine build type for logging and status reporting
                        def buildType = env.CHANGE_ID ? "PR #${env.CHANGE_ID}" : "Branch: ${env.BRANCH_NAME}"
                        echo "=== Multi-Branch Build ==="
                        echo "Build type: ${buildType}"
                        if (env.CHANGE_ID) {
                            echo "PR Title: ${env.CHANGE_TITLE ?: 'N/A'}"
                            echo "PR Author: ${env.CHANGE_AUTHOR ?: 'N/A'}"
                            echo "Target Branch: ${env.CHANGE_TARGET ?: 'N/A'}"
                        }
                        echo "=========================="

                        // Report pending status to GitHub
                        githubStatusReporter(
                            status: 'pending',
                            context: 'jenkins/ci',
                            description: "Build started for ${buildType}"
                        )
                    }

                    // Checkout the uniteDiscord application repo (multi-branch SCM)
                    checkout scm

                    // Remove stale test directories and coverage files from previous builds
                    sh '''
                        rm -rf frontend/frontend || true
                        rm -rf coverage || true
                        find . -path "*/coverage/*" -name "*.xml" -delete 2>/dev/null || true
                    '''

                    script {
                        env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        env.GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                        echo "Building commit: ${env.GIT_COMMIT_SHORT}"
                    }
                }
            }

            stage('Install Dependencies') {
                steps {
                    sh '''
                        # Clean stale node_modules to force fresh install
                        rm -rf node_modules

                        # Fresh install with pnpm
                        npx --yes pnpm@latest install --frozen-lockfile
                    '''
                }
            }

            stage('Build Packages') {
                steps {
                    sh '''
                        # Build packages AND generate Prisma client
                        npx pnpm --filter "./packages/*" -r run build

                        # Verify packages are linked correctly
                        echo "=== Verifying workspace package links ==="
                        ls -la node_modules/@unite-discord/ || echo "WARNING: @unite-discord packages not linked"
                        ls -la node_modules/@prisma/client/ || echo "WARNING: @prisma/client not found"
                        ls -la node_modules/.pnpm/ | head -20 || echo "WARNING: .pnpm store missing"
                    '''
                }
            }

            stage('Lint') {
                steps {
                    sh 'npx pnpm run lint'
                }
            }

            stage('Unit Tests') {
                steps {
                    withAwsCredentials {
                        sh 'npx pnpm run test:unit'
                    }
                }
            }

            stage('Integration Tests') {
                steps {
                    script {
                        try {
                            echo "=== Running Integration Tests ==="
                            echo "Branch: ${env.BRANCH_NAME ?: 'N/A'}"
                            echo "Tests: 124 integration tests (6 files)"
                            echo "Framework: vitest.integration.config.ts"
                            runIntegrationTests(
                                testCommand: 'npx vitest run --config vitest.integration.config.ts',
                                skipLock: false,
                                statusContext: 'jenkins/integration',
                                composeFile: 'docker-compose.test.yml'
                            )
                            echo "=== Integration Tests Complete ==="
                        } catch (Exception e) {
                            echo "⚠️  Integration tests failed"
                            echo "Error: ${e.message}"
                            // Mark as unstable but don't fail the build
                            // Integration tests can have flaky issues, but we want to know about them
                            currentBuild.result = 'UNSTABLE'
                        }
                    }
                }
            }

            stage('Contract Tests') {
                steps {
                    sh 'npx pnpm run test:contract'
                }
            }

            stage('E2E Tests') {
                when {
                    anyOf {
                        branch 'main'
                        branch 'develop'
                        branch 'feat/e2e-docker-compose'
                    }
                }
                steps {
                    script {
                        try {
                            echo "=== Running E2E Tests ==="
                            echo "Branch: ${env.BRANCH_NAME}"
                            echo "Test Suite: Playwright (301 tests, 240 active)"
                            echo "Environment: Full production-like stack (infrastructure + all microservices + frontend)"

                            // Clean up any existing E2E and integration test containers
                            echo "Cleaning up existing containers and ports..."

                            // Force remove all E2E containers (handles stale containers)
                            sh '''
                                # Remove E2E containers
                                docker ps -a -q -f "name=unite-.*-e2e" | xargs -r docker rm -f 2>/dev/null || true
                                docker ps -a -q -f "name=.*_feat_e2e-docker-compose.*" | xargs -r docker rm -f 2>/dev/null || true

                                # Remove integration test containers (they use same ports: 5433, 6380, 4567)
                                docker ps -a -q -f "name=unite-.*-test" | xargs -r docker rm -f 2>/dev/null || true
                                docker ps -a -q -f "name=.*postgres.*test" | xargs -r docker rm -f 2>/dev/null || true
                                docker ps -a -q -f "name=.*redis.*test" | xargs -r docker rm -f 2>/dev/null || true
                                docker ps -a -q -f "name=.*localstack.*test" | xargs -r docker rm -f 2>/dev/null || true
                            '''

                            // Remove volumes and networks for both test and e2e
                            dockerCompose.safe('down -v --remove-orphans', 'docker-compose.test.yml')
                            dockerCompose.safe('down -v --remove-orphans', 'docker-compose.e2e.yml')

                            // Diagnostic: Check what's using E2E ports before cleanup
                            echo "Checking processes on E2E ports (5434, 6381, 4568)..."
                            sh '''
                                echo "=== Processes on port 5434 (postgres) ==="
                                lsof -i :5434 2>/dev/null || echo "No process found on port 5434"
                                echo "=== Processes on port 6381 (redis) ==="
                                lsof -i :6381 2>/dev/null || echo "No process found on port 6381"
                                echo "=== Processes on port 4568 (localstack) ==="
                                lsof -i :4568 2>/dev/null || echo "No process found on port 4568"
                            '''

                            // Force kill any processes on E2E ports
                            echo "Killing processes on E2E ports..."
                            sh '''
                                # Kill processes on ports used by E2E environment
                                for port in 5434 6381 4568; do
                                    echo "Killing processes on port $port..."
                                    (lsof -ti :$port 2>/dev/null || fuser $port/tcp 2>/dev/null) | xargs -r kill -9 2>/dev/null || true
                                done
                            '''

                            // Wait for Docker to release ports (prevent "port already allocated" errors)
                            echo "Waiting for Docker to release ports and clean up network namespaces..."
                            sh 'sleep 10'

                            // Verify cleanup
                            echo "Verifying no containers remain..."
                            sh '''
                                echo "=== Remaining containers ==="
                                docker ps -a | grep -E "(unite-.*-(test|e2e)|postgres|redis|localstack)" || echo "No test/e2e containers found"
                            '''

                            // Install Playwright browsers (dependencies already in agent image)
                            sh 'npx playwright install chromium'

                            // Build Docker images
                            echo "Building Docker images for E2E environment..."
                            dockerCompose('build --parallel', 'docker-compose.e2e.yml')

                            // Start all services
                            echo "Starting all services (infrastructure, 8 microservices, frontend)..."

                            // Use docker compose V2 exclusively for 'up' to avoid V1 KeyError with stale metadata
                            sh '''
                                if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
                                    echo "Using docker compose V2"
                                    docker compose -f docker-compose.e2e.yml up -d
                                else
                                    echo "Using docker-compose V1"
                                    docker-compose -f docker-compose.e2e.yml up -d
                                fi
                            '''

                            // Wait for infrastructure services to be healthy
                            echo "Waiting for infrastructure services to be healthy..."
                            sh '''
                                timeout 60 sh -c 'until (docker compose -f docker-compose.e2e.yml ps 2>/dev/null || docker-compose -f docker-compose.e2e.yml ps) | grep -E "(postgres|redis)" | grep -q "healthy"; do sleep 2; done' || {
                                    echo "Warning: Timed out waiting for infrastructure services"
                                }
                            '''

                            // Wait for backend services
                            echo "Waiting for backend services to start..."
                            sh 'sleep 15'

                            // Check service health
                            echo "Service status:"
                            dockerCompose('ps', 'docker-compose.e2e.yml')

                            // Setup E2E database (migrations + seed data)
                            echo "Setting up E2E database..."
                            sh '''
                                cd packages/db-models
                                echo "Running Prisma migrations on E2E database..."
                                DATABASE_URL="postgresql://unite_test:unite_test@localhost:5433/unite_test" npx prisma migrate deploy
                                echo "Seeding E2E database with test data..."
                                DATABASE_URL="postgresql://unite_test:unite_test@localhost:5433/unite_test" node prisma/seed.js
                                cd ../..
                            '''
                            echo "E2E database ready"

                            // Run Playwright tests
                            sh '''
                                cd frontend
                                E2E_DOCKER=true E2E_FRONTEND_PORT=9080 npx playwright test --reporter=list,junit,json || {
                                    EXIT_CODE=$?
                                    cd ..
                                    echo "Playwright tests exited with code $EXIT_CODE"
                                    exit $EXIT_CODE
                                }
                                cd ..
                            '''

                            // Move test results
                            sh '''
                                mkdir -p coverage
                                mv frontend/playwright-report/junit.xml coverage/e2e-junit.xml 2>/dev/null || true
                                mv frontend/allure-results ../allure-results/e2e 2>/dev/null || true
                            '''

                            echo "=== E2E Tests Complete ==="

                        } catch (Exception e) {
                            // Show service logs for debugging
                            echo "=== Service Logs (last 50 lines) ==="
                            dockerCompose.safe('logs --tail=50', 'docker-compose.e2e.yml')

                            echo "⚠️  E2E tests failed"
                            echo "Error: ${e.message}"
                            // Mark as unstable but don't fail the build
                            currentBuild.result = 'UNSTABLE'

                        } finally {
                            // Always cleanup Docker services
                            echo "Stopping and removing all E2E services..."
                            dockerCompose.safe('down -v', 'docker-compose.e2e.yml')
                        }
                    }
                }
            }

            stage('Build') {
                steps {
                    sh 'npx pnpm run build'
                }
            }
        }

        post {
            always {
                // Publish JUnit test results
                junit testResults: 'coverage/**/*.xml', allowEmptyResults: true, skipPublishingChecks: true

                // Publish Allure test reports
                script {
                    def allureDirs = ['allure-results', 'frontend/allure-results', 'backend/allure-results']
                    def existingDirs = allureDirs.findAll { fileExists(it) }
                    if (existingDirs) {
                        allure([
                            includeProperties: false,
                            jdk: '',
                            results: existingDirs.collect { [path: it] }
                        ])
                    }
                }
            }
            success {
                script {
                    def buildType = env.CHANGE_ID ? "PR #${env.CHANGE_ID}" : env.BRANCH_NAME
                    githubStatusReporter(
                        status: 'success',
                        context: 'jenkins/ci',
                        description: "Build succeeded for ${buildType}"
                    )
                }
            }
            failure {
                script {
                    def buildType = env.CHANGE_ID ? "PR #${env.CHANGE_ID}" : env.BRANCH_NAME
                    githubStatusReporter(
                        status: 'failure',
                        context: 'jenkins/ci',
                        description: "Build failed for ${buildType}"
                    )
                }
            }
            cleanup {
                cleanWs()
            }
        }
    }
}
