/**
 * Jenkinsfile — App iOS con build, sign e upload su App Store + TestFlight
 *
 * Pipeline completa per app iOS.
 * Build → Test → Archive → Export IPA → Upload App Store / TestFlight
 *
 * NOTA: Eseguire su macOS agent con Xcode installato.
 */
@Library('jenkins-shared-library') _

def ipaPath = ''
def version = ''

pipeline {
    agent { label 'macos-xcode' }

    options {
        ansiColor('xterm')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 90, unit: 'MINUTES')
    }

    environment {
        // --- Xcode Config ---
        IOS_WORKSPACE = "${env.JOB_NAME}.xcworkspace"
        IOS_SCHEME    = env.JOB_NAME
        EXPORT_METHOD = 'app-store'
        APPLE_TEAM_ID = credentials('apple-team-id')

        // --- Slack ---
        SLACK_CONFIG = credentials('slack-config-json')

        // --- App Store Connect ---
        // ASC_API_KEY_ID     = credentials('asc-api-key-id')
        // ASC_ISSUER_ID      = credentials('asc-issuer-id')
        // ASC_KEY_CONTENT    = credentials('asc-api-key-content')
    }

    stages {
        stage('🔍 Checkout & Dependencies') {
            steps {
                checkout scm

                // Installa dipendenze CocoaPods/SPM
                script {
                    if (fileExists('Podfile')) {
                        sh 'pod install --repo-update'
                    }
                }

                log.info 'Dipendenze installate'
            }
        }

        stage('🧪 Test') {
            steps {
                iosBuild.runTests(device: 'iPhone 14', osVersion: 'latest')
            }
            post {
                success {
                    junit '**/*.xcodeproj/**/test-results/**/*.xml'
                }
            }
        }

        stage('🔬 Security Scan') {
            steps {
                securityScan.filesystem('.', severity: 'CRITICAL,HIGH',
                    exitOnFailure: false)
            }
        }

        stage('🏗️ Build & Sign') {
            steps {
                script {
                    iosBuild.bumpBuildNumber()

                    version = sh(
                        script: "/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' ${env.JOB_NAME}/Info.plist",
                        returnStdout: true
                    ).trim()

                    ipaPath = iosBuild.buildAndSign(
                        matchGitUrl: credentials('fastlane-match-git-url'),
                        bundleIdentifier: "com.azienda.${env.JOB_NAME.toLowerCase()}",
                        appStoreKeyId: 'asc-api-key'
                    )

                    log.info "IPA generato: ${ipaPath}"
                }
            }
        }

        stage('📤 Upload & Distribute') {
            when {
                branch 'main'
            }
            stages {
                stage('Upload to App Store Connect') {
                    steps {
                        iosBuild.uploadToAppStore(
                            ipaPath: ipaPath,
                            appStoreKeyId: 'asc-api-key',
                            bundleId: "com.azienda.${env.JOB_NAME.toLowerCase()}"
                        )
                    }
                }
                stage('Distribute to TestFlight') {
                    steps {
                        iosBuild.distributeToTestFlight(
                            ipaPath: ipaPath,
                            testers: credentials('testflight-testers')?.split(',') ?: []
                        )
                    }
                }
            }
        }

        stage('📦 Archive IPA') {
            steps {
                nexusOps.uploadArtifact(
                    ipaPath, 'raw-releases',
                    "com.azienda.ios", env.JOB_NAME.toLowerCase(),
                    version, 'ipa'
                )
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
                "App iOS v${version} disponibile su TestFlight"
            )
        }
        failure {
            slackNotify.notifyFailure(
                "iOS build fallito",
                env.BRANCH_NAME,
                env.STAGE_NAME
            )
        }
        cleanup {
            pipelineUtils.cleanupWorkspace()
        }
    }
}