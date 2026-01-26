#!/usr/bin/env groovy
/**
 * Trigger Job for reasonbridge-jenkins-lib
 *
 * This job runs whenever jenkins-lib is updated and triggers
 * a rescan of all dependent multibranch pipelines to pick up
 * the latest Jenkinsfile.multibranch changes.
 */

pipeline {
    agent any

    triggers {
        githubPush()
    }

    stages {
        stage('Trigger Dependent Pipeline Rescans') {
            steps {
                script {
                    echo "=== jenkins-lib Updated ==="
                    echo "Triggering rescan of dependent multibranch pipelines..."

                    // Trigger rescan using the multibranch-scan-webhook-trigger
                    def response = sh(
                        script: """
                            curl -s -X POST \
                                "http://localhost:8080/multibranch-webhook-trigger/invoke?token=reasonbridge-rescan-token"
                        """,
                        returnStdout: true
                    ).trim()

                    echo "API Response: ${response}"
                    echo "✅ Rescan triggered - reasonbridge-multibranch will reload latest Jenkinsfile"
                }
            }
        }
    }

    post {
        success {
            echo "✅ Dependent pipelines will pick up latest Jenkinsfile changes"
        }
        failure {
            echo "❌ Failed to trigger dependent pipeline rescans"
        }
    }
}
