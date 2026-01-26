#!/usr/bin/env groovy
/**
 * GitHub Status Reporter
 * Reports build status to GitHub commit status API
 *
 * Usage:
 *   githubStatusReporter(status: 'pending', context: 'jenkins/ci', description: 'Build started')
 *   githubStatusReporter(status: 'success', context: 'jenkins/unit-tests', description: 'All tests passed')
 *   githubStatusReporter(status: 'failure', context: 'jenkins/e2e', description: 'E2E tests failed')
 *
 * Parameters:
 *   status      - GitHub status: 'pending', 'success', 'failure', 'error'
 *   context     - Status context (e.g., 'jenkins/ci', 'jenkins/unit-tests')
 *   description - Short description of the status
 *   targetUrl   - Optional URL (defaults to BUILD_URL)
 */

def call(Map config = [:]) {
    def status = config.status ?: 'pending'
    def context = config.context ?: 'jenkins/ci'
    def description = config.description ?: 'Jenkins build'
    def targetUrl = config.targetUrl ?: env.BUILD_URL

    // Validate status
    def validStatuses = ['pending', 'success', 'failure', 'error']
    if (!validStatuses.contains(status)) {
        error "Invalid status '${status}'. Must be one of: ${validStatuses.join(', ')}"
    }

    // Get repository info from environment or git remote
    def owner = env.GITHUB_OWNER ?: 'steiner385'
    def repo = env.GITHUB_REPO

    // If GITHUB_REPO not set, try to extract from git remote URL
    if (!repo) {
        try {
            def gitUrl = sh(script: 'git config --get remote.origin.url 2>/dev/null || echo ""', returnStdout: true).trim()
            if (gitUrl) {
                // Handle both HTTPS and SSH URLs:
                // https://github.com/owner/repo.git -> repo
                // git@github.com:owner/repo.git -> repo
                def matcher = gitUrl =~ /(?:github\.com[\/:])[^\/]+\/([^\/\.]+)/
                if (matcher.find()) {
                    repo = matcher.group(1)
                    echo "Extracted repo name '${repo}' from git remote URL"
                }
            }
        } catch (Exception e) {
            echo "WARNING: Could not extract repo from git URL: ${e.message}"
        }
    }

    // Fallback to job name (strip -ci, -multibranch suffixes)
    if (!repo) {
        def jobName = env.JOB_NAME?.split('/')[0] ?: 'reasonBridge'
        repo = jobName.replaceAll(/-(ci|multibranch)$/, '')
        echo "Using fallback repo name '${repo}' derived from job name"
    }

    def sha = env.GIT_COMMIT

    if (!sha) {
        echo "WARNING: GIT_COMMIT not set, skipping GitHub status update"
        return
    }

    echo "Reporting GitHub status: ${status} for ${context} on ${sha.take(7)}"

    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def payload = groovy.json.JsonOutput.toJson([
            state: status,
            context: context,
            description: description.take(140), // GitHub limits to 140 chars
            target_url: targetUrl
        ])

        def response = sh(
            script: """
                curl -s -w "\\n%{http_code}" -X POST \
                    -H "Authorization: token \${GITHUB_TOKEN}" \
                    -H "Accept: application/vnd.github.v3+json" \
                    -H "Content-Type: application/json" \
                    "https://api.github.com/repos/${owner}/${repo}/statuses/${sha}" \
                    -d '${payload}'
            """,
            returnStdout: true
        ).trim()

        def lines = response.split('\n')
        def httpCode = lines[-1]
        def body = lines[0..-2].join('\n')

        if (httpCode != '201') {
            echo "WARNING: GitHub status API returned ${httpCode}: ${body}"
        } else {
            echo "GitHub status updated successfully"
        }
    }
}

/**
 * Convenience method to report pending status
 */
def pending(String context, String description = 'Build in progress') {
    call(status: 'pending', context: context, description: description)
}

/**
 * Convenience method to report success status
 */
def success(String context, String description = 'Build succeeded') {
    call(status: 'success', context: context, description: description)
}

/**
 * Convenience method to report failure status
 */
def failure(String context, String description = 'Build failed') {
    call(status: 'failure', context: context, description: description)
}

/**
 * Convenience method to report error status
 */
def error(String context, String description = 'Build error') {
    call(status: 'error', context: context, description: description)
}
