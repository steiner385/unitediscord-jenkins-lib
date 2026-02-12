#!/usr/bin/env groovy
/**
 * Run E2E Tests Stage
 * Executes end-to-end tests with full infrastructure setup
 *
 * Features:
 * - Pre/post Docker cleanup
 * - Resource locking (test-infrastructure) [CURRENTLY DISABLED]
 * - Playwright browser installation
 * - GitHub status reporting
 * - Playwright and Allure report publishing
 *
 * Usage:
 *   runE2ETests()  // Use defaults
 *   runE2ETests(testCommand: 'npm run test:e2e')
 *   runE2ETests(browsers: ['chromium', 'firefox'])
 */

def call(Map config = [:]) {
    def statusContext = config.statusContext ?: 'jenkins/e2e'
    def testCommand = config.testCommand ?: 'npm run test:e2e'
    def lockResource = config.lockResource ?: 'test-infrastructure'
    def composeFile = config.composeFile ?: 'deployment/docker/docker-compose.test.yml'
    def ports = config.ports ?: pipelineHelpers.getServicePorts()
    def browsers = config.browsers ?: ['chromium']
    def skipLock = config.skipLock ?: false
    def skipCheckout = config.skipCheckout ?: false

    // Ensure source code is present (runners don't share filesystems)
    if (!skipCheckout) {
        checkout scm
    }

    // Install dependencies if needed
    installDependencies()

    // Pre-cleanup: Ensure clean environment
    echo "Cleaning up previous test artifacts..."
    dockerCleanup(
        composeFile: composeFile,
        ports: ports,
        cleanLockfiles: true
    )

    try {
        // Define the test execution closure
        def runTests = {
            // Report pending status
            githubStatusReporter(
                status: 'pending',
                context: statusContext,
                description: 'E2E tests running'
            )

            // Install Playwright browsers if not cached
            playwrightSetup(browsers: browsers)

            // Clean previous Allure results
            sh 'rm -rf allure-results'

            // Run E2E tests
            sh testCommand
        }

        // Run tests with or without lock
        // LOCK DISABLED: Uncomment block below to re-enable resource locking for E2E tests
        // if (skipLock) {
        //     runTests()
        // } else {
        //     lock(resource: lockResource, inversePrecedence: true) {
        //         runTests()
        //     }
        // }
        runTests()

        // Report success
        githubStatusReporter(
            status: 'success',
            context: statusContext,
            description: 'E2E tests passed'
        )

    } catch (Exception e) {
        // Report failure
        githubStatusReporter(
            status: 'failure',
            context: statusContext,
            description: 'E2E tests failed'
        )
        throw e

    } finally {
        // Always publish reports
        publishReports(
            playwright: true,
            allure: true,
            disableTrendGraph: true  // Hide Allure trend chart (keep JUnit trend only)
        )

        // Post-cleanup: Ensure Docker containers and ports are freed
        echo "Post-test cleanup..."
        dockerCleanup(
            composeFile: composeFile,
            ports: ports,
            cleanLockfiles: false
        )
    }
}

/**
 * Run E2E tests on specific browsers
 */
def withBrowsers(List browsers, Map config = [:]) {
    call(config + [browsers: browsers])
}

/**
 * Run E2E tests with visual regression
 */
def withVisualRegression(Map config = [:]) {
    call(config)

    // Run visual regression tests after E2E
    script {
        def visualResult = sh(script: 'npm run test:visual', returnStatus: true)
        if (visualResult != 0) {
            unstable('Visual regression tests detected differences')
        }
    }

    archiveArtifacts artifacts: 'visual-regression/**', allowEmptyArchive: true
}

return this
