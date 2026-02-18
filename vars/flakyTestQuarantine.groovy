#!/usr/bin/env groovy
/**
 * Flaky Test Quarantine System (#392)
 *
 * Detects, tracks, and quarantines flaky tests in CI.
 * A test is considered flaky if it fails on first attempt but passes on retry.
 *
 * Features:
 * - Detects flaky tests via retry comparison
 * - Tracks flaky test history in GitHub issues
 * - Quarantines consistently flaky tests
 * - Reports flaky test patterns
 *
 * Usage:
 *   def result = flakyTestQuarantine.analyzeResults(testResultsDir: 'coverage')
 *   flakyTestQuarantine.reportFlakyTests(flakyTests: result.flakyTests)
 *   flakyTestQuarantine.updateQuarantine(flakyTests: result.flakyTests)
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import com.cloudbees.groovy.cps.NonCPS

/**
 * Analyze test results for flaky tests
 * Compares first-run failures with retry successes to identify flaky tests
 */
def analyzeResults(Map config = [:]) {
    def testResultsDir = config.testResultsDir ?: 'coverage'
    def retryResultsDir = config.retryResultsDir ?: "${testResultsDir}/retry"
    def quarantineFile = config.quarantineFile ?: '.flaky-tests.json'

    echo "=== Analyzing Test Results for Flaky Tests ==="

    def firstRunFailures = parseTestResults("${testResultsDir}")
    def retryResults = parseTestResults("${retryResultsDir}")

    // Flaky tests = failed in first run, passed in retry
    def flakyTests = []
    def consistentFailures = []

    for (failure in firstRunFailures) {
        def testId = failure.testId
        def retryResult = retryResults.find { it.testId == testId }

        if (retryResult && retryResult.status == 'passed') {
            // Test failed first, passed on retry = flaky
            flakyTests.add([
                testId: testId,
                testName: failure.testName,
                testFile: failure.testFile,
                firstRunError: failure.errorMessage,
                retryDuration: retryResult.duration,
                detectedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
            ])
        } else if (!retryResult || retryResult.status == 'failed') {
            // Test failed both times = consistent failure
            consistentFailures.add(failure)
        }
    }

    echo "First run failures: ${firstRunFailures.size()}"
    echo "Flaky tests detected: ${flakyTests.size()}"
    echo "Consistent failures: ${consistentFailures.size()}"

    // Load existing quarantine data
    def quarantine = loadQuarantine(quarantineFile)

    // Update quarantine with new flaky test occurrences
    def updatedQuarantine = updateQuarantineData(quarantine, flakyTests)

    return [
        flakyTests: flakyTests,
        consistentFailures: consistentFailures,
        quarantine: updatedQuarantine
    ]
}

/**
 * Parse JUnit/Vitest test results from directory
 */
def parseTestResults(String directory) {
    def results = []

    if (!fileExists(directory)) {
        echo "Test results directory not found: ${directory}"
        return results
    }

    // Parse JUnit XML files
    def xmlFiles = findFiles(glob: "${directory}/**/*.xml")
    for (xmlFile in xmlFiles) {
        try {
            def content = readFile(xmlFile.path)
            results.addAll(parseJUnitXml(content, xmlFile.path))
        } catch (Exception e) {
            echo "WARNING: Could not parse ${xmlFile.path}: ${e.message}"
        }
    }

    // Parse JSON results if available (vitest --reporter=json)
    def jsonFiles = findFiles(glob: "${directory}/**/results.json")
    for (jsonFile in jsonFiles) {
        try {
            def content = readFile(jsonFile.path)
            results.addAll(parseVitestJson(content))
        } catch (Exception e) {
            echo "WARNING: Could not parse ${jsonFile.path}: ${e.message}"
        }
    }

    return results
}

/**
 * Parse JUnit XML format
 * @NonCPS for XML parsing
 */
@NonCPS
def parseJUnitXml(String xmlContent, String sourcePath) {
    def results = []
    try {
        def testsuite = new XmlSlurper().parseText(xmlContent)

        testsuite.'**'.findAll { it.name() == 'testcase' }.each { testcase ->
            def testId = "${testcase.@classname}::${testcase.@name}"
            def status = testcase.failure.size() > 0 || testcase.error.size() > 0 ? 'failed' : 'passed'
            def errorMessage = testcase.failure?.text() ?: testcase.error?.text() ?: ''

            results.add([
                testId: testId,
                testName: testcase.@name.toString(),
                testFile: testcase.@classname.toString(),
                status: status,
                duration: (testcase.@time.toString() as Double) * 1000,
                errorMessage: errorMessage.take(500)
            ])
        }
    } catch (Exception e) {
        // Ignore parse errors, continue with other files
    }
    return results
}

