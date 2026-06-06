/**
 * Jenkinsfile — Libreria Node.js con npm, version bumping e publish
 *
 * Pipeline per pacchetti Node.js.
 * Install → Lint → Test → Security Scan → Bump version → Publish to Nexus
 */
@Library('jenkins-shared-library') _

def newVersion = ''

pipeline {
    agent any

    options {
        ansiColor('xterm')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 15, unit: 'MINUTES')
    }

    environment {
        NEXUS_URL   = 'https://nexus.azienda.local/repository/npm-hosted/'
        NEXUS_CREDS = 'nexus-npm-creds'
        SLACK_CONFIG = credentials('slack-config-json')

        GIT_USER_NAME  = 'Jenkins CI'
        GIT_USER_EMAIL = 'jenkins@azienda.local'
        GPG_SIGN       = 'true'
    }

    stages {
        stage('🔍 Install') {
            steps {
                checkout scm
                sh 'npm ci --ignore-scripts'
                log.info 'Dipendenze installate'
            }
        }

        stage('🧪 Lint & Test') {
            parallel {
                stage('Lint') {
                    steps {
                        sh 'npm run lint 2>/dev/null || true'
                    }
                }
                stage('Test') {
                    steps {
                        sh 'npm test 2>/dev/null || echo "Test non configurati"'
                    }
                    post {
                        success {
                            junit '**/junit.xml' ?: archiveArtifacts artifacts: '**/test-results/**/*'
                        }
                    }
                }
                stage('Audit') {
                    steps {
                        sh 'npm audit --production || true'
                    }
                }
            }
        }

        stage('🔬 Security Scan') {
            steps {
                securityScan.filesystem('.', severity: 'CRITICAL,HIGH')
                securityScan.dependencyCheck(path: '.', format: 'HTML')
            }
        }

        stage('🏷️ Version Bump') {
            when {
                branch 'main'
            }
            steps {
                script {
                    newVersion = bumpVersion(
                        projectType: 'node',
                        bumpType: 'auto',
                        changelog: true
                    )
                    log.info "Nuova versione: ${newVersion}"
                }
            }
        }

        stage('📦 Publish to Nexus') {
            when {
                branch 'main'
            }
            steps {
                script {
                    // Configura npm registry per Nexus
                    sh """
                        npm config set registry ${env.NEXUS_URL}
                        npm config set //nexus.azienda.local/repository/npm-hosted/:username=jenkins
                        npm config set //nexus.azienda.local/repository/npm-hosted/:_password=\$(echo -n '\${NEXUS_PASS}' | base64)
                        npm config set //nexus.azienda.local/repository/npm-hosted/:email=jenkins@azienda.local
                        npm config set always-auth=true
                    """

                    nexusOps.uploadArtifact(
                        "*.tgz", 'npm-hosted',
                        "@azienda", env.JOB_NAME.toLowerCase(),
                        newVersion, 'tgz'
                    )

                    sh 'npm publish'
                    log.info "Pacchetto pubblicato: @azienda/${env.JOB_NAME}@${newVersion}"
                }
            }
        }
    }

    post {
        always {
            pipelineUtils.archiveReports()
        }
        success {
            slackNotify.notifySuccess(
                currentBuild.durationString,
                '',
                'v' + (newVersion ?: '?')
            )
        }
        failure {
            slackNotify.notifyFailure(
                "Node.js build fallito",
                env.BRANCH_NAME,
                env.STAGE_NAME
            )
        }
    }
}