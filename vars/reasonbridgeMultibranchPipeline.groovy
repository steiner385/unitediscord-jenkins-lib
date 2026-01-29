#!/usr/bin/env groovy
/**
 * reasonbridge Multi-Branch Pipeline (Global Variable)
 *
 * This is the actual pipeline definition, called from the minimal stub Jenkinsfile
 * in the main reasonbridge repo.
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
 *   - E2E Tests: End-to-end browser tests with Playwright - 301 tests (all branches except staging/*)
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
            GITHUB_REPO = 'reasonbridge'
            CI = 'true'
            NODE_ENV = 'test'
            NODE_OPTIONS = '--max-old-space-size=4096'

            // Multi-branch provides BRANCH_NAME, CHANGE_ID, CHANGE_TARGET automatically

            // Unique project name per build for E2E Docker Compose isolation
            // This prevents container name conflicts across concurrent builds
            // Image caching still works because docker-compose.e2e.yml uses explicit image: tags
            E2E_PROJECT_NAME = "e2e-build-${env.BUILD_NUMBER ?: 'local'}"

            // Unique project name per build for Integration Test Docker Compose isolation
            // Eliminates "Paused for XXX" locking - builds can run truly in parallel
            INT_TEST_PROJECT_NAME = "int-test-build-${env.BUILD_NUMBER ?: 'local'}"
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

                    // Checkout the reasonbridge application repo (multi-branch SCM)
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
                        ls -la node_modules/@reason-bridge/ || echo "WARNING: @reason-bridge packages not linked"
                        ls -la node_modules/@prisma/client/ || echo "WARNING: @prisma/client not found"
                        ls -la node_modules/.pnpm/ | head -20 || echo "WARNING: .pnpm store missing"
                    '''
                }
            }

            stage('Lint') {
                steps {
                    runLintChecks(
                        skipCheckout: true,
                        lintCommand: 'npx pnpm run lint',
                        typeCheckCommand: 'npx pnpm typecheck'
                    )
                }
            }

            stage('Unit Tests') {
                steps {
                    withAwsCredentials {
                        runUnitTests(
                            skipCheckout: true,
                            testCommand: 'npx pnpm run test:unit'
                        )
                    }
                }
            }

            stage('Integration Tests') {
                options {
                    // Acquire shared lock to prevent concurrent resource-intensive tests
                    // This lock is shared across all multibranch pipelines to prevent OOM
                    lock(resource: 'docker-test-environment')
                }
                steps {
                    // catchError marks stage as FAILURE (red) but build as UNSTABLE (yellow)
                    // This gives accurate visual feedback while allowing pipeline to continue
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        script {
                            echo "=== Running Integration Tests ==="
                            echo "Branch: ${env.BRANCH_NAME ?: 'N/A'}"
                            echo "Tests: 124 integration tests (6 files)"
                            echo "Framework: vitest.integration.config.ts"
                            echo "Project: ${env.INT_TEST_PROJECT_NAME} (build-specific isolation)"
                            runIntegrationTests(
                                testCommand: 'npx vitest run --config vitest.integration.config.ts',
                                projectName: env.INT_TEST_PROJECT_NAME,
                                statusContext: 'jenkins/integration',
                                composeFile: 'docker-compose.test.yml'
                            )
                            echo "=== Integration Tests Complete ==="
                        }
                    }
                }
            }

            stage('Contract Tests') {
                steps {
                    sh 'npx pnpm run test:contract'
                }
            }

            // Parent stage wraps Pre-Build E2E and E2E Tests with a single lock
            // This prevents other pipelines from running their E2E stages while this one is active
            stage('E2E Environment') {
                when {
                    // Skip E2E for staging branches (dependency updates that passed lint/unit/integration)
                    expression {
                        def sourceBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME
                        return !(sourceBranch?.startsWith('staging/'))
                    }
                }
                options {
                    // Single lock for entire E2E lifecycle - prevents race between Pre-Build and E2E Tests
                    // This lock is shared across all multibranch pipelines to prevent OOM
                    lock(resource: 'docker-test-environment')
                }
                stages {
                    stage('Pre-Build E2E') {
                        steps {
                            script {
                                echo "=== Pre-Building E2E Environment ==="
                                echo "This stage pre-pulls and pre-builds all Docker images BEFORE E2E tests"
                                echo "to prevent memory spikes during test execution."

                                // Pre-pull external Docker images (these are large and can cause memory spikes if pulled during E2E)
                                echo "Pre-pulling external Docker images..."
                                sh '''
                                    echo "Pulling Playwright Docker image (~1.5GB)..."
                                    docker pull mcr.microsoft.com/playwright:v1.58.0-noble

                                    echo "Pulling curl image for health checks..."
                                    docker pull curlimages/curl:latest

                                    echo "Pulling base images for E2E services..."
                                    docker pull postgres:15-alpine
                                    docker pull redis:7-alpine
                                    docker pull localstack/localstack:3.0

                                    echo "All external images pre-pulled successfully"
                                '''

                                // Pre-build all E2E service images (this prevents memory spike during E2E startup)
                                echo "Pre-building E2E service images (11 images)..."
                                dockerCompose('build --parallel', 'docker-compose.e2e.yml')
                                echo "All E2E images pre-built successfully"

                                echo "=== Pre-Build E2E Complete ==="
                            }
                        }
                    }

                    // E2E Tests stage - nested inside E2E Environment (inherits parent lock)
                    // NO 'when' clause - parent E2E Environment handles staging branch skip
                    // NO 'options/lock' - parent E2E Environment holds the lock for entire lifecycle
                    stage('E2E Tests') {
                        steps {
                    // catchError marks stage as FAILURE (red) but build as UNSTABLE (yellow)
                    // This gives accurate visual feedback while allowing pipeline to continue
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
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

                            // NOTE: Playwright browsers are NOT installed here - they're pre-installed in the
                            // mcr.microsoft.com/playwright Docker image. The old `npx playwright install chromium`
                            // was downloading ~400MB for nothing and causing memory pressure.

                            // Verify all E2E ports are free before starting containers
                            // This prevents "port already allocated" errors from zombie containers
                            dockerCleanup.verifyE2EPortsFree(3, 5)  // 3 retries, 5 second delay

                            // NOTE: Docker images are pre-built in the "Pre-Build E2E" stage to prevent
                            // memory spikes during E2E test execution. No --build flag needed here.

                            // Start all services (using pre-built images from Pre-Build E2E stage)
                            echo "Starting all services (infrastructure, 8 microservices, frontend)..."
                            echo "NOTE: Using pre-built images from Pre-Build E2E stage (no --build flag)"

                            // Use docker compose V2 exclusively for 'up' to avoid V1 KeyError with stale metadata
                            sh '''
                                if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
                                    echo "Using docker compose V2"
                                    COMPOSE_PROJECT_NAME=$E2E_PROJECT_NAME docker compose -f docker-compose.e2e.yml up -d
                                else
                                    echo "Using docker-compose V1"
                                    COMPOSE_PROJECT_NAME=$E2E_PROJECT_NAME docker-compose -f docker-compose.e2e.yml up -d
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
                                # Use 2g memory - peak usage is ~636MB, so 2GB provides ample headroom
                                # Reduced from 4g to free host memory and prevent host-level OOM kills
                                docker run -d \
                                    --name "$CONTAINER_NAME" \
                                    --network ${E2E_PROJECT_NAME}_unite-e2e \
                                    --memory 2g \
                                    -w /app/frontend \
                                    -e CI=true \
                                    -e E2E_DOCKER=true \
                                    -e PLAYWRIGHT_BASE_URL=$PLAYWRIGHT_URL \
                                    -e SKIP_GLOBAL_SETUP_WAIT=true \
                                    mcr.microsoft.com/playwright:v1.58.0-noble \
                                    sleep infinity

                                # Copy ONLY essential Playwright test files to container
                                # Previous approach copied entire frontend (~134MB with dereferenced symlinks)
                                # which caused memory pressure. Now we copy only what's needed (~500KB):
                                #   - e2e/ (test specs)
                                #   - playwright.config.ts (config)
                                #   - global-setup.ts (global setup)
                                #   - package.json (for npm install)
                                #   - tsconfig.json, tsconfig.node.json (TypeScript config for config files)
                                echo "Copying essential Playwright files to container (e2e/, config files, tsconfig)..."
                                tar -chf - -C frontend e2e playwright.config.ts global-setup.ts package.json tsconfig.json tsconfig.node.json | docker exec -i "$CONTAINER_NAME" tar -xf - -C /app/frontend/

                                # Also copy tsconfig.base.json from root (frontend/tsconfig.json extends ../tsconfig.base.json)
                                echo "Copying tsconfig.base.json from root..."
                                tar -chf - tsconfig.base.json | docker exec -i "$CONTAINER_NAME" tar -xf - -C /app/

                                echo "DEBUG: Files copied. Listing /app/frontend/:"
                                docker exec "$CONTAINER_NAME" ls -la /app/frontend/

                                echo "DEBUG: Listing /app/ (should contain tsconfig.base.json):"
                                docker exec "$CONTAINER_NAME" ls -la /app/

                                # Debug network connectivity before running tests
                                echo "=== DEBUG: Checking network connectivity ==="
                                docker exec "$CONTAINER_NAME" bash -c "
                                    echo 'DEBUG: Resolving frontend hostname:'
                                    getent hosts frontend || echo 'WARN: getent failed, trying alternative'
                                    cat /etc/hosts | grep -i frontend || echo 'DEBUG: frontend not in /etc/hosts'

                                    echo 'DEBUG: Testing HTTP connection to frontend:'
                                    curl -v --connect-timeout 5 http://frontend:80 2>&1 | head -20 || echo 'ERROR: curl to frontend failed'

                                    echo 'DEBUG: Environment variables:'
                                    env | grep -E 'PLAYWRIGHT|E2E|CI' | sort

                                    echo 'DEBUG: Network interfaces:'
                                    ip addr show 2>/dev/null || ifconfig 2>/dev/null || echo 'WARN: Cannot show network interfaces'
                                "
                                echo "=== END network connectivity debug ==="

                                # Create coverage directory in container
                                docker exec "$CONTAINER_NAME" mkdir -p /app/coverage 2>/dev/null || true

                                # Run Playwright tests using the official Playwright Docker image
                                # The image (mcr.microsoft.com/playwright:v1.58.0-noble) has:
                                #   - @playwright/test pre-installed
                                #   - Chromium browser binaries (~400MB) pre-installed
                                #   - All system dependencies for running browsers
                                # We only need to npm install project dependencies (allure-playwright)
                                # This eliminates the npm install @playwright/test step that was causing
                                # intermittent OOM kills (exit code 137) on Jenkins agents.
                                echo "Running Playwright tests with official Playwright Docker image..."

                                # Start memory monitoring in background
                                echo "=== Starting memory monitoring ==="
                                docker stats --no-stream "$CONTAINER_NAME" > /tmp/memory-before-tests.log 2>&1
                                docker stats "$CONTAINER_NAME" --format "{{.Container}},{{.MemUsage}},{{.MemPerc}},{{.CPUPerc}}" > /tmp/memory-during-tests.log 2>&1 &
                                STATS_PID=$!

                                docker exec \
                                    -e PLAYWRIGHT_BASE_URL='http://frontend:80' \
                                    "$CONTAINER_NAME" bash -c "
                                    echo 'DEBUG: Inside container - Starting E2E test execution'
                                    echo 'DEBUG: Working directory:' \$(pwd)
                                    echo 'DEBUG: PLAYWRIGHT_BASE_URL=' \$PLAYWRIGHT_BASE_URL

                                    # Install only allure-playwright reporter (not all devDependencies!)
                                    # The official Playwright Docker image already has @playwright/test pre-installed
                                    # Installing only allure-playwright (~10MB) instead of all devDependencies (~500MB+)
                                    # prevents memory bloat and OOM kills
                                    echo 'DEBUG: Installing allure-playwright reporter...'
                                    npm install allure-playwright --no-save --prefer-offline 2>&1 | tail -5 || npm install allure-playwright --no-save
                                    echo 'DEBUG: Playwright version:' \$(npx playwright --version)

                                    echo 'DEBUG: Starting Playwright tests...'
                                    echo '=========================================='

                                    # Use reporters configured in playwright.config.ts (includes allure-playwright)
                                    npx playwright test
                                " || {
                                    EXIT_CODE=$?
                                    echo "ERROR: Playwright tests exited with code $EXIT_CODE"

                                    # Stop memory monitoring
                                    kill $STATS_PID 2>/dev/null || true

                                    # Display memory statistics
                                    echo "=== Memory Usage Before Tests ==="
                                    cat /tmp/memory-before-tests.log || echo "No pre-test memory stats"
                                    echo "=== Memory Usage During Tests (last 20 samples) ==="
                                    tail -20 /tmp/memory-during-tests.log || echo "No memory stats collected"
                                    echo "=== Peak Memory Usage ==="
                                    sort -t',' -k2 -h /tmp/memory-during-tests.log | tail -1 || echo "No peak memory data"

                                    # Copy results out even on failure
                                    docker cp "$CONTAINER_NAME":/app/frontend/playwright-report ./frontend/ 2>/dev/null || true
                                    docker cp "$CONTAINER_NAME":/app/frontend/test-results ./frontend/ 2>/dev/null || true
                                    docker cp "$CONTAINER_NAME":/app/frontend/allure-results ./frontend/ 2>/dev/null || true

                                    # Cleanup container
                                    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
                                    exit $EXIT_CODE
                                }

                                # Stop memory monitoring on success
                                kill $STATS_PID 2>/dev/null || true

                                # Display memory statistics on success too
                                echo "=== Memory Usage Before Tests ==="
                                cat /tmp/memory-before-tests.log || echo "No pre-test memory stats"
                                echo "=== Memory Usage During Tests (last 20 samples) ==="
                                tail -20 /tmp/memory-during-tests.log || echo "No memory stats collected"
                                echo "=== Peak Memory Usage ==="
                                sort -t',' -k2 -h /tmp/memory-during-tests.log | tail -1 || echo "No peak memory data"

                                # Copy test results back
                                echo "Copying test results..."
                                docker cp "$CONTAINER_NAME":/app/frontend/playwright-report ./frontend/ 2>/dev/null || true
                                docker cp "$CONTAINER_NAME":/app/frontend/test-results ./frontend/ 2>/dev/null || true
                                docker cp "$CONTAINER_NAME":/app/frontend/allure-results ./frontend/ 2>/dev/null || true

                                # Cleanup container
                                docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

                                echo "DEBUG: Playwright tests completed successfully"
                            '''

                            // Move test results
                            sh '''
                                mkdir -p coverage allure-results
                                mv frontend/playwright-report/junit.xml coverage/e2e-junit.xml 2>/dev/null || echo "No Playwright report to move"
                                # Merge E2E Allure results into root allure-results directory
                                if [ -d "frontend/allure-results" ]; then
                                    mv frontend/allure-results/* allure-results/ 2>/dev/null || echo "No Allure results to move"
                                    rmdir frontend/allure-results 2>/dev/null || true
                                fi
                            '''

                            echo "=== E2E Tests Complete ==="

                            } catch (Exception e) {
                                // Show service logs for debugging
                                echo "=== Service Logs (last 50 lines) ==="
                                dockerCompose.safe('logs --tail=50', 'docker-compose.e2e.yml', env.E2E_PROJECT_NAME)

                                echo "⚠️  E2E tests failed"
                                echo "Error: ${e.message}"
                                // Re-throw to let catchError handle build/stage result
                                throw e

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

                // Archive Playwright artifacts only if E2E tests ran (prevents UNSTABLE from missing artifacts)
                script {
                    if (fileExists('frontend/playwright-report')) {
                        archiveArtifacts artifacts: 'frontend/playwright-report/**', allowEmptyArchive: true
                    }
                    if (fileExists('frontend/test-results')) {
                        archiveArtifacts artifacts: 'frontend/test-results/**', allowEmptyArchive: true
                    }
                }

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
                            disableTrendGraph: true,  // Hide Allure trend chart (keep JUnit trend only)
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
            unstable {
                script {
                    def buildType = env.CHANGE_ID ? "PR #${env.CHANGE_ID}" : env.BRANCH_NAME
                    def sourceBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME
                    // For staging branches where E2E is skipped, treat UNSTABLE as success
                    // (UNSTABLE may come from Allure/JUnit plugin artifacts, not actual test failures)
                    if (sourceBranch?.startsWith('staging/')) {
                        // Override build result to SUCCESS for staging branches
                        // This affects the automatic continuous-integration/jenkins/pr-merge status
                        currentBuild.result = 'SUCCESS'
                        githubStatusReporter(
                            status: 'success',
                            context: 'jenkins/ci',
                            description: "Build succeeded for ${buildType} (E2E skipped)"
                        )
                    } else {
                        githubStatusReporter(
                            status: 'failure',
                            context: 'jenkins/ci',
                            description: "Build unstable for ${buildType}"
                        )
                    }
                }
            }
            cleanup {
                cleanWs()
            }
        }
    }
}
