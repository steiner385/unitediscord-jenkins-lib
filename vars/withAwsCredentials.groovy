#!/usr/bin/env groovy
/**
 * Shared library function to inject AWS credentials for Bedrock access
 *
 * Usage:
 *   withAwsCredentials {
 *       // Your code here with AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_REGION available
 *       sh 'aws bedrock-runtime invoke-model ...'
 *   }
 *
 * Available environment variables:
 *   - AWS_ACCESS_KEY_ID: AWS access key for authentication
 *   - AWS_SECRET_ACCESS_KEY: AWS secret key for authentication
 *   - AWS_REGION: AWS region (default: us-east-1)
 *   - BEDROCK_ENABLED: Set to 'true' when credentials are available
 *   - BEDROCK_DEFAULT_MODEL: Default Bedrock model ID
 */

def call(Closure body) {
    withCredentials([
        string(credentialsId: 'aws-access-key-id', variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY'),
        string(credentialsId: 'aws-region', variable: 'AWS_REGION')
    ]) {
        // Set additional Bedrock-specific environment variables
        withEnv([
            'BEDROCK_ENABLED=true',
            'BEDROCK_DEFAULT_MODEL=us.anthropic.claude-3-5-haiku-20241022-v1:0',
            'BEDROCK_MODEL_HAIKU=us.anthropic.claude-3-5-haiku-20241022-v1:0',
            'BEDROCK_MODEL_SONNET=us.anthropic.claude-3-5-sonnet-20241022-v2:0',
            'BEDROCK_MODEL_OPUS=us.anthropic.claude-opus-4-5-20251101-v1:0'
        ]) {
            sh 'echo "AWS Bedrock credentials injected (region: $AWS_REGION)"'
            body()
        }
    }
}

/**
 * Verify AWS Bedrock access is working
 * Returns true if Bedrock is accessible, false otherwise
 */
def verify() {
    def result = false
    withCredentials([
        string(credentialsId: 'aws-access-key-id', variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY'),
        string(credentialsId: 'aws-region', variable: 'AWS_REGION')
    ]) {
        try {
            def output = sh(
                script: '''
                    aws bedrock list-foundation-models \
                        --region $AWS_REGION \
                        --query "modelSummaries[?providerName=='Anthropic'] | length(@)" \
                        --output text 2>/dev/null || echo "0"
                ''',
                returnStdout: true
            ).trim()

            def modelCount = output.toInteger()
            if (modelCount > 0) {
                echo "AWS Bedrock verified: ${modelCount} Anthropic models available"
                result = true
            } else {
                echo "WARNING: AWS Bedrock verification failed - no models available"
            }
        } catch (Exception e) {
            echo "WARNING: AWS Bedrock verification failed - ${e.message}"
        }
    }
    return result
}

return this
