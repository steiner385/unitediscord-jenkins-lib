#!/usr/bin/env groovy
/**
 * Standard CI Pipeline Configuration
 *
 * This function returns a configuration map for use in Declarative Pipelines.
 * Since Declarative Pipeline blocks cannot be placed inside shared library functions,
 * this provides a configuration and helper pattern instead.
 *
 * Usage in Jenkinsfile:
 *   @Library('reasonbridge-lib@main') _
 *   def cfg = standardPipeline.config(githubRepo: 'reasonbridge')
 *   // Then use cfg.* values in your pipeline
 */

/**
 * Get default configuration for standard CI pipeline
 */
def config(Map userConfig = [:]) {
    return [
        githubOwner: userConfig.githubOwner ?: 'steiner385',
        githubRepo: userConfig.githubRepo ?: 'reasonbridge',
        webhookToken: userConfig.webhookToken ?: "${userConfig.githubRepo ?: 'reasonbridge'}-ci",
        statusContext: userConfig.statusContext ?: 'Jenkins CI',
        buildPackages: userConfig.buildPackages != false,
        runLint: userConfig.runLint != false,
        runUnitTests: userConfig.runUnitTests != false,
        runBuild: userConfig.runBuild != false,
        timeoutMinutes: userConfig.timeoutMinutes ?: 60
    ]
}

/**
 * Initialize checkout based on webhook payload
 */
def initializeCheckout(Map cfg) {
    def checkoutBranch = env.BRANCH_NAME ?: 'main'
    def checkoutCommit = params.after ?: ''

    echo "Building branch: ${checkoutBranch}"
    if (checkoutCommit) {
        echo "Commit SHA: ${checkoutCommit}"
    }

    checkout([
        $class: 'GitSCM',
        branches: [[name: checkoutCommit ?: "*/${checkoutBranch}"]],
        userRemoteConfigs: [[
            url: "https://github.com/${cfg.githubOwner}/${cfg.githubRepo}.git",
            credentialsId: 'github-credentials'
        ]]
    ])

    env.GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
    echo "Building commit: ${env.GIT_COMMIT_SHORT}"
}

/**
 * Run build packages stage
 */
def buildPackages() {
    def pm = pipelineHelpers.getPackageManager()
    sh "${pm.filter} \"./packages/*\" -r run build"
}

/**
 * Run lint stage
 */
def runLint() {
    def pm = pipelineHelpers.getPackageManager()
    sh "${pm.run} lint"
}

/**
 * Run unit tests stage
 */
def runUnitTests() {
    def pm = pipelineHelpers.getPackageManager()
    try {
        withAwsCredentials {
            sh "${pm.run} test:unit -- --coverage"
        }
    } catch (Exception e) {
        echo "WARNING: AWS credentials not available: ${e.message}"
        sh "${pm.run} test:unit -- --coverage"
    }
}

/**
 * Run build stage
 */
def runBuild() {
    def pm = pipelineHelpers.getPackageManager()
    sh "${pm.run} build"
}

/**
 * Report success status to GitHub
 */
def reportSuccess(String context = 'Jenkins CI') {
    githubStatusReporter(
        status: 'success',
        context: context,
        description: 'All checks passed'
    )
}

/**
 * Report failure status to GitHub
 */
def reportFailure(String context = 'Jenkins CI') {
    githubStatusReporter(
        status: 'failure',
        context: context,
        description: 'Build or tests failed'
    )
}

/**
 * Report pending status to GitHub
 */
def reportPending(String context = 'Jenkins CI') {
    githubStatusReporter(
        status: 'pending',
        context: context,
        description: 'Build in progress'
    )
}

return this
