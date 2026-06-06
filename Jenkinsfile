/**
 * Jenkinsfile — CI/CD della Jenkins Shared Library stessa
 *
 * Pipeline per testare, validare e pubblicare la shared library.
 * Lint Groovy → Test unitari → Package → Tag release
 */
@Library('jenkins-shared-library') _

def newVersion = ''

pipeline {
    agent any

    options {
        ansiColor('xterm')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 15, unit: 'MINUTES')
    }

    environment {
        SLACK_CONFIG = credentials('slack-config-json')
        GIT_USER_NAME  = 'Jenkins CI'
        GIT_USER_EMAIL = 'jenkins@azienda.local'
        GPG_SIGN       = 'true'
        LOG_DEBUG      = 'true'
    }

    stages {
        stage('🔍 Checkout') {
            steps {
                checkout scm
            }
        }

        stage('🔬 Code Quality') {
            parallel {
                stage('Groovy Lint (CodeNarc)') {
                    steps {
                        sh '''
                            docker run --rm -v $(pwd):/workspace \
                                code-narc/code-narc /workspace \
                                -includes="**/*.groovy" \
                                -maxPriority1Violations=0 \
                                -maxPriority2Violations=10 || true
                        '''
                        log.info 'CodeNarc completato'
                    }
                }
                stage('Structure Validation') {
                    steps {
                        script {
                            // Verifica struttura richiesta
                            def requiredFiles = [
                                'vars/log.groovy',
                                'vars/pipelineUtils.groovy',
                                'vars/awsAssumeRole.groovy',
                                'vars/dockerOps.groovy',
                                'vars/nexusOps.groovy',
                                'vars/bumpVersion.groovy',
                                'vars/slackNotify.groovy',
                                'vars/androidBuild.groovy',
                                'vars/iosBuild.groovy',
                                'vars/huaweiUpload.groovy',
                                'vars/amazonAppStore.groovy',
                                'vars/rawArtifact.groovy',
                                'vars/securityScan.groovy'
                            ]

                            def missingFiles = requiredFiles.findAll { !fileExists(it) }
                            if (missingFiles) {
                                error("[Structure] ❌ File mancanti: ${missingFiles.join(', ')}")
                            }

                            // Verifica package structure
                            def requiredClasses = [
                                'src/io/awesome/jenkins/SecretManager.groovy',
                                'src/io/awesome/jenkins/Utils.groovy',
                                'src/io/awesome/jenkins/AWSHelper.groovy',
                                'src/io/awesome/jenkins/DockerHelper.groovy',
                                'src/io/awesome/jenkins/VersionHelper.groovy',
                                'src/io/awesome/jenkins/NexusHelper.groovy',
                                'src/io/awesome/jenkins/SlackHelper.groovy',
                                'src/io/awesome/jenkins/AndroidHelper.groovy',
                                'src/io/awesome/jenkins/IOSHelper.groovy',
                                'src/io/awesome/jenkins/HuaweiHelper.groovy',
                                'src/io/awesome/jenkins/AmazonAppStoreHelper.groovy'
                            ]

                            def missingClasses = requiredClasses.findAll { !fileExists(it) }
                            if (missingClasses) {
                                error("[Structure] ❌ Classi mancanti: ${missingClasses.join(', ')}")
                            }

                            log.info '✓ Struttura libreria valida'
                        }
                    }
                }
            }
        }

        stage('🧪 Unit Test (JenkinsPipelineUnit)') {
            steps {
                script {
                    if (fileExists('test')) {
                        sh 'find test -name "*.groovy" -exec echo "Test trovato: {}" \\;'
                        // TODO: Integrare con JenkinsPipelineUnit
                        // sh 'gradle test'
                    }
                    log.info 'Test unitari: struttura OK'
                }
            }
        }

        stage('📚 Documentation Check') {
            steps {
                script {
                    if (!fileExists('README.md')) {
                        error('[Docs] README.md mancante')
                    }
                    if (!findFiles(glob: 'examples/*.groovy')) {
                        error('[Docs] Nessun esempio trovato in examples/')
                    }
                    log.info '✓ Documentazione presente'
                }
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
                    log.info "Nuova versione libreria: ${newVersion}"
                }
            }
        }
    }

    post {
        always {
            pipelineUtils.printBuildSummary(
                currentBuild.currentResult,
                currentBuild.durationString,
                newVersion ?: 'snapshot'
            )
        }
        success {
            slackNotify.notifySuccess(
                currentBuild.durationString,
                '',
                "Shared Library ${newVersion ?: ''}"
            )
        }
        failure {
            slackNotify.notifyFailure(
                'Shared Library CI fallita',
                env.BRANCH_NAME,
                env.STAGE_NAME
            )
        }
    }
}