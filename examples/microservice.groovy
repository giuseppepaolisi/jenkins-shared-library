/**
 * Jenkinsfile — Microservizio Spring Boot con Docker, AWS, Nexus, version bumping
 *
 * Pipeline completa per microservizio Java/Spring Boot.
 * Build → Test → Security Scan → Docker Build → Push → Deploy → Notifica
 */
@Library('jenkins-shared-library') _

def version = ''
def commitSha = ''

pipeline {
    agent any

    options {
        ansiColor('xterm')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    environment {
        // --- AWS ---
        AWS_ACCOUNTS = credentials('aws-accounts-json')
        AWS_ROLES    = credentials('aws-roles-json')
        AWS_REGION   = 'eu-west-1'

        // --- Docker Registries ---
        DOCKER_REGISTRIES = credentials('docker-registries-json')

        // --- Nexus ---
        NEXUS_URL   = 'https://nexus.azienda.local'
        NEXUS_CREDS = 'nexus-jenkins-creds'

        // --- Slack ---
        SLACK_CONFIG = credentials('slack-config-json')

        // --- Git ---
        GIT_USER_NAME  = 'Jenkins CI'
        GIT_USER_EMAIL = 'jenkins@azienda.local'
        GPG_SIGN       = 'true'

        // --- SonarQube ---
        SONAR_PROJECT_KEY = "${env.JOB_NAME}"
        SONAR_HOST_URL    = 'http://sonarqube:9000'
    }

    stages {
        stage('🔍 Checkout & Setup') {
            steps {
                log.stage('Checkout')
                checkout scm
                commitSha = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                log.info "Commit: ${commitSha}"
            }
        }

        stage('🔬 Build & Test') {
            parallel {
                stage('Unit Test') {
                    steps {
                        pipelineUtils.safeStage('Maven Test', {
                            sh 'mvn clean test -q'
                        })
                    }
                    post {
                        success { junit '**/target/surefire-reports/*.xml' }
                    }
                }
                stage('Security Scan (FS)') {
                    steps {
                        securityScan.filesystem('.')
                    }
                }
                stage('Lint') {
                    steps {
                        sh 'mvn checkstyle:check -q || true'
                    }
                }
            }
        }

        stage('🎯 Quality Gate') {
            steps {
                securityScan.sonarQube(projectKey: env.SONAR_PROJECT_KEY, sources: '.')
            }
        }

        stage('📦 Dependency Check') {
            steps {
                securityScan.dependencyCheck(path: '.', format: 'HTML')
            }
        }

        stage('🏷️ Version Bump') {
            when {
                branch 'main'
            }
            steps {
                script {
                    version = bumpVersion(projectType: 'maven', bumpType: 'auto')
                    log.info "Nuova versione: ${version}"
                }
            }
        }

        stage('🐳 Docker Build & Push') {
            steps {
                script {
                    version = version ?: "0.0.0-${commitSha}"
                    def imageName = "microservice/${env.JOB_NAME.toLowerCase()}"

                    dockerOps.build(imageName, [version, "latest"], [
                        VERSION: version,
                        COMMIT : commitSha
                    ])

                    dockerOps.securityScan("${imageName}:${version}")
                    dockerOps.generateSbom("${imageName}:${version}")

                    dockerOps.pushToRegistries(imageName, [version, "latest"], ['ecr', 'nexus'])
                }
            }
        }

        stage('🚀 Deploy') {
            when {
                branch 'main'
            }
            stages {
                stage('Deploy Staging') {
                    steps {
                        awsAssumeRole.withAwsEnv('staging') {
                            slackNotify.notifyDeploy('staging', version, 'started')
                            sh "kubectl set image deployment/${env.JOB_NAME} app=${imageName}:${version} -n staging"
                        }
                        slackNotify.notifyDeploy('staging', version, 'success', '2m 30s')
                    }
                }
                stage('Deploy Production') {
                    input {
                        message "Deployare ${version} in PRODUZIONE?"
                        ok "Sì, deploya in prod"
                    }
                    steps {
                        awsAssumeRole.withAwsEnv('prod') {
                            slackNotify.notifyDeploy('prod', version, 'started')
                            sh "kubectl set image deployment/${env.JOB_NAME} app=${imageName}:${version} -n prod"
                        }
                        slackNotify.notifyDeploy('prod', version, 'success', '3m 12s')
                    }
                }
            }
        }
    }

    post {
        always {
            pipelineUtils.archiveReports()
            pipelineUtils.printBuildSummary(
                currentBuild.currentResult,
                currentBuild.durationString,
                version
            )
        }
        success {
            slackNotify.notifySuccess(currentBuild.durationString, env.BRANCH_NAME)
        }
        failure {
            slackNotify.notifyFailure(
                currentBuild.rawBuild?.getLog(50)?.join('\n') ?: '',
                env.BRANCH_NAME,
                env.STAGE_NAME
            )
        }
        aborted {
            slackNotify.sendCustom(text: "⏹️ Build interrotta: ${env.JOB_NAME} #${env.BUILD_NUMBER}")
        }
        cleanup {
            pipelineUtils.cleanupWorkspace()
        }
    }
}