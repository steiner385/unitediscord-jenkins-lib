#!/usr/bin/env groovy
/**
 * Report Publishing Utility
 * Publishes JUnit, HTML, Allure, and Playwright reports
 *
 * Features:
 * - Conditional publishing based on config
 * - Archives artifacts
 * - Handles missing reports gracefully
 * - WSL2 /mnt path workaround for junit
 *
 * Usage:
 *   publishReports(junit: true)
 *   publishReports(playwright: true, allure: true)
 *   publishReports(coverage: true, coverageDir: 'coverage/lcov-report')
 */

def call(Map config = [:]) {
    // JUnit test results
    if (config.junit) {
        def junitPattern = config.junitPattern ?: '**/junit.xml'
        def workspace = env.WORKSPACE ?: pwd()

        // Workaround for WSL2 /mnt paths - JUnit plugin hangs reading from mounted Windows paths
        if (workspace.startsWith('/mnt/')) {
            echo "WSL2 mounted workspace detected, copying junit files to native Linux path..."
            def tmpDir = "/tmp/junit-results-${env.BUILD_NUMBER}"
            sh """
                mkdir -p ${tmpDir}
                find . -name 'junit.xml' -exec cp {} ${tmpDir}/ \\; 2>/dev/null || true
                # Rename to avoid conflicts
                cd ${tmpDir} && for f in *.xml; do [ -f "\$f" ] && mv "\$f" "\$(dirname \$f)/\$(basename \$f .xml)-\$RANDOM.xml" 2>/dev/null || true; done
                ls -la ${tmpDir}/ || echo "No junit files found"
            """
            try {
                timeout(time: 2, unit: 'MINUTES') {
                    junit testResults: "${tmpDir}/*.xml", allowEmptyResults: true
                }
            } finally {
                sh "rm -rf ${tmpDir}"
            }
        } else {
            timeout(time: 2, unit: 'MINUTES') {
                junit testResults: junitPattern, allowEmptyResults: true
            }
        }
    }

    // Playwright HTML report
    if (config.playwright) {
        archiveArtifacts artifacts: 'playwright-report/**', allowEmptyArchive: true
        archiveArtifacts artifacts: 'test-results/**', allowEmptyArchive: true

        // Try to publish HTML report if plugin is available
        try {
            publishHTML(target: [
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: config.playwrightDir ?: 'playwright-report',
                reportFiles: 'index.html',
                reportName: config.playwrightReportName ?: 'Playwright Report'
            ])
        } catch (NoSuchMethodError e) {
            echo "WARNING: HTML Publisher plugin not installed - skipping Playwright HTML report"
        }
    }

    // Coverage HTML report
    if (config.coverage) {
        def coverageDir = config.coverageDir ?: 'coverage'
        archiveArtifacts artifacts: "${coverageDir}/**", allowEmptyArchive: true

        // Try to publish HTML report if plugin is available
        try {
            publishHTML(target: [
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: coverageDir,
                reportFiles: 'index.html',
                reportName: config.coverageReportName ?: 'Coverage Report'
            ])
        } catch (NoSuchMethodError e) {
            echo "WARNING: HTML Publisher plugin not installed - skipping Coverage HTML report"
        }
    }

    // Allure report
    if (config.allure) {
        archiveArtifacts artifacts: 'allure-results/**', allowEmptyArchive: true

        // Try to publish Allure report if plugin is available
        try {
            script {
                if (fileExists('allure-results')) {
                    def allureConfig = [
                        includeProperties: false,
                        jdk: '',
                        results: [[path: 'allure-results']]
                    ]

                    // Add disableTrendGraph if specified (custom Allure plugin parameter)
                    if (config.disableTrendGraph != null) {
                        allureConfig.disableTrendGraph = config.disableTrendGraph
                    }

                    allure allureConfig
                }
            }
        } catch (NoSuchMethodError e) {
            echo "WARNING: Allure plugin not installed - skipping Allure report"
        }
    }

    // Custom artifacts
    if (config.artifacts) {
        config.artifacts.each { artifact ->
            archiveArtifacts artifacts: artifact, allowEmptyArchive: true
        }
    }
}

/**
 * Publish unit test reports
 */
def unitTestReports(Map config = [:]) {
    call([
        junit: true,
        coverage: true,
        coverageDir: config.coverageDir ?: 'coverage/lcov-report',
        allure: config.allure ?: true
    ])
}

/**
 * Publish integration test reports
 */
def integrationTestReports(Map config = [:]) {
    call([
        junit: true,
        allure: config.allure ?: false
    ])
}

/**
 * Publish E2E test reports
 */
def e2eTestReports(Map config = [:]) {
    call([
        junit: config.junit ?: false,
        playwright: true,
        allure: true,
        disableTrendGraph: true  // Hide Allure trend chart (keep JUnit trend only)
    ])
}

return this
