#!/usr/bin/env groovy
/**
 * Docker Cleanup Utility
 * Stops Docker containers and kills processes on specified ports
 *
 * Features:
 * - Docker Compose v1/v2 fallback support
 * - Port cleanup via lsof/kill
 * - Container cleanup by project name pattern (e2e-build-*, unite-*-test)
 * - Stale network cleanup
 * - Optional lockfile cleanup
 *
 * Usage:
 *   dockerCleanup()  // Use defaults
 *   dockerCleanup(composeFile: 'docker-compose.test.yml', ports: [3001, 3002])
 *   dockerCleanup(cleanLockfiles: true)
 *   dockerCleanup(projectPattern: 'e2e-build-')  // Clean all containers matching pattern
 */

def call(Map config = [:]) {
    def composeFile = config.composeFile ?: 'deployment/docker/docker-compose.test.yml'
    def ports = config.ports ?: pipelineHelpers.getServicePorts()
    def cleanLockfiles = config.cleanLockfiles != null ? config.cleanLockfiles : true
    def silent = config.silent ?: false
    def projectPattern = config.projectPattern ?: ''

    def portsString = ports.join(' ')
    def lockfileCleanup = cleanLockfiles ? 'rm -f .e2e-port.json .e2e-jwt-token.json .dev-server-pid .e2e-services-pid' : ''

    if (!silent) {
        echo "Cleaning up Docker and ports..."
    }

    // Stop Docker containers using dockerCompose helper
    dockerCompose.safe('down -v --remove-orphans', composeFile)

    // Clean containers by project pattern if specified
    if (projectPattern) {
        cleanContainersByPattern(projectPattern, silent)
    }

    // Kill processes on specified ports
    sh """
        # Try lsof first, fall back to fuser, then ss+kill
        for port in ${portsString}; do
            (lsof -ti :\$port 2>/dev/null || fuser \$port/tcp 2>/dev/null || ss -tlnp 2>/dev/null | grep ":\$port " | awk '{print \$NF}' | grep -oP 'pid=\\K[0-9]+') | xargs -r kill -9 2>/dev/null || true
        done

        # Clean lockfiles if requested
        ${lockfileCleanup}
    """

    if (!silent) {
        echo "Cleanup complete"
    }
}

/**
 * Pre-test cleanup (more verbose)
 */
def preCleanup(Map config = [:]) {
    echo "Pre-test cleanup..."
    call(config + [silent: false])
}

/**
 * Post-test cleanup (quieter)
 */
def postCleanup(Map config = [:]) {
    echo "Post-test cleanup..."
    call(config + [silent: false])
}

/**
 * Clean all containers matching a project name pattern
 * Handles containers created by docker compose with COMPOSE_PROJECT_NAME
 *
 * @param pattern The pattern to match (e.g., 'e2e-build-' matches 'e2e-build-40-postgres-1')
 * @param silent If true, suppress output
 */
def cleanContainersByPattern(String pattern, boolean silent = false) {
    if (!silent) {
        echo "Cleaning containers matching pattern: ${pattern}*"
    }

    sh """
        # Remove all containers matching the pattern
        CONTAINERS=\$(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -E '^${pattern}' || true)
        if [ -n "\$CONTAINERS" ]; then
            echo "Removing containers: \$CONTAINERS"
            echo "\$CONTAINERS" | xargs -r docker rm -f 2>/dev/null || true
        fi

        # Remove networks matching the pattern
        NETWORKS=\$(docker network ls --format '{{.Name}}' 2>/dev/null | grep -E '^${pattern}' || true)
        if [ -n "\$NETWORKS" ]; then
            echo "Removing networks: \$NETWORKS"
            echo "\$NETWORKS" | xargs -r docker network rm 2>/dev/null || true
        fi

        # Remove volumes matching the pattern (be careful - only unnamed/test volumes)
        VOLUMES=\$(docker volume ls --format '{{.Name}}' 2>/dev/null | grep -E '^${pattern}' || true)
        if [ -n "\$VOLUMES" ]; then
            echo "Removing volumes: \$VOLUMES"
            echo "\$VOLUMES" | xargs -r docker volume rm 2>/dev/null || true
        fi
    """
}

/**
 * Aggressive E2E cleanup - removes ALL E2E-related containers, networks, and volumes
 * Use this before E2E tests to ensure a clean slate
 *
 * Enhanced to find containers by port binding (not just name patterns) to catch
 * zombie containers from crashed builds that might still hold ports.
 *
 * Also handles docker-proxy processes that can hold ports even after containers are removed.
 */
def aggressiveE2ECleanup() {
    echo "Performing aggressive E2E cleanup..."

    sh '''
        # Define E2E ports (3001-3007, 5000, 9080)
        E2E_PORTS="3001 3002 3003 3004 3005 3006 3007 5000 9080"

        # Step 1: Find and remove containers BY PORT BINDING
        # This catches containers regardless of naming pattern
        echo "=== Removing containers by port binding ==="
        for port in $E2E_PORTS; do
            # Find containers with this port published (running or stopped)
            CONTAINERS=$(docker ps -a --filter "publish=$port" --format '{{.Names}}' 2>/dev/null || true)
            if [ -n "$CONTAINERS" ]; then
                echo "Found containers on port $port: $CONTAINERS"
                echo "$CONTAINERS" | xargs -r docker rm -f 2>/dev/null || true
            fi
        done

        # Step 2: Remove containers by name pattern (for containers not yet bound to ports)
        echo "=== Removing E2E containers by name pattern ==="
        docker ps -a --format '{{.Names}}' 2>/dev/null | grep -E '^e2e-build-|^unite-.*-e2e|^playwright-e2e-' | xargs -r docker rm -f 2>/dev/null || true

        # Remove integration test containers (both old shared and new build-specific)
        echo "=== Removing integration test containers ==="
        docker ps -a --format '{{.Names}}' 2>/dev/null | grep -E '^int-test-build-|^unite-.*-test|postgres.*test|redis.*test|localstack.*test' | xargs -r docker rm -f 2>/dev/null || true

        # Step 3: Kill docker-proxy processes holding E2E ports
        # docker-proxy can hold ports even after containers are removed
        echo "=== Killing docker-proxy processes on E2E ports ==="
        for port in $E2E_PORTS; do
            # Find docker-proxy processes for this port
            DOCKER_PROXY_PIDS=$(ps aux 2>/dev/null | grep "docker-proxy.*:$port" | grep -v grep | awk '{print $2}' || true)
            if [ -n "$DOCKER_PROXY_PIDS" ]; then
                echo "Found docker-proxy on port $port with PIDs: $DOCKER_PROXY_PIDS"
                echo "$DOCKER_PROXY_PIDS" | xargs -r kill -9 2>/dev/null || true
            fi
        done

        # Step 4: Kill host processes on E2E ports
        # This catches orphaned processes from crashed containers or dev servers
        echo "=== Killing host processes on E2E ports ==="
        for port in $E2E_PORTS; do
            # Try multiple methods to find PIDs (lsof is most reliable)
            PID=$(lsof -ti :$port 2>/dev/null || fuser $port/tcp 2>/dev/null | tr -d ' ' || true)
            if [ -n "$PID" ]; then
                echo "Killing process $PID on port $port"
                kill -9 $PID 2>/dev/null || true
            fi
        done

        # Step 5: Remove networks (after containers are removed)
        echo "=== Removing test networks ==="
        docker network ls --format '{{.Name}}' 2>/dev/null | grep -E '^e2e-build-|^int-test-build-|_unite-e2e$|_unite-test$' | xargs -r docker network rm 2>/dev/null || true

        # Prune unused networks (safe - only removes unused)
        docker network prune -f 2>/dev/null || true

        # Step 6: Wait for Docker to release resources
        echo "=== Waiting for Docker to release resources ==="
        sleep 5

        # Step 7: Verify ports are actually free with multiple retries
        echo "=== Verifying E2E ports are free ==="
        for attempt in 1 2 3; do
            PORTS_IN_USE=""
            for port in $E2E_PORTS; do
                if lsof -ti :$port >/dev/null 2>&1 || ss -tln 2>/dev/null | grep -q ":$port "; then
                    PORTS_IN_USE="$PORTS_IN_USE $port"
                fi
            done

            if [ -z "$PORTS_IN_USE" ]; then
                echo "All E2E ports are free"
                break
            fi

            echo "Attempt $attempt/3: Ports still in use:$PORTS_IN_USE"

            if [ $attempt -lt 3 ]; then
                echo "Attempting additional cleanup..."
                for port in $PORTS_IN_USE; do
                    # Force kill any remaining processes (including docker-proxy)
                    lsof -ti :$port 2>/dev/null | xargs -r kill -9 2>/dev/null || true
                    fuser -k $port/tcp 2>/dev/null || true
                    # Also check for docker-proxy again
                    ps aux 2>/dev/null | grep "docker-proxy.*:$port" | grep -v grep | awk '{print $2}' | xargs -r kill -9 2>/dev/null || true
                done
                sleep 3
            else
                echo "WARNING: Some ports still in use after 3 cleanup attempts:$PORTS_IN_USE"
                echo "Listing processes on those ports:"
                for port in $PORTS_IN_USE; do
                    echo "Port $port:"
                    lsof -i :$port 2>/dev/null || true
                done
            fi
        done

        echo "=== Aggressive cleanup complete ==="
    '''
}

