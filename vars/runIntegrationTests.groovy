#!/usr/bin/env groovy
/**
 * Run Integration Tests Stage
 * Executes integration tests with Docker cleanup and build-specific isolation
 *
 * Features:
 * - Build-specific Docker project names (no resource locking needed)
 * - Pre/post Docker cleanup
 * - GitHub status reporting
 * - JUnit report publishing
 * - Automatic package building (includes Prisma client generation)
 *
 * Usage:
 *   runIntegrationTests()  // Use defaults
 *   runIntegrationTests(testCommand: 'npm run test:integration')
 *   runIntegrationTests(projectName: 'int-test-build-42')  // Build-specific isolation
 */

def call(Map config = [:]) {
    def statusContext = config.statusContext ?: 'jenkins/integration'
    def testCommand = config.testCommand ?: 'npm run test:integration'
    def composeFile = config.composeFile ?: 'deployment/docker/docker-compose.test.yml'
    def ports = config.ports ?: pipelineHelpers.getServicePorts()
    def skipCheckout = config.skipCheckout ?: false
    // Build-specific project name for isolation (eliminates need for locking)
    def projectName = config.projectName ?: ''

    // Ensure source code is present (runners don't share filesystems)
    if (!skipCheckout) {
        checkout scm
    }

    // Install dependencies if needed
    installDependencies()

    // Build packages (includes Prisma client generation)
    // This is required because integration tests need compiled packages
    // and generated Prisma client which aren't in git
    sh '''
        echo "Building packages (required for Prisma client)..."
        npx pnpm --filter "./packages/*" -r run build
    '''

    // Pre-cleanup: clean this build's specific containers if using isolation
    if (projectName) {
        echo "Using build-specific isolation: ${projectName}"
        dockerCleanup.cleanContainersByPattern("${projectName}-", false)
    } else {
        // Legacy: clean shared resources
        dockerCleanup(
            composeFile: composeFile,
            ports: ports,
            cleanLockfiles: true
        )
    }

    // Clean previous results
    sh 'rm -rf allure-results'

    // Report pending status
    githubStatusReporter(
        status: 'pending',
        context: statusContext,
        description: 'Integration tests running'
    )

    try {
        // Run tests with build-specific project name (no lock needed)
        if (projectName) {
            // Inject COMPOSE_PROJECT_NAME into the test command environment
            withEnv(["COMPOSE_PROJECT_NAME=${projectName}"]) {
                sh testCommand
            }
        } else {
            // Legacy: no isolation
            sh testCommand
        }

        // Report success
        githubStatusReporter(
            status: 'success',
            context: statusContext,
            description: 'Integration tests passed'
        )

    } catch (Exception e) {
        // Report failure
        githubStatusReporter(
            status: 'failure',
            context: statusContext,
            description: 'Integration tests failed'
        )
        throw e

    } finally {
        // Always publish reports and cleanup
        publishReports(junit: true, allure: true)

        // Cleanup: target this build's specific containers if using isolation
        if (projectName) {
            dockerCleanup.cleanContainersByPattern("${projectName}-", false)
        } else {
            dockerCleanup(
                composeFile: composeFile,
                ports: ports,
                cleanLockfiles: false
            )
        }
    }
}

return this
