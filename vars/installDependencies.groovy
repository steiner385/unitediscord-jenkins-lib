#!/usr/bin/env groovy
/**
 * Install Dependencies Stage
 * Smart npm installation with caching support
 *
 * Features:
 * - Uses .npmrc.ci if present
 * - Skips install if node_modules is up-to-date
 * - Legacy peer deps support
 * - Official npm registry enforcement
 *
 * Usage:
 *   installDependencies()  // Use defaults
 *   installDependencies(legacyPeerDeps: false)
 *   installDependencies(forceInstall: true)
 */

def call(Map config = [:]) {
    def useNpmrc = config.useNpmrc != null ? config.useNpmrc : true
    def legacyPeerDeps = config.legacyPeerDeps != null ? config.legacyPeerDeps : true
    def forceInstall = config.forceInstall ?: false
    def registry = config.registry ?: 'https://registry.npmjs.org/'

    def peerDepsFlag = legacyPeerDeps ? '--legacy-peer-deps' : ''

    // Configure npm registry and .npmrc
    if (useNpmrc) {
        sh """
            # Use CI-specific npmrc if available
            if [ -f "config/tools/.npmrc.ci" ]; then
                echo "Using CI npm configuration..."
                cp config/tools/.npmrc.ci .npmrc
            elif [ -f ".npmrc.ci" ]; then
                echo "Using CI npm configuration (legacy location)..."
                cp .npmrc.ci .npmrc
            else
                # Remove any local npmrc that might point to Verdaccio
                rm -f .npmrc
            fi

            # Override any local registry config - use official npm registry
            npm config set registry ${registry}

            # Fix package-lock.json URLs if they point to local Verdaccio
            # npm ci uses resolved URLs from lock file, ignoring registry config
            if grep -q 'localhost:4873' package-lock.json 2>/dev/null; then
                echo "Fixing Verdaccio URLs in package-lock.json..."
                sed -i 's|http://localhost:4873|https://registry.npmjs.org|g' package-lock.json
            fi
        """
    }

    // Install dependencies (smart caching)
    // Detect package manager: pnpm (pnpm-lock.yaml) vs npm (package-lock.json)
    if (forceInstall) {
        sh """
            echo "Force installing dependencies..."
            rm -rf node_modules
            if [ -f "pnpm-lock.yaml" ]; then
                echo "Using pnpm (detected pnpm-lock.yaml)..."
                npx --yes pnpm@latest install --frozen-lockfile
            else
                npm ci ${peerDepsFlag}
                cp package-lock.json node_modules/.package-lock.json
            fi
        """
    } else {
        sh """
            if [ -f "pnpm-lock.yaml" ]; then
                # pnpm workspace - check if node_modules exists and is linked properly
                if [ ! -d "node_modules" ]; then
                    echo "Installing dependencies with pnpm..."
                    npx --yes pnpm@latest install --frozen-lockfile
                else
                    echo "node_modules exists, assuming dependencies up to date"
                fi
            elif [ ! -d "node_modules" ] || [ "package-lock.json" -nt "node_modules/.package-lock.json" ]; then
                echo "Installing dependencies with npm..."
                npm ci ${peerDepsFlag}
                cp package-lock.json node_modules/.package-lock.json
            else
                echo "Dependencies up to date, skipping install"
            fi
        """
    }
}

/**
 * Force clean install
 */
def clean() {
    call(forceInstall: true)
}

return this
