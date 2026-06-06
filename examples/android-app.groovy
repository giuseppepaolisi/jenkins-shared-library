/**
 * Jenkinsfile — App Android con build, sign e upload multi-store
 *
 * Pipeline completa per app Android.
 * Build → Test → Sign → Upload a Google Play, Huawei, Amazon in parallelo
 */
@Library('jenkins-shared-library') _

def versionName = ''
def versionCode = ''
def apkPath = ''

pipeline {
    agent { label 'android' }

    options {
        ansiColor('xterm')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        ANDROID_HOME = '/opt/android-sdk'
        GRADLEW_PATH = './gradlew'

        // --- Slack ---
        SLACK_CONFIG = credentials('slack-config-json')

        // --- Store Credentials ---
        // Android keystore
        // Google Play service account
        // Huawei credentials
        // Amazon credentials
    }

    stages {
        stage('🔍 Checkout & Setup') {
            steps {
                checkout scm
                sh 'chmod +x gradlew'

                script {
                    versionName = sh(
                        script: "grep versionName app/build.gradle | head -1 | sed 's/.*\"\\(.*\\)\".*/\\1/'",
                        returnStdout: true
                    ).trim()
                    versionCode = sh(
                        script: "grep versionCode app/build.gradle | head -1 | sed 's/[^0-9]//g'",
                        returnStdout: true
                    ).trim()
                    log.info "Versione app: ${versionName} (${versionCode})"
                }
            }
        }

        stage('🧪 Test & Lint') {
            parallel {
                stage('Unit Test') {
                    steps {
                        androidBuild.runTests()
                    }
                }
                stage('Lint') {
                    steps {
                        androidBuild.runLint()
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

        stage('🏗️ Build & Sign') {
            steps {
                script {
                    androidBuild.bumpVersionCode()
                    apkPath = androidBuild.buildAndSign(
                        flavor: 'production',
                        buildType: 'release',
                        keystoreId: 'android-keystore-prod',
                        bundle: true,
                        extraTasks: []
                    )
                    log.info "APK generato: ${apkPath}"
                }
            }
        }

        stage('📤 Upload to App Stores') {
            parallel {
                stage('Google Play (Internal)') {
                    steps {
                        androidBuild.uploadToGooglePlay(
                            artifactPath: apkPath[0],
                            serviceAccountId: 'google-play-svc-account',
                            applicationId: 'com.azienda.app',
                            track: 'internal'
                        )
                        slackNotify.notifyPhase(
                            'Google Play Internal upload',
                            'staging',
                            "Versione ${versionName}"
                        )
                    }
                }
                stage('Huawei AppGallery') {
                    steps {
                        huaweiUpload(
                            filePath: apkPath.findAll { it.endsWith('.apk') }[0],
                            distributionPhase: 'Draft',
                            releaseType: 1
                        )
                    }
                }
                stage('Amazon AppStore') {
                    steps {
                        amazonAppStore(
                            filePath: apkPath.findAll { it.endsWith('.apk') }[0],
                            releaseNotes: "Release ${versionName}",
                            isProduction: false
                        )
                    }
                }
            }
        }

        stage('📦 Archive & Publish') {
            steps {
                nexusOps.uploadArtifact(
                    apkPath[0], 'raw-releases',
                    'com.azienda.app', env.JOB_NAME.toLowerCase(),
                    "${versionName}.${versionCode}", 'aab'
                )
                rawArtifact.archive('**/build/outputs/**/*.{apk,aab}')
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
                'staging',
                "App Android v${versionName} disponibile su store interni"
            )
        }
        failure {
            slackNotify.notifyFailure(
                "Android build fallito",
                env.BRANCH_NAME,
                env.STAGE_NAME
            )
        }
        cleanup {
            pipelineUtils.cleanupWorkspace()
        }
    }
}