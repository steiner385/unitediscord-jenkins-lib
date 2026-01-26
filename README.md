# reasonbridge-jenkins-lib

Jenkins shared library for reasonbridge CI pipelines.

## Purpose

This library is **intentionally decoupled** from the main application repository to allow CI/CD infrastructure changes without being blocked by application-level branch protection rules.

## Usage

In your application's Jenkinsfile:

```groovy
@Library('reasonbridge-lib@main') _

standardPipeline()
```

Or with custom configuration:

```groovy
@Library('reasonbridge-lib@main') _

standardPipeline(
    githubOwner: 'steiner385',
    githubRepo: 'reasonbridge',
    webhookToken: 'reasonbridge-ci',
    statusContext: 'Jenkins CI',
    buildPackages: true,
    runLint: true,
    runUnitTests: true,
    runBuild: true,
    timeoutMinutes: 60
)
```

## Available Functions

### Pipeline Wrappers

| Function | Description |
|----------|-------------|
| `standardPipeline()` | Complete CI pipeline with all stages |

### Composable Stages

| Function | Description |
|----------|-------------|
| `installDependencies()` | Install project dependencies (auto-detects pnpm/yarn/npm) |
| `buildProject()` | Build the project and archive artifacts |
| `runLintChecks()` | Run ESLint and other linters |
| `runUnitTests()` | Run unit tests with coverage |
| `runIntegrationTests()` | Run integration tests |
| `runE2ETests()` | Run Playwright E2E tests |

### Utilities

| Function | Description |
|----------|-------------|
| `pipelineHelpers` | Helper functions (getPackageManager, getProjectName, etc.) |
| `githubStatusReporter()` | Report build status to GitHub |
| `withAwsCredentials {}` | Inject AWS credentials if available |
| `dockerCleanup()` | Clean up Docker resources |
| `publishReports()` | Publish test and coverage reports |

## Jenkins Configuration

Add this library to Jenkins global configuration:

1. Manage Jenkins → System → Global Pipeline Libraries
2. Add new library:
   - Name: `reasonbridge-lib`
   - Default version: `main`
   - Retrieval method: Modern SCM
   - Source: Git
   - Project Repository: `https://github.com/steiner385/reasonbridge-jenkins-lib.git`

## Directory Structure

```
├── vars/                    # Global pipeline functions
│   ├── standardPipeline.groovy
│   ├── installDependencies.groovy
│   ├── buildProject.groovy
│   ├── runUnitTests.groovy
│   ├── pipelineHelpers.groovy
│   └── ...
├── src/                     # (Reserved for classes)
└── resources/               # (Reserved for non-Groovy files)
```

## Branch Protection

This repository intentionally has **lighter branch protection** than the application repository to allow CI infrastructure changes to be made independently. However, changes should still be reviewed and tested before merging.
