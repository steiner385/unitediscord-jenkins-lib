#!/usr/bin/env groovy
/**
 * Main CI Pipeline for uniteDiscord
 *
 * Loads shared library functions from vars/ directory of this repository
 */

// Load shared library directly from GitHub (self-contained, no global config required)
library identifier: 'unitediscord-lib@main',
    retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/steiner385/unitediscord-jenkins-lib.git',
        credentialsId: 'github-credentials'
    ])

pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds(abortPrevious: true)
    }

    triggers {
        GenericTrigger(
            genericVariables: [
                [key: 'ref', value: '$.ref'],
                [key: 'after', value: '$.after']
            ],
            token: 'uniteDiscord-ci',
            regexpFilterText: '$ref',
            regexpFilterExpression: '^refs/heads/(main|develop|feature/.*|fix/.*|bugfix/.*|hotfix/.*|chore/.*|refactor/.*|docs/.*|test/.*|ci/.*|release/.*|continuous-claude/.*)$',
            printContributedVariables: true,
            printPostContent: false,
            silentResponse: false
        )
    }

    environment {
        GITHUB_OWNER = 'steiner385'
        GITHUB_REPO = 'uniteDiscord'
        CI = 'true'
        NODE_ENV = 'test'
        // AWS Bedrock configuration is injected via withAwsCredentials shared library
    }

    stages {
        stage('Initialize') {
            steps {
                // Checkout uniteDiscord repo (triggered by webhook with $ref and $after variables)
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: env.ref ?: '*/main']],
                    userRemoteConfigs: [[
                        url: "https://github.com/${env.GITHUB_OWNER}/${env.GITHUB_REPO}.git",
                        credentialsId: 'github-credentials'
                    ]]
                ])
                // Remove stale test directories and coverage files that may exist from previous builds
                sh '''
                    rm -rf frontend/frontend || true
                    rm -rf coverage || true
                    find . -path "*/coverage/*" -name "*.xml" -delete 2>/dev/null || true
                '''
                script {
                    env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                }
                echo "Building commit: ${env.GIT_COMMIT_SHORT}"
            }
        }

        stage('Install Dependencies') {
            steps {
                sh '''
                    # Increase Node.js memory limit for large dependency trees
                    export NODE_OPTIONS="--max-old-space-size=4096"

                    # Remove .npmrc (contains pnpm-specific configs that break npm/npx)
                    # It's not needed for CI since we use --frozen-lockfile
                    rm -f .npmrc

                    # Use npx to run pnpm without global installation
                    # Install dependencies using pnpm (matches local development)
                    npx --yes pnpm@latest install --frozen-lockfile
                '''
            }
        }

        stage('Build Packages') {
            steps {
                sh 'npx pnpm --filter "./packages/*" -r run build'
            }
        }

        stage('Lint') {
            steps {
                sh 'npx pnpm run lint'
            }
        }

        stage('Unit Tests') {
            steps {
                // Inject AWS credentials for Bedrock AI service tests via shared library
                script {
                    withAwsCredentials {
                        // Run unit tests (backend + frontend) with Allure reporting
                        // Use pipefail to capture vitest's exit code, not tee's
                        def testResult = sh(
                            script: '''#!/bin/bash
                                set -o pipefail
                                npx pnpm run test:unit 2>&1 | tee test-output.log
                            ''',
                            returnStatus: true
                        )
                        env.UNIT_TEST_EXIT_CODE = testResult.toString()

                        if (testResult != 0) {
                            echo "Unit tests failed with exit code ${testResult}"
                            env.UNIT_TESTS_FAILED = 'true'
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

        stage('Validate Test Results') {
            steps {
                script {
                    if (env.UNIT_TESTS_FAILED == 'true') {
                        error("Unit Tests failed")
                    }

                    echo "✓ All tests passed!"
                }
            }
        }
    }

    post {
        always {
            // Consolidate Allure results from all test types (unit, integration, contract, frontend, e2e)
            script {
                sh '''
                    #!/bin/bash
                    set +e

                    # Create consolidated allure-results directory
                    mkdir -p consolidated-allure-results

                    # Copy results from all test suites
                    for dir in allure-results/*/; do
                        if [ -d "$dir" ]; then
                            echo "Copying Allure results from $dir"
                            cp -r "$dir"* consolidated-allure-results/ 2>/dev/null || true
                        fi
                    done

                    set -e
                '''
            }

            // Publish consolidated Allure test reports if results exist
            script {
                if (fileExists('consolidated-allure-results') && !sh(script: 'test -z "$(ls -A consolidated-allure-results)"', returnStatus: true)) {
                    allure([
                        includeProperties: false,
                        jdk: '',
                        results: [[path: 'consolidated-allure-results']]
                    ])
                } else {
                    echo 'No Allure results found to publish'
                }
            }

            // Publish JUnit test results
            script {
                junit testResults: 'coverage/**/*.xml', allowEmptyResults: true, skipPublishingChecks: true
            }
        }
        success {
            echo 'Build succeeded!'
        }
        failure {
            echo 'Build failed!'
            script {
                if (env.TEST_RESULTS_FILE && fileExists(env.TEST_RESULTS_FILE)) {
                    // Analyze test failures and create grouped GitHub issues
                    echo 'Analyzing test failures...'
                    analyzeTestFailures(
                        testResultsFile: env.TEST_RESULTS_FILE,
                        maxIssues: 10
                    )
                }
                // NOTE: No fallback to generic issue creation
                // Infrastructure failures should be investigated and fixed,
                // not automatically tracked as GitHub issues
            }
        }
    }
}
