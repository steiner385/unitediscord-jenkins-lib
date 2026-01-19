#!/usr/bin/env groovy
/**
 * Scan Container Images for Vulnerabilities
 * Uses Trivy to scan Docker images for security issues
 *
 * Features:
 * - Trivy vulnerability scanning
 * - Configurable severity levels
 * - Optional fail-on-vulnerability
 * - JSON report archival
 *
 * Usage:
 *   scanContainerImage(imageName: 'myapp:latest')
 *   scanContainerImage(imageName: 'myapp:latest', severity: 'CRITICAL')
 *   scanContainerImage(imageName: 'myapp:latest', failOnVulnerability: false)
 *
 * Prerequisites:
 *   - Trivy must be installed on the Jenkins agent
 *   - Or use: docker run aquasec/trivy
 */

def call(Map config = [:]) {
    def imageName = config.imageName
    def severity = config.severity ?: 'HIGH,CRITICAL'
    def failOnVulnerability = config.failOnVulnerability != null ? config.failOnVulnerability : true
    def outputFile = config.outputFile ?: 'trivy-report.json'
    def useDocker = config.useDocker != null ? config.useDocker : true

    if (!imageName) {
        error "scanContainerImage: imageName is required"
    }

    echo "Scanning container image: ${imageName}"
    echo "Severity filter: ${severity}"

    def exitCode = failOnVulnerability ? '--exit-code 1' : '--exit-code 0'
    def trivyCmd = useDocker ?
        "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest" :
        "trivy"

    try {
        sh """
            ${trivyCmd} image \
                --severity ${severity} \
                ${exitCode} \
                --format json \
                --output ${outputFile} \
                ${imageName}
        """

        echo "Container scan passed: No ${severity} vulnerabilities found"

    } catch (Exception e) {
        if (failOnVulnerability) {
            error "Container scan failed: ${severity} vulnerabilities detected in ${imageName}"
        } else {
            unstable("Container scan warning: Vulnerabilities found in ${imageName}")
        }
    } finally {
        // Archive the scan report
        archiveArtifacts(
            artifacts: outputFile,
            fingerprint: true,
            allowEmptyArchive: true
        )
    }
}

return this
