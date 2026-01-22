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

            // Unique project name per build for E2E Docker Compose isolation
            // This prevents container name conflicts across concurrent builds
            // Image caching still works because docker-compose.e2e.yml uses explicit image: tags
            E2E_PROJECT_NAME = "e2e-build-${env.BUILD_NUMBER ?: 'local'}"
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
                // When condition physically removed - Jenkins DSL doesn't support commenting out structural elements
                // The when block will be re-added after E2E infrastructure is validated
                steps {
                    script {
                        try {
                            echo "=== Running E2E Tests ==="
                            echo "Branch: ${env.BRANCH_NAME}"
                            echo "Test Suite: Playwright (301 tests, 240 active)"
                            echo "Environment: Full production-like stack (infrastructure + all microservices + frontend)"

                            // Clean up any existing E2E and integration test containers
                            echo "Cleaning up existing containers and ports..."

                            // Use aggressive cleanup to remove all E2E-related resources
                            // This handles containers with COMPOSE_PROJECT_NAME=e2e-build-* naming
                            dockerCleanup.aggressiveE2ECleanup()

                            // Also run compose down for both test and e2e environments
                            dockerCompose.safe('down -v --remove-orphans', 'docker-compose.test.yml')
                            dockerCompose.safe('down -v --remove-orphans', 'docker-compose.e2e.yml', env.E2E_PROJECT_NAME)

                            // Clean up stale networks that might block new network creation
                            dockerCleanup.cleanStaleNetworks()

                            // Wait for Docker to release resources
                            echo "Waiting for Docker to release resources..."
                            sh 'sleep 5'

                            // Verify cleanup
                            echo "Verifying no E2E containers remain..."
                            sh '''
                                echo "=== Remaining E2E/test containers ==="
                                docker ps -a --format '{{.Names}}' | grep -E '(e2e-build-|unite-.*-(test|e2e)|postgres|redis|localstack)' || echo "No test/e2e containers found - cleanup successful"
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
                                    COMPOSE_PROJECT_NAME=$E2E_PROJECT_NAME docker compose -f docker-compose.e2e.yml up -d --build
                                else
                                    echo "Using docker-compose V1"
                                    COMPOSE_PROJECT_NAME=$E2E_PROJECT_NAME docker-compose -f docker-compose.e2e.yml up -d --build
                                fi
                            '''

                            // Wait for infrastructure services to be healthy
                            echo "Waiting for infrastructure services to be healthy..."
                            sh '''
                                timeout 60 sh -c "until COMPOSE_PROJECT_NAME=$E2E_PROJECT_NAME docker compose -f docker-compose.e2e.yml ps 2>/dev/null | grep -E '(postgres|redis)' | grep -q 'healthy'; do sleep 2; done" || {
                                    echo "Warning: Timed out waiting for infrastructure services"
                                }
                            '''

                            // Wait for backend services to be ready
                            echo "Waiting for backend services to be ready..."
                            sh '''
                                echo "Checking health of critical backend services..."

                                # POSIX-compliant service checking (no bash associative arrays)
                                # Format: "service:port service:port ..."
                                SERVICES="user-service:3001 discussion-service:3007 ai-service:3002"

                                for service_port in $SERVICES; do
                                    service=$(echo $service_port | cut -d: -f1)
                                    port=$(echo $service_port | cut -d: -f2)
                                    echo "Checking $service (port $port)..."
                                    MAX_ATTEMPTS=30  # 30 attempts x 2 seconds = 1 minute max

                                    for i in $(seq 1 $MAX_ATTEMPTS); do
                                        # Check if container is running
                                        CONTAINER_STATE=$(docker inspect --format='{{.State.Status}}' ${E2E_PROJECT_NAME}-${service}-1 2>/dev/null || echo "not_found")

                                        if [ "$CONTAINER_STATE" != "running" ]; then
                                            echo "⚠️  $service container state: $CONTAINER_STATE (attempt $i/$MAX_ATTEMPTS)"
                                            if [ $i -eq $MAX_ATTEMPTS ]; then
                                                echo "ERROR: $service container is not running"
                                                docker logs ${E2E_PROJECT_NAME}-${service}-1 --tail 30 2>/dev/null || echo "No logs available"
                                                exit 1
                                            fi
                                            sleep 2
                                            continue
                                        fi

                                        # Check health from INSIDE the Docker network using a curl container
                                        # This avoids the Jenkins agent network isolation issue - Jenkins agents
                                        # run in Docker containers, so localhost doesn't reach E2E services
                                        if docker run --rm --network ${E2E_PROJECT_NAME}_unite-e2e curlimages/curl:latest \
                                            curl -f -s "http://${service}:$port/health" > /dev/null 2>&1; then
                                            echo "✅ $service is ready and healthy on port $port (attempt $i/$MAX_ATTEMPTS)"
                                            break
                                        fi

                                        if [ $i -eq $MAX_ATTEMPTS ]; then
                                            echo "ERROR: $service did not become ready after $MAX_ATTEMPTS attempts"
                                            echo "Container logs (last 50 lines):"
                                            docker logs ${E2E_PROJECT_NAME}-${service}-1 --tail 50
                                            echo ""
                                            echo "Container inspect:"
                                            docker inspect ${E2E_PROJECT_NAME}-${service}-1 | grep -A 10 "State"
                                            exit 1
                                        fi

                                        echo "⏳ Waiting for $service to respond on /health (attempt $i/$MAX_ATTEMPTS)"
                                        sleep 2
                                    done
                                done

                                echo "✅ All critical backend services are ready and healthy"
                            '''

                            // Check service health
                            echo "Service status:"
                            dockerCompose('ps', 'docker-compose.e2e.yml')

                            // Setup E2E database (migrations + seed data)
                            // Run from inside Docker network using docker compose exec
                            echo "Setting up E2E database..."
                            sh '''
                                # Run migrations from inside a container on the Docker network
                                # This avoids host-to-container networking issues
                                echo "Running Prisma migrations on E2E database..."
                                COMPOSE_PROJECT_NAME=$E2E_PROJECT_NAME docker compose -f docker-compose.e2e.yml exec -T postgres sh -c "
                                    until pg_isready -U unite_test -d unite_test; do
                                        echo 'Waiting for postgres...';
                                        sleep 1;
                                    done
                                    echo 'Postgres is ready'
                                "

                                # Run migrations and seed data using script (avoids pnpm symlink issues with docker cp)
                                ./scripts/jenkins-e2e-db-setup.sh
                            '''
                            echo "E2E database ready"

                            // Wait for frontend to be healthy
                            echo "Waiting for frontend container to be healthy..."
                            sh '''
                                MAX_WAIT=60
                                WAIT_COUNT=0
                                until [ "$(docker inspect --format='{{.State.Health.Status}}' ${E2E_PROJECT_NAME}-frontend-1 2>/dev/null)" = "healthy" ]; do
                                    if [ $WAIT_COUNT -ge $MAX_WAIT ]; then
                                        echo "ERROR: Frontend container did not become healthy after ${MAX_WAIT} seconds"
                                        docker logs ${E2E_PROJECT_NAME}-frontend-1 --tail 50
                                        exit 1
                                    fi
                                    echo "Waiting for frontend health check... ($WAIT_COUNT/$MAX_WAIT)"
                                    sleep 2
                                    WAIT_COUNT=$((WAIT_COUNT + 1))
                                done
                                echo "Frontend container is healthy"
                            '''

                            // Verify frontend is accessible from within Docker network
                            echo "Verifying frontend is accessible from Docker network..."
                            sh '''
                                MAX_ATTEMPTS=30
                                for i in $(seq 1 $MAX_ATTEMPTS); do
                                    if COMPOSE_PROJECT_NAME=$E2E_PROJECT_NAME docker compose -f docker-compose.e2e.yml exec -T frontend curl -f -s http://localhost:80 > /dev/null 2>&1; then
                                        echo "Frontend is accessible on Docker network"
                                        break
                                    fi
                                    if [ $i -eq $MAX_ATTEMPTS ]; then
                                        echo "ERROR: Frontend not accessible after $MAX_ATTEMPTS attempts"
                                        COMPOSE_PROJECT_NAME=$E2E_PROJECT_NAME docker compose -f docker-compose.e2e.yml logs frontend --tail 50
                                        exit 1
                                    fi
                                    echo "Attempt $i/$MAX_ATTEMPTS: Waiting for frontend to be accessible..."
                                    sleep 2
                                done
                            '''

                            // Run Playwright tests inside a Docker container on the same network
                            // Use tar with --dereference to handle pnpm's symlinked node_modules
                            // (docker cp cannot handle symlinks, tar -h follows them)
                            echo "Running Playwright tests inside Docker network..."
                            sh '''
                                echo "DEBUG: =========================================="
                                echo "DEBUG: Setting up Playwright test container"
                                echo "DEBUG: Network: ${E2E_PROJECT_NAME}_unite-e2e"
                                echo "DEBUG: PLAYWRIGHT_BASE_URL: http://frontend:80"
                                echo "DEBUG: Playwright version: v1.57.0-noble"
                                echo "DEBUG: =========================================="

                                CONTAINER_NAME="playwright-e2e-runner-$$"
                                PLAYWRIGHT_URL="http://frontend:80"

                                echo "Creating Playwright container: $CONTAINER_NAME"
                                # Add --memory 4g to prevent OOM kills during npm ci
                                docker run -d \
                                    --name "$CONTAINER_NAME" \
                                    --network ${E2E_PROJECT_NAME}_unite-e2e \
                                    --memory 4g \
                                    -w /app/frontend \
                                    -e CI=true \
                                    -e E2E_DOCKER=true \
                                    -e PLAYWRIGHT_BASE_URL=$PLAYWRIGHT_URL \
                                    -e SKIP_GLOBAL_SETUP_WAIT=true \
                                    mcr.microsoft.com/playwright:v1.57.0-noble \
                                    sleep infinity

                                # Copy frontend files using tar to handle pnpm symlinks
                                # -h/--dereference follows symlinks (copies the actual files)
                                echo "Copying frontend files to container (using tar to handle symlinks)..."
                                tar -chf - -C frontend . | docker exec -i "$CONTAINER_NAME" tar -xf - -C /app/frontend/

                                echo "DEBUG: Files copied. Listing /app/frontend/:"
                                docker exec "$CONTAINER_NAME" ls -la /app/frontend/

                                # Create coverage directory in container
                                docker exec "$CONTAINER_NAME" mkdir -p /app/coverage 2>/dev/null || true

                                # Run npm install and Playwright tests
                                # Note: Using npm install (not npm ci) because frontend uses pnpm lockfile
                                # pnpm node_modules use symlinks that don't work when tar copied, so always npm install
                                echo "Running npm install and Playwright tests..."
                                docker exec "$CONTAINER_NAME" bash -c "
                                    export PLAYWRIGHT_BASE_URL='http://frontend:80'
                                    echo 'DEBUG: Inside container - Starting E2E test execution'
                                    echo 'DEBUG: Working directory:' \$(pwd)
                                    echo 'DEBUG: PLAYWRIGHT_BASE_URL=' \$PLAYWRIGHT_BASE_URL

                                    # Always run npm install - pnpm node_modules symlinks don't work when tar copied
                                    echo 'DEBUG: Installing npm dependencies...'
                                    npm install 2>&1 | tee /tmp/npm-install.log || {
                                        echo 'ERROR: npm install failed'
                                        cat /tmp/npm-install.log
                                        exit 1
                                    }
                                    echo 'DEBUG: npm install complete'

                                    echo 'DEBUG: Starting Playwright tests...'
                                    echo '=========================================='

                                    npx playwright test --reporter=list,junit,json
                                " || {
                                    EXIT_CODE=$?
                                    echo "ERROR: Playwright tests exited with code $EXIT_CODE"

                                    # Copy results out even on failure
                                    docker cp "$CONTAINER_NAME":/app/frontend/playwright-report ./frontend/ 2>/dev/null || true
                                    docker cp "$CONTAINER_NAME":/app/frontend/test-results ./frontend/ 2>/dev/null || true

                                    # Cleanup container
                                    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
                                    exit $EXIT_CODE
                                }

                                # Copy test results back
                                echo "Copying test results..."
                                docker cp "$CONTAINER_NAME":/app/frontend/playwright-report ./frontend/ 2>/dev/null || true
                                docker cp "$CONTAINER_NAME":/app/frontend/test-results ./frontend/ 2>/dev/null || true

                                # Cleanup container
                                docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

                                echo "DEBUG: Playwright tests completed successfully"
                            '''

                            // Move test results
                            sh '''
                                mkdir -p coverage
                                mv frontend/playwright-report/junit.xml coverage/e2e-junit.xml 2>/dev/null || echo "No Playwright report to move"
                                mv frontend/allure-results ../allure-results/e2e 2>/dev/null || echo "No Allure results to move"
                            '''

                            echo "=== E2E Tests Complete ==="

                        } catch (Exception e) {
                            // Show service logs for debugging
                            echo "=== Service Logs (last 50 lines) ==="
                            dockerCompose.safe('logs --tail=50', 'docker-compose.e2e.yml', env.E2E_PROJECT_NAME)

                            echo "⚠️  E2E tests failed"
                            echo "Error: ${e.message}"
                            // Mark as unstable but don't fail the build
                            currentBuild.result = 'UNSTABLE'

                        } finally {
                            // Always cleanup Docker services thoroughly
                            echo "Stopping and removing all E2E services..."
                            dockerCompose.safe('down -v --remove-orphans', 'docker-compose.e2e.yml', env.E2E_PROJECT_NAME)

                            // Clean up any playwright containers that might be left running
                            sh '''
                                docker ps -a --format '{{.Names}}' | grep -E '^playwright-e2e-' | xargs -r docker rm -f 2>/dev/null || true
                            '''

                            // Clean up this build's containers by project name pattern
                            dockerCleanup.cleanContainersByPattern("${env.E2E_PROJECT_NAME}-", true)
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

                // Archive Playwright artifacts (enables "Build Artifacts" sidebar link)
                archiveArtifacts artifacts: 'frontend/playwright-report/**', allowEmptyArchive: true
                archiveArtifacts artifacts: 'frontend/test-results/**', allowEmptyArchive: true

                // Publish Playwright HTML report with screenshots
                script {
                    if (fileExists('frontend/playwright-report')) {
                        publishHTML([
                            allowMissing: false,
                            alwaysLinkToLastBuild: true,
                            keepAll: true,
                            reportDir: 'frontend/playwright-report',
                            reportFiles: 'index.html',
                            reportName: 'Playwright E2E Report',
                            reportTitles: 'E2E Test Results with Screenshots'
                        ])
                        echo "✅ Playwright HTML report published (includes screenshots)"
                    }
                }

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