/**
 * Clean stale Docker networks that might prevent new network creation
 */
def cleanStaleNetworks() {
    echo "Cleaning stale Docker networks..."

    sh '''
        # Remove networks with 'e2e' or 'test' in the name
        docker network ls --format '{{.Name}}' 2>/dev/null | grep -E 'e2e|test' | xargs -r docker network rm 2>/dev/null || true

        # Prune unused networks
        docker network prune -f 2>/dev/null || true
    '''
}

/**
 * Verify E2E ports are free, with retry logic
 * Call this right before starting the E2E environment
 *
 * @param maxRetries Maximum number of cleanup attempts (default: 3)
 * @param retryDelay Seconds to wait between retries (default: 5)
 * @return true if all ports are free, throws exception if cleanup fails
 */
def verifyE2EPortsFree(int maxRetries = 3, int retryDelay = 5) {
    echo "Verifying E2E ports are free..."

    def portsScript = '''
        E2E_PORTS="3001 3002 3003 3004 3005 3006 3007 5000 9080"
        PORTS_IN_USE=""

        for port in $E2E_PORTS; do
            # Check if port is in use (by process or container)
            if lsof -ti :$port >/dev/null 2>&1; then
                PORTS_IN_USE="$PORTS_IN_USE $port"
            elif ss -tln 2>/dev/null | grep -q ":$port "; then
                PORTS_IN_USE="$PORTS_IN_USE $port"
            elif docker ps --filter "publish=$port" --format '{{.Names}}' 2>/dev/null | grep -q .; then
                PORTS_IN_USE="$PORTS_IN_USE $port"
            fi
        done

        if [ -n "$PORTS_IN_USE" ]; then
            echo "Ports in use:$PORTS_IN_USE"
            exit 1
        else
            echo "All E2E ports are free"
            exit 0
        fi
    '''

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        def result = sh(script: portsScript, returnStatus: true)
        if (result == 0) {
            echo "All E2E ports verified free on attempt ${attempt}"
            return true
        }

        if (attempt < maxRetries) {
            echo "Attempt ${attempt}/${maxRetries}: Ports still in use, running cleanup..."
            aggressiveE2ECleanup()
            sleep(retryDelay)
        }
    }

    error("Failed to free E2E ports after ${maxRetries} attempts. Check for zombie containers or host processes.")
}

return this