/**
 * Parse Vitest JSON format
 * @NonCPS for JSON parsing
 */
@NonCPS
def parseVitestJson(String jsonContent) {
    def results = []
    try {
        def parsed = new JsonSlurper().parseText(jsonContent)

        parsed.testResults?.each { fileResult ->
            fileResult.assertionResults?.each { test ->
                def testId = "${fileResult.name}::${test.fullName ?: test.title}"
                results.add([
                    testId: testId,
                    testName: test.fullName ?: test.title,
                    testFile: fileResult.name,
                    status: test.status == 'passed' ? 'passed' : 'failed',
                    duration: test.duration ?: 0,
                    errorMessage: test.failureMessages?.join('\n')?.take(500) ?: ''
                ])
            }
        }
    } catch (Exception e) {
        // Ignore parse errors
    }
    return results
}

/**
 * Load existing quarantine data
 */
def loadQuarantine(String quarantineFile) {
    def quarantine = [
        tests: [:],
        lastUpdated: null,
        version: 1
    ]

    if (fileExists(quarantineFile)) {
        try {
            def content = readFile(quarantineFile)
            quarantine = parseJsonToMap(content)
        } catch (Exception e) {
            echo "WARNING: Could not load quarantine file: ${e.message}"
        }
    }

    return quarantine
}

/**
 * Parse JSON string to serializable Map
 * @NonCPS for JSON parsing
 */
@NonCPS
def parseJsonToMap(String jsonContent) {
    def parsed = new JsonSlurper().parseText(jsonContent)
    return convertToSerializable(parsed)
}

/**
 * Convert LazyMap to HashMap for Jenkins serialization
 * @NonCPS for recursive operations
 */
@NonCPS
def convertToSerializable(obj) {
    if (obj instanceof Map) {
        def result = new HashMap()
        obj.each { k, v -> result.put(k, convertToSerializable(v)) }
        return result
    } else if (obj instanceof List) {
        def result = new ArrayList()
        obj.each { item -> result.add(convertToSerializable(item)) }
        return result
    }
    return obj
}

/**
 * Update quarantine data with new flaky test occurrences
 */
def updateQuarantineData(Map quarantine, List flakyTests) {
    def tests = quarantine.tests ?: [:]

    for (flaky in flakyTests) {
        def testId = flaky.testId
        def existing = tests[testId] ?: [
            testId: testId,
            testName: flaky.testName,
            testFile: flaky.testFile,
            occurrences: 0,
            firstSeen: flaky.detectedAt,
            lastSeen: null,
            quarantined: false,
            history: []
        ]

        existing.occurrences = (existing.occurrences ?: 0) + 1
        existing.lastSeen = flaky.detectedAt
        existing.history = (existing.history ?: []) + [
            [
                date: flaky.detectedAt,
                error: flaky.firstRunError?.take(200),
                build: env.BUILD_NUMBER
            ]
        ]

        // Keep last 10 occurrences
        if (existing.history.size() > 10) {
            existing.history = existing.history[-10..-1]
        }

        // Auto-quarantine after 3 occurrences
        if (existing.occurrences >= 3 && !existing.quarantined) {
            existing.quarantined = true
            existing.quarantinedAt = flaky.detectedAt
            echo "Auto-quarantining flaky test: ${flaky.testName} (${existing.occurrences} occurrences)"
        }

        tests[testId] = existing
    }

    quarantine.tests = tests
    quarantine.lastUpdated = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")

    return quarantine
}

/**
 * Save quarantine data to file
 */
def saveQuarantine(Map quarantine, String quarantineFile = '.flaky-tests.json') {
    def json = JsonOutput.prettyPrint(JsonOutput.toJson(quarantine))
    writeFile(file: quarantineFile, text: json)
    echo "Quarantine data saved to ${quarantineFile}"
}

/**
 * Report flaky tests to console and optionally create GitHub issues
 */
