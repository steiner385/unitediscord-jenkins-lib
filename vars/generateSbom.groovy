#!/usr/bin/env groovy
/**
 * Generate Software Bill of Materials (SBOM)
 * Creates a CycloneDX SBOM from npm dependencies
 *
 * Features:
 * - CycloneDX format (industry standard)
 * - Archives SBOM as build artifact
 * - Supports custom output paths
 *
 * Usage:
 *   generateSbom()  // Use defaults
 *   generateSbom(outputFile: 'sbom.json')
 *   generateSbom(format: 'xml', outputFile: 'sbom.xml')
 */

def call(Map config = [:]) {
    def format = config.format ?: 'json'
    def outputFile = config.outputFile ?: "sbom.${format}"
    def packagePath = config.packagePath ?: '.'
    def includeDevDeps = config.includeDevDeps != null ? config.includeDevDeps : false

    echo "Generating SBOM in ${format} format..."

    def devFlag = includeDevDeps ? '' : '--omit dev'

    try {
        sh """
            cd ${packagePath}
            npx --yes @cyclonedx/cyclonedx-npm ${devFlag} --output-format ${format} --output-file ${outputFile}
        """

        // Archive the SBOM as a build artifact
        archiveArtifacts(
            artifacts: "${packagePath}/${outputFile}",
            fingerprint: true,
            allowEmptyArchive: true
        )

        echo "SBOM generated successfully: ${outputFile}"

    } catch (Exception e) {
        echo "WARNING: SBOM generation failed: ${e.message}"
        // Don't fail the build for SBOM generation issues
    }
}

return this
