#!/usr/bin/env groovy
/**
 * Main CI Pipeline for uniteDiscord
 *
 * Uses local shared library functions from .jenkins/vars/
 */

pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds(abortPrevious: true)
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
                // Remove stale test directories that may exist from previous builds
                sh 'rm -rf frontend/frontend || true'
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
                        // Run tests with JSON output for intelligent failure analysis
                        // Use pipefail to capture Jest's exit code, not tee's
                        def testResult = sh(
                            script: '''#!/bin/bash
                                set -o pipefail
                                npx pnpm run test:unit -- --reporter=json --outputFile=test-results.json 2>&1 | tee test-output.log
                            ''',
                            returnStatus: true
                        )
                        env.TEST_EXIT_CODE = testResult.toString()
                        env.TEST_RESULTS_FILE = 'test-results.json'

                        if (testResult != 0) {
                            error("Unit tests failed with exit code ${testResult}")
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
            // Publish Allure test reports if results exist
            script {
                if (fileExists('allure-results')) {
                    allure([
                        includeProperties: false,
                        jdk: '',
                        results: [[path: 'allure-results']]
                    ])
                }
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
