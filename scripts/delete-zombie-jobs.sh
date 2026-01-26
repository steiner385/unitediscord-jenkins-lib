#!/bin/bash
# Delete zombie uniteDiscord jobs that remain after rename to ReasonBridge
# These jobs were renamed in the JCasC config but JCasC doesn't auto-delete old jobs

set -euo pipefail

JENKINS_URL="${JENKINS_URL:-https://jenkins.kindash.com}"
JENKINS_USER="${JENKINS_USER:-tony}"
JENKINS_TOKEN="${JENKINS_TOKEN:-}"

if [ -z "$JENKINS_TOKEN" ]; then
    echo "ERROR: JENKINS_TOKEN environment variable not set"
    echo "Usage: JENKINS_TOKEN=your-token ./delete-zombie-jobs.sh"
    exit 1
fi

echo "🧹 Cleaning up zombie uniteDiscord jobs..."
echo "Jenkins URL: $JENKINS_URL"
echo ""

# Zombie jobs to delete
ZOMBIE_JOBS=(
    "uniteDiscord-multibranch"
    "uniteDiscord-nightly"
)

for job in "${ZOMBIE_JOBS[@]}"; do
    echo "Deleting job: $job"

    # Check if job exists
    if curl -s -u "$JENKINS_USER:$JENKINS_TOKEN" -I "$JENKINS_URL/job/$job/" | grep -q "200 OK"; then
        # Delete the job
        curl -X POST -u "$JENKINS_USER:$JENKINS_TOKEN" "$JENKINS_URL/job/$job/doDelete"
        echo "✅ Deleted: $job"
    else
        echo "⚠️  Job not found (already deleted?): $job"
    fi
    echo ""
done

echo "✅ Zombie job cleanup complete!"
echo ""
echo "Remaining jobs should be:"
echo "  - KinDash-multibranch"
echo "  - KinDash-nightly"
echo "  - ReasonBridge-ci"
echo "  - ReasonBridge-nightly"
