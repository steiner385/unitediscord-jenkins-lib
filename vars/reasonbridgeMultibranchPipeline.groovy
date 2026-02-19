#!/usr/bin/env groovy
/**
 * reasonbridge Multi-Branch Pipeline (Global Variable)
 *
 * This is the actual pipeline definition, called from the minimal stub Jenkinsfile
 * in the main reasonbridge repo.
 *
 * CI Strategy: Fast feedback on all pushes, full CI on PRs and protected branches
 *
 * Stages (by build type):
 *
 *   ALL BUILDS (feature branch pushes):
 *   - Initialize: Setup, checkout, and GitHub status reporting
 *   - Install Dependencies: pnpm install with frozen lockfile
 *   - Build Packages: Build shared packages and generate Prisma client
 *   - Lint: ESLint and code quality checks
 *   - Unit Tests: Run unit tests (backend + frontend) - 1,221 tests
 *   - Build: Production build and artifact generation
 *
 *   FULL CI (PRs + protected branches: main, develop, staging, deploy/*):
 *   - All of the above, plus:
 *   - Integration Tests: Run integration tests - 124 tests
 *   - Contract Tests: API contract validation tests
 *   - E2E Tests: End-to-end browser tests with Playwright - 301 tests
 *
 * Multi-branch Jenkins provides these environment variables automatically:
 *   BRANCH_NAME   - Current branch name
 *   CHANGE_ID     - PR number (null if not a PR build)
 *
 * Branch Discovery: ALL branches are discovered
 *   - Feature branches get fast CI (lint + unit tests) ~3-5 min
 *   - PRs and protected branches get full CI ~15-20 min
 *
 * Webhook Setup:
 *   URL: https://jenkins.kindash.com/github-webhook/
 *   Content type: application/json
 *   Events: Push, Pull requests
 */

/**
 * Determines if full CI should run (integration, contract, E2E tests)
 * Full CI runs for:
 *   - Pull requests (env.CHANGE_ID is set, or PR exists for branch)
 *   - Protected branches: main, develop, staging, deploy/*
 */
def isFullCI() {
    // PR builds always get full CI
    if (env.CHANGE_ID) {
        return true
    }
    // Protected branches get full CI
    def protectedBranches = ['main', 'master', 'develop', 'staging']
    if (env.BRANCH_NAME in protectedBranches) {
        return true
    }
    // Deploy branches get full CI
    if (env.BRANCH_NAME?.startsWith('deploy/')) {
        return true
    }
    // Check if a PR exists for this branch (handles buildOriginBranchWithPR=true case)
    // When building the branch directly, env.CHANGE_ID is null even if a PR exists
    if (env.HAS_OPEN_PR == 'true') {
        return true
    }
    return false
}

/**
 * Check if the current branch has an open PR
 * Sets env.HAS_OPEN_PR to 'true' if a PR exists
 */
def checkForOpenPR() {
    try {
        withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
            def result = sh(
                script: """
                    curl -s -H "Authorization: token \$GITHUB_TOKEN" \
                        -H "Accept: application/vnd.github.v3+json" \
                        "https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/pulls?head=${env.GITHUB_OWNER}:${env.BRANCH_NAME}&state=open" \
                        | grep -c '"number"' || echo "0"
                """,
                returnStdout: true
            ).trim()
            if (result.toInteger() > 0) {
                env.HAS_OPEN_PR = 'true'
                echo "Found open PR for branch ${env.BRANCH_NAME}"
            } else {
                env.HAS_OPEN_PR = 'false'
                echo "No open PR found for branch ${env.BRANCH_NAME}"
            }
        }
    } catch (Exception e) {
        echo "Warning: Could not check for open PR: ${e.message}"
        env.HAS_OPEN_PR = 'false'
    }
}