def reportFlakyTests(Map config = [:]) {
    def flakyTests = config.flakyTests ?: []
    def createIssues = config.createIssues ?: false
    def owner = config.owner ?: env.GITHUB_OWNER ?: 'steiner385'
    def repo = config.repo ?: env.GITHUB_REPO ?: 'reasonbridge'

    if (flakyTests.isEmpty()) {
        echo "No flaky tests detected"
        return
    }

    echo "=== Flaky Test Report ==="
    echo "Detected ${flakyTests.size()} flaky test(s) in this build\n"

    for (test in flakyTests) {
        echo "🔄 FLAKY: ${test.testName}"
        echo "   File: ${test.testFile}"
        echo "   Error: ${test.firstRunError?.take(100) ?: 'Unknown'}"
        echo ""
    }

    // Create GitHub issue for flaky tests if requested
    if (createIssues && flakyTests.size() > 0) {
        createFlakyTestIssue(flakyTests, owner, repo)
    }

    echo "=== End Flaky Test Report ==="
}

/**
 * Create a GitHub issue for flaky tests
 */
def createFlakyTestIssue(List flakyTests, String owner, String repo) {
    def branch = env.BRANCH_NAME ?: 'unknown'
    def buildUrl = env.BUILD_URL ?: ''
    def buildNumber = env.BUILD_NUMBER ?: ''

    def title = "CI: ${flakyTests.size()} flaky test(s) detected in build #${buildNumber}"

    def body = """## Flaky Tests Detected

**Branch:** ${branch}
**Build:** [#${buildNumber}](${buildUrl})
**Tests Affected:** ${flakyTests.size()}

### Flaky Tests

${flakyTests.collect { test ->
    """- **${test.testName}**
  - File: \`${test.testFile}\`
  - Error: \`${test.firstRunError?.take(200) ?: 'Unknown'}\`
"""
}.join('\n')}

### What Makes a Test Flaky?

A test is marked as flaky when it fails on the first attempt but passes on retry.
This indicates non-deterministic behavior that should be investigated.

### Common Causes

- Race conditions in async code
- Timing-dependent assertions
- External service dependencies
- Shared state between tests
- Database cleanup issues

### Next Steps

1. Review the test implementation
2. Add proper waits/retries if timing-dependent
3. Mock external dependencies
4. Ensure proper test isolation

---
_Auto-generated by Jenkins CI_
"""

    def labels = ['testing', 'flaky-test', 'ci']

    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def payload = JsonOutput.toJson([
            title: title,
            body: body,
            labels: labels
        ])

        def tempFile = "${env.WORKSPACE}/flaky_issue_${System.currentTimeMillis()}.json"
        writeFile(file: tempFile, text: payload)

        def response = sh(
            script: """
                curl -s -w "\\n%{http_code}" -X POST \
                    -H "Authorization: token \${GITHUB_TOKEN}" \
                    -H "Accept: application/vnd.github.v3+json" \
                    -H "Content-Type: application/json" \
                    "https://api.github.com/repos/${owner}/${repo}/issues" \
                    -d @"${tempFile}"
            """,
            returnStdout: true
        ).trim()

        sh(script: "rm -f '${tempFile}'", returnStatus: true)

        def lines = response.split('\n')
        def httpCode = lines[-1]

        if (httpCode == '201') {
            def issueData = parseJsonToMap(lines[0..-2].join('\n'))
            echo "Created flaky test issue #${issueData.number}"
        } else {
            echo "WARNING: Could not create flaky test issue (HTTP ${httpCode})"
        }
    }
}

/**
 * Get list of quarantined test patterns for test exclusion
 */
def getQuarantinedPatterns(String quarantineFile = '.flaky-tests.json') {
    def patterns = []
    def quarantine = loadQuarantine(quarantineFile)

    quarantine.tests?.each { testId, testData ->
        if (testData.quarantined) {
            // Convert test ID to grep-compatible pattern
            def pattern = testData.testName.replaceAll(/[^a-zA-Z0-9\s]/, '.')
            patterns.add(pattern)
        }
    }

    return patterns
}

/**
 * Generate vitest --exclude arguments for quarantined tests
 */
def getExcludeArgs(String quarantineFile = '.flaky-tests.json') {
    def quarantine = loadQuarantine(quarantineFile)
    def excludes = []

    quarantine.tests?.each { testId, testData ->
        if (testData.quarantined && testData.testFile) {
            excludes.add("--exclude='${testData.testFile}'")
        }
    }

    return excludes.join(' ')
}
