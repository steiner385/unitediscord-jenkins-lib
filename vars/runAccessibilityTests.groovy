#!/usr/bin/env groovy
/**
 * Accessibility Test Runner (#390)
 *
 * Runs axe-core WCAG 2.2 AA accessibility tests and reports violations.
 * Fails the build on critical accessibility violations.
 *
 * Features:
 * - Runs Playwright accessibility tests with axe-core
 * - Reports violations with WCAG criteria references
 * - Publishes HTML accessibility report
 * - Posts GitHub status for accessibility checks
 *
 * Usage:
 *   runAccessibilityTests()
 *   runAccessibilityTests(failOnViolations: true)
 */

def call(Map config = [:]) {
    def failOnViolations = config.failOnViolations != false  // default true
    def testPattern = config.testPattern ?: 'frontend/tests/e2e/accessibility/**/*.spec.ts'
    def statusContext = config.statusContext ?: 'jenkins/accessibility'
    def reportDir = config.reportDir ?: 'frontend/a11y-report'

    echo "=== Running Accessibility Tests ==="
    echo "WCAG Level: 2.2 AA"
    echo "Test Pattern: ${testPattern}"
    echo "Fail on Violations: ${failOnViolations}"

    def exitCode = 0
    def violationCount = 0

    try {
        // Report pending status
        githubStatusReporter(
            status: 'pending',
            context: statusContext,
            description: 'Running WCAG 2.2 AA accessibility tests...'
        )

        // Run accessibility tests with Playwright
        // Uses the existing accessibility test infrastructure in frontend/tests/e2e/accessibility/
        def result = sh(
            script: """
                cd frontend

                # Run accessibility tests with dedicated output
                npx playwright test tests/e2e/accessibility/ \
                    --reporter=list,html \
                    --output=a11y-results \
                    2>&1 | tee a11y-test-output.log

                echo "Exit code: \$?"
            """,
            returnStatus: true
        )
        exitCode = result

        // Parse test output for violation count
        violationCount = parseViolationCount()

        // Archive accessibility report
        if (fileExists("${reportDir}")) {
            archiveArtifacts artifacts: "${reportDir}/**", allowEmptyArchive: true

            publishHTML([
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: reportDir,
                reportFiles: 'index.html',
                reportName: 'Accessibility Report',
                reportTitles: 'WCAG 2.2 AA Accessibility Report'
            ])
        }

        if (exitCode != 0 && failOnViolations) {
            githubStatusReporter(
                status: 'failure',
                context: statusContext,
                description: "Found ${violationCount} accessibility violation(s)"
            )
            error("Accessibility tests failed with ${violationCount} violation(s)")
        } else if (violationCount > 0 && !failOnViolations) {
            githubStatusReporter(
                status: 'success',
                context: statusContext,
                description: "Passed (${violationCount} warnings)"
            )
            echo "WARNING: ${violationCount} accessibility violation(s) found but not blocking"
        } else {
            githubStatusReporter(
                status: 'success',
                context: statusContext,
                description: 'All accessibility checks passed'
            )
        }

        echo "=== Accessibility Tests Complete ==="

    } catch (Exception e) {
        githubStatusReporter(
            status: 'failure',
            context: statusContext,
            description: "Accessibility tests failed: ${e.message.take(50)}"
        )
        throw e
    }

    return [
        exitCode: exitCode,
        violationCount: violationCount
    ]
}

/**
 * Parse accessibility test output for violation count
 */
def parseViolationCount() {
    def count = 0
    try {
        if (fileExists('frontend/a11y-test-output.log')) {
            def output = readFile('frontend/a11y-test-output.log')
            // Look for "Found X accessibility violation(s)" in output
            def matcher = output =~ /Found (\d+) accessibility violation/
            if (matcher.find()) {
                count = matcher.group(1).toInteger()
            }
            // Alternative: count "❌ Accessibility Violations Found" blocks
            def violationMatches = output =~ /\[(\d+)\] [A-Z_]+\s+Impact:/
            if (violationMatches) {
                count = Math.max(count, violationMatches.size())
            }
        }
    } catch (Exception e) {
        echo "WARNING: Could not parse violation count: ${e.message}"
    }
    return count
}
