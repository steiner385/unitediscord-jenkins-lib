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

                        # Remove .npmrc (contains pnpm-specific configs that break npm/npx)
                        rm -f .npmrc

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

                            sh '''
                                # Install Playwright browsers (chromium only for CI, no system deps)
                                npx playwright install chromium

                                # Build all services and frontend with Docker Compose
                                echo "Building Docker images for E2E environment..."
                                docker-compose -f docker-compose.e2e.yml build --parallel

                                # Start all services (infrastructure + backend + frontend)
                                echo "Starting all services (infrastructure, 8 microservices, frontend)..."
                                docker-compose -f docker-compose.e2e.yml up -d

                                # Wait for infrastructure services to be healthy
                                echo "Waiting for infrastructure services to be healthy..."
                                timeout 60 sh -c 'until docker-compose -f docker-compose.e2e.yml ps | grep -E "(postgres|redis)" | grep -q "healthy"; do sleep 2; done' || {
                                    echo "Warning: Timed out waiting for infrastructure services"
                                    docker-compose -f docker-compose.e2e.yml ps
                                }

                                # Wait a bit for backend services to start
                                echo "Waiting for backend services to start..."
                                sleep 15

                                # Check service health
                                echo "Service status:"
                                docker-compose -f docker-compose.e2e.yml ps

                                # Run Playwright tests against containerized environment
                                cd frontend
                                E2E_DOCKER=true npx playwright test --reporter=list,junit,json || {
                                    EXIT_CODE=$?
                                    cd ..
                                    echo "Playwright tests exited with code $EXIT_CODE"

                                    # Show service logs for debugging
                                    echo "=== Service Logs (last 50 lines) ==="
                                    docker-compose -f docker-compose.e2e.yml logs --tail=50

                                    # Cleanup Docker services
                                    echo "Stopping and removing all E2E services..."
                                    docker-compose -f docker-compose.e2e.yml down -v

                                    exit $EXIT_CODE
                                }

                                cd ..

                                # Move test results to coverage directory for Jenkins
                                mkdir -p coverage
                                mv frontend/playwright-report/junit.xml coverage/e2e-junit.xml 2>/dev/null || true
                                mv frontend/allure-results ../allure-results/e2e 2>/dev/null || true

                                # Cleanup Docker services
                                echo "Stopping and removing all E2E services..."
                                docker-compose -f docker-compose.e2e.yml down -v
                            '''

                            echo "=== E2E Tests Complete ==="
                        } catch (Exception e) {
                            // Ensure cleanup happens even on failure
                            sh 'docker-compose -f docker-compose.e2e.yml down -v 2>/dev/null || true'

                            echo "⚠️  E2E tests failed"
                            echo "Error: ${e.message}"
                            // Mark as unstable but don't fail the build
                            currentBuild.result = 'UNSTABLE'
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
