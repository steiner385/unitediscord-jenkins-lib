#!/usr/bin/env groovy
/**
 * Report Publishing Utility
 * Publishes JUnit, HTML, Allure, and Playwright reports
 *
 * Features:
 * - Conditional publishing based on config
 * - Archives artifacts
 * - Handles missing reports gracefully
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
        junit testResults: junitPattern, allowEmptyResults: true
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

        script {
            if (fileExists('allure-results')) {
                allure includeProperties: false,
                       jdk: '',
                       results: [[path: 'allure-results']]
            }
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
        allure: false  // Disabled to remove Allure trend chart from job page (keep standard JUnit trend only)
    ])
}

return this