def call() {
    // Determine build type for logging
    def fullCI = isFullCI()
    def buildMode = fullCI ? "FULL CI" : "FAST CI (lint + unit tests only)"
    echo "🚀 Running ${buildMode} for branch: ${env.BRANCH_NAME}"
    if (!fullCI) {
        echo "ℹ️  Open a PR to run integration, contract, and E2E tests"
    }

    pipeline {
        agent any

        triggers {
            githubPush()
        }

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
                        // Check if branch has an open PR (for buildOriginBranchWithPR=true case)
                        // This must run before isFullCI() to set env.HAS_OPEN_PR
                        if (!env.CHANGE_ID) {
                            checkForOpenPR()
                        }

                        // Determine build type for logging and status reporting
                        def buildType = env.CHANGE_ID ? "PR #${env.CHANGE_ID}" : (env.HAS_OPEN_PR == 'true' ? "Branch with PR: ${env.BRANCH_NAME}" : "Branch: ${env.BRANCH_NAME}")
                        def ciMode = isFullCI() ? "Full CI" : "Fast CI"
                        echo "=== Multi-Branch Build ==="
                        echo "Build type: ${buildType}"
                        echo "CI Mode: ${ciMode}"
                        if (isFullCI()) {
                            echo "Stages: Lint → Unit → Integration → Contract → E2E → Build"
                        } else {
                            echo "Stages: Lint → Unit → Build (open PR for full CI)"
                        }
                        if (env.CHANGE_ID) {
                            echo "PR Title: ${env.CHANGE_TITLE ?: 'N/A'}"
                            echo "PR Author: ${env.CHANGE_AUTHOR ?: 'N/A'}"
                            echo "Target Branch: ${env.CHANGE_TARGET ?: 'N/A'}"
                        }
                        echo "=========================="

                        // NOTE: jenkins/ci status is only reported at build completion (post block)
                        // NOT at build start. This prevents the race condition where:
                        //   1. jenkins/ci: pending is reported
                        //   2. Individual stage checks (lint, unit-tests, integration) pass
                        //   3. GitHub allows merge because pending != failure
                        //   4. E2E tests run for 20+ more minutes
                        //   5. jenkins/ci: failure is reported AFTER merge
                        //
                        // By NOT reporting pending, GitHub's "required check not yet reported"
                        // logic blocks the merge until the entire pipeline completes.
                        echo "Build type: ${buildType} (${isFullCI() ? 'Full CI' : 'Fast CI'})"
                        // Note: GithubSkipNotifications trait in job config suppresses
                        // automatic continuous-integration/jenkins/branch status
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

                        # Also clear pnpm store cache to avoid corrupted symlinks
                        # The ENOENT errors during pnpm install suggest stale symlink targets
                        rm -rf ~/.local/share/pnpm/store 2>/dev/null || true

                        # Fresh install with pnpm - with retry on failure
                        # pnpm symlink creation can fail intermittently on busy systems
                        echo "Installing dependencies with pnpm..."
                        for attempt in 1 2 3; do
                            if npx --yes pnpm@latest install --frozen-lockfile; then
                                echo "pnpm install succeeded on attempt $attempt"
                                break
                            else
                                echo "pnpm install failed on attempt $attempt"
                                if [ $attempt -lt 3 ]; then
                                    echo "Cleaning up and retrying..."
                                    rm -rf node_modules
                                    sleep 2
                                else
                                    echo "pnpm install failed after 3 attempts"
                                    exit 1
                                fi
                            fi
                        done

                        # Verify critical packages are properly installed
                        echo "Verifying installation..."
                        if [ ! -d "node_modules/.pnpm" ]; then
                            echo "ERROR: pnpm store not found in node_modules"
                            exit 1
                        fi
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
                            testCommand: 'npx pnpm run test:unit -- --coverage'
                        )
                    }
                }
            }

            stage('Integration Tests') {
                when {
                    expression { isFullCI() }
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
                when {
                    expression { isFullCI() }
                }
                steps {
                    // catchError marks stage as FAILURE (red) but build as UNSTABLE (yellow)
                    // This allows the build to continue and post.unstable can report success for PRs
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        sh 'npx pnpm run test:contract'
                    }
                }
            }

            // Parent stage wraps Pre-Build E2E and E2E Tests with a single lock
            // This prevents other pipelines from running their E2E stages while this one is active
            stage('E2E Environment') {
                when {
                    expression {
                        // Only run E2E for full CI builds (PRs and protected branches)
                        if (!isFullCI()) {
                            return false
                        }
                        // Skip E2E for staging branches (dependency updates that passed lint/unit/integration)
                        def sourceBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME
                        return !(sourceBranch?.startsWith('staging/'))
                    }
                }
                stages {
                    stage('Pre-Build E2E') {
                        steps {
                            // catchError marks stage as FAILURE (red) but build as UNSTABLE (yellow)
                            // This allows the build to continue and post.unstable can report success for PRs
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                            script {
                                echo "=== Pre-Building E2E Environment ==="
                                echo "This stage pre-pulls and pre-builds all Docker images BEFORE E2E tests"
                                echo "to prevent memory spikes during test execution."

                                // Pre-pull external Docker images (skip if already cached locally)
                                // This prevents Docker Hub timeout issues when images are already cached
                                echo "Pre-pulling external Docker images (skipping if cached locally)..."
                                sh '''
                                    pull_if_missing() {
                                        local image="$1"
                                        if docker image inspect "$image" > /dev/null 2>&1; then
                                            echo "✓ $image already cached locally"
                                        else
                                            echo "Pulling $image..."
                                            docker pull "$image" || echo "⚠ Warning: Failed to pull $image, continuing..."
                                        fi
                                    }

                                    echo "Checking Playwright Docker image (~1.5GB)..."
                                    pull_if_missing "mcr.microsoft.com/playwright:v1.58.0-noble"

                                    echo "Checking curl image for health checks..."
                                    pull_if_missing "curlimages/curl:latest"

                                    echo "Checking base images for E2E services..."
                                    pull_if_missing "postgres:15-alpine"
                                    pull_if_missing "redis:7-alpine"
                                    pull_if_missing "localstack/localstack:3.0"

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
                    }

                    // E2E Tests stage - nested inside E2E Environment (inherits parent lock)
                    // NO 'when' clause - parent E2E Environment handles staging branch skip
                    // NO 'options/lock' - parent E2E Environment holds the lock for entire lifecycle
                    stage('E2E Tests') {
                        environment {
                            // Track retry results for flaky test detection (#392)
                            FLAKY_TEST_TRACKING = 'true'
                        }
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
                                docker ps -a --format '{{.Names}}' | grep -E '(e2e-build-|reasonbridge-.*-(test|e2e)|postgres|redis|localstack)' || echo "No test/e2e containers found - cleanup successful"
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
                                        if docker run --rm --network ${E2E_PROJECT_NAME}_reasonbridge-e2e curlimages/curl:latest \
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
                                    until pg_isready -U reasonbridge_test -d reasonbridge_test; do
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
                                echo "DEBUG: Network: ${E2E_PROJECT_NAME}_reasonbridge-e2e"
                                echo "DEBUG: PLAYWRIGHT_BASE_URL: http://frontend:80"
                                echo "DEBUG: Playwright version: v1.57.0-noble"
                                echo "DEBUG: =========================================="

                                CONTAINER_NAME="playwright-e2e-runner-$$"
                                PLAYWRIGHT_URL="http://frontend:80"

                                echo "Creating Playwright container: $CONTAINER_NAME"
                                # Use 3g memory - runner-3 has 6GB total, services need ~2GB
                                # Reduced from 4g due to OOM (exit code 137) during E2E startup
                                docker run -d \
                                    --name "$CONTAINER_NAME" \
                                    --network ${E2E_PROJECT_NAME}_reasonbridge-e2e \
                                    --memory 3g \
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
                                    -e CI=true \
                                    "$CONTAINER_NAME" bash -c "
                                    set -e -o pipefail  # Exit on error, catch pipe failures
                                    echo 'DEBUG: Inside container - Starting E2E test execution'
                                    echo 'DEBUG: Working directory:' \$(pwd)
                                    echo 'DEBUG: PLAYWRIGHT_BASE_URL=' \$PLAYWRIGHT_BASE_URL
                                    echo 'DEBUG: CI=' \$CI

                                    # Install @playwright/test (the test framework) - ~5MB, fast install
                                    # The official Playwright Docker image has browsers pre-installed but
                                    # not the @playwright/test package that our config file imports
                                    # NOTE: allure-playwright is NOT installed (skipped in CI via playwright.config.ts)
                                    # NOTE: --legacy-peer-deps bypasses peer dependency conflicts (e.g., react-joyride requiring React 15-18 while we use 19)
                                    echo 'DEBUG: Installing @playwright/test...'
                                    (npm install @playwright/test@1.58.0 --no-save --prefer-offline --legacy-peer-deps 2>&1 | tail -3) || npm install @playwright/test@1.58.0 --no-save --legacy-peer-deps
                                    echo 'DEBUG: Playwright version:' \$(npx playwright --version)

                                    echo 'DEBUG: Starting Playwright tests...'
                                    echo '=========================================='

                                    # Use reporters configured in playwright.config.ts
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

                            // Run accessibility tests while E2E environment is still running (#390)
                            echo "=== Running Accessibility Tests ==="
                            echo "WCAG Level: 2.2 AA"
                            echo "Framework: axe-core via @axe-core/playwright"

                            githubStatusReporter(
                                status: 'pending',
                                context: 'jenkins/accessibility',
                                description: 'Running WCAG 2.2 AA accessibility tests...'
                            )

                            def a11yResult = sh(
                                script: """
                                    CONTAINER_NAME="playwright-a11y-runner-\$\$"

                                    echo "Creating accessibility test container..."
                                    docker run -d \
                                        --name "\$CONTAINER_NAME" \
                                        --network ${env.E2E_PROJECT_NAME}_reasonbridge-e2e \
                                        --memory 2g \
                                        -w /app/frontend \
                                        -e CI=true \
                                        -e PLAYWRIGHT_BASE_URL='http://frontend:80' \
                                        mcr.microsoft.com/playwright:v1.58.0-noble \
                                        sleep infinity

                                    # Copy essential files for accessibility tests
                                    tar -chf - -C frontend tests/e2e/accessibility tests/e2e/helpers playwright.config.ts package.json tsconfig.json tsconfig.node.json | \
                                        docker exec -i "\$CONTAINER_NAME" tar -xf - -C /app/frontend/

                                    # Copy root tsconfig
                                    tar -chf - tsconfig.base.json | docker exec -i "\$CONTAINER_NAME" tar -xf - -C /app/

                                    # Run accessibility tests
                                    docker exec \
                                        -e PLAYWRIGHT_BASE_URL='http://frontend:80' \
                                        -e CI=true \
                                        "\$CONTAINER_NAME" bash -c "
                                            npm install @playwright/test@1.58.0 axe-playwright --no-save --legacy-peer-deps 2>&1 | tail -5
                                            npx playwright test tests/e2e/accessibility/ --reporter=html,list --output=a11y-results 2>&1
                                        " || {
                                            EXIT_CODE=\$?
                                            docker cp "\$CONTAINER_NAME":/app/frontend/playwright-report ./frontend/a11y-report 2>/dev/null || true
                                            docker rm -f "\$CONTAINER_NAME" 2>/dev/null || true
                                            exit \$EXIT_CODE
                                        }

                                    docker cp "\$CONTAINER_NAME":/app/frontend/playwright-report ./frontend/a11y-report 2>/dev/null || true
                                    docker rm -f "\$CONTAINER_NAME" 2>/dev/null || true
                                """,
                                returnStatus: true
                            )

                            if (a11yResult == 0) {
                                githubStatusReporter(
                                    status: 'success',
                                    context: 'jenkins/accessibility',
                                    description: 'All WCAG 2.2 AA checks passed'
                                )
                                echo "✅ All accessibility tests passed"
                            } else {
                                // Report as success with warning - don't block PRs for a11y issues yet
                                // This gives visibility without blocking development
                                githubStatusReporter(
                                    status: 'success',
                                    context: 'jenkins/accessibility',
                                    description: 'WCAG 2.2 AA violations found (non-blocking)'
                                )
                                echo "⚠️  Accessibility violations found (see report for details)"
                            }

                            // Publish accessibility report
                            if (fileExists('frontend/a11y-report')) {
                                publishHTML([
                                    allowMissing: true,
                                    alwaysLinkToLastBuild: true,
                                    keepAll: true,
                                    reportDir: 'frontend/a11y-report',
                                    reportFiles: 'index.html',
                                    reportName: 'Accessibility Report',
                                    reportTitles: 'WCAG 2.2 AA Accessibility Report'
                                ])
                            }

                            echo "=== Accessibility Tests Complete ==="

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

                    // Flaky Test Analysis stage (#392)
                    // Analyzes test results to detect and report flaky tests
                    stage('Flaky Test Analysis') {
                        steps {
                            script {
                                echo "=== Analyzing Test Results for Flaky Tests ==="

                                // Load quarantine data
                                def quarantineFile = '.flaky-tests.json'
                                def flakyTests = []

                                try {
                                    // Parse Playwright test results for retries
                                    // Playwright with retries enabled produces retry info in results
                                    if (fileExists('frontend/test-results')) {
                                        def result = flakyTestQuarantine.analyzeResults(
                                            testResultsDir: 'coverage',
                                            quarantineFile: quarantineFile
                                        )

                                        flakyTests = result.flakyTests ?: []

                                        if (flakyTests.size() > 0) {
                                            echo "⚠️  Detected ${flakyTests.size()} flaky test(s)"

                                            // Report flaky tests
                                            flakyTestQuarantine.reportFlakyTests(
                                                flakyTests: flakyTests,
                                                createIssues: env.BRANCH_NAME == 'main'
                                            )

                                            // Save updated quarantine data
                                            flakyTestQuarantine.saveQuarantine(result.quarantine, quarantineFile)

                                            // Archive quarantine data
                                            archiveArtifacts artifacts: quarantineFile, allowEmptyArchive: true
                                        } else {
                                            echo "✅ No flaky tests detected"
                                        }
                                    } else {
                                        echo "No test results found for flaky test analysis"
                                    }
                                } catch (Exception e) {
                                    echo "WARNING: Flaky test analysis failed: ${e.message}"
                                    // Don't fail the build for flaky test analysis issues
                                }

                                echo "=== Flaky Test Analysis Complete ==="
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

                // Publish Allure test reports (if plugin is available)
                script {
                    def allureDirs = ['allure-results', 'frontend/allure-results', 'backend/allure-results']
                    def existingDirs = allureDirs.findAll { fileExists(it) }
                    if (existingDirs) {
                        try {
                            allure([
                                includeProperties: false,
                                jdk: '',
                                disableTrendGraph: true,  // Hide Allure trend chart (keep JUnit trend only)
                                results: existingDirs.collect { [path: it] }
                            ])
                        } catch (NoSuchMethodError e) {
                            echo "WARNING: Allure plugin not installed - skipping Allure report"
                        }
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
                    // For PRs and protected branches, treat UNSTABLE as success
                    // UNSTABLE can come from: E2E flakiness, Contract Test issues, Allure/JUnit plugin artifacts
                    // Required checks (lint, unit-tests, integration) already passed at this point
                    if (env.CHANGE_ID || sourceBranch?.startsWith('staging/') || sourceBranch in ['main', 'master', 'develop']) {
                        // Override build result to SUCCESS for PRs and protected branches
                        // This affects the automatic continuous-integration/jenkins/pr-merge status
                        currentBuild.result = 'SUCCESS'
                        githubStatusReporter(
                            status: 'success',
                            context: 'jenkins/ci',
                            description: "Build succeeded for ${buildType}"
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
