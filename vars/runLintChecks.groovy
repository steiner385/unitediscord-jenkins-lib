#!/usr/bin/env groovy
/**
 * Run Lint & Type Checks Stage
 * Executes linting and type checking with GitHub status reporting
 *
 * Features:
 * - GitHub status reporting (pending/success/failure)
 * - Configurable lint and type-check commands
 * - Optional skip for either check
 *
 * Usage:
 *   runLintChecks()  // Use defaults
 *   runLintChecks(lintCommand: 'npm run lint:fix')
 *   runLintChecks(skipTypeCheck: true)
 */

def call(Map config = [:]) {
    def statusContext = config.statusContext ?: 'jenkins/lint'

    // Auto-detect package manager and set appropriate commands
    def packageManager = fileExists('pnpm-lock.yaml') ? 'pnpm' : 'npm'
    def lintCommand = config.lintCommand ?: "${packageManager} run lint"
    def typeCheckCommand = config.typeCheckCommand ?: "${packageManager} run type-check"

    def skipLint = config.skipLint ?: false
    def skipTypeCheck = config.skipTypeCheck ?: false
    def typeCheckIgnoreErrors = config.typeCheckIgnoreErrors ?: false
    def skipCheckout = config.skipCheckout ?: false

    // Ensure source code is present (runners don't share filesystems)
    if (!skipCheckout) {
        checkout scm
    }

    // Install dependencies if needed
    installDependencies()

    // Report pending status
    githubStatusReporter(
        status: 'pending',
        context: statusContext,
        description: 'Linting in progress'
    )

    try {
        // Run lint
        if (!skipLint) {
            sh lintCommand
        }

        // Run type check
        if (!skipTypeCheck) {
            if (typeCheckIgnoreErrors) {
                sh "${typeCheckCommand} || true"
            } else {
                sh typeCheckCommand
            }
        }

        // Report success
        githubStatusReporter(
            status: 'success',
            context: statusContext,
            description: 'Lint passed'
        )

    } catch (Exception e) {
        // Report failure
        githubStatusReporter(
            status: 'failure',
            context: statusContext,
            description: 'Lint failed'
        )
        throw e
    }
}

return this
