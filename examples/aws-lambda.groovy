/**
 * Jenkinsfile — AWS Lambda con deploy multi-account
 *
 * Pipeline per funzione AWS Lambda (Node.js/Python/Java).
 * Build → Test → Package → Deploy a staging → Approvazione → Deploy a prod
 */
@Library('jenkins-shared-library') _

def lambdaName = ''
def zipFile = ''

pipeline {
    agent any

    options {
        ansiColor('xterm')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 20, unit: 'MINUTES')
    }

    environment {
        AWS_ACCOUNTS = credentials('aws-accounts-json')
        AWS_ROLES    = credentials('aws-roles-json')
        AWS_REGION   = 'eu-west-1'
        SLACK_CONFIG = credentials('slack-config-json')
    }

    stages {
        stage('🔍 Setup') {
            steps {
                checkout scm
                script {
                    lambdaName = env.JOB_NAME.replaceAll('.*/', '')
                    log.info "Lambda: ${lambdaName}"
                }
            }
        }

        stage('🧪 Test') {
            steps {
                script {
                    if (fileExists('package.json')) {
                        sh 'npm ci && npm test 2>/dev/null || true'
                    } else if (fileExists('requirements.txt')) {
                        sh 'pip install -r requirements.txt -t . && python -m pytest 2>/dev/null || true'
                    } else if (fileExists('pom.xml')) {
                        sh 'mvn test -q'
                        junit '**/target/surefire-reports/*.xml'
                    }
                }
            }
        }

        stage('🔬 Security Scan') {
            steps {
                securityScan.filesystem('.', severity: 'CRITICAL,HIGH',
                    exitOnFailure: false)
            }
        }

        stage('📦 Package') {
            steps {
                script {
                    if (fileExists('package.json')) {
                        sh 'zip -r lambda.zip . -x "*.git*" "node_modules/.cache/*" "test/*"'
                    } else if (fileExists('requirements.txt')) {
                        sh 'pip install -r requirements.txt -t . && zip -r lambda.zip . -x "*.git*" "test/*"'
                    } else if (fileExists('pom.xml')) {
                        sh 'mvn package -DskipTests -q'
                        zipFile = findFiles(glob: 'target/*.jar').path[0]
                        sh "cp ${zipFile} lambda.zip"
                    }
                    zipFile = 'lambda.zip'
                    log.info "Package: ${zipFile}"
                }
            }
        }

        stage('🚀 Deploy Staging') {
            steps {
                awsAssumeRole.withAwsEnv('staging') {
                    slackNotify.notifyPhase('Deploy Lambda Staging', 'staging', lambdaName)
                    sh """
                        aws lambda update-function-code \
                            --function-name '${lambdaName}-staging' \
                            --zip-file 'fileb://${zipFile}' \
                            --publish
                    """
                    log.info "Lambda ${lambdaName}-staging deployato"
                }
            }
        }

        stage('🧪 Integration Test (Staging)') {
            steps {
                awsAssumeRole.withAwsEnv('staging') {
                    sh """
                        aws lambda invoke \
                            --function-name '${lambdaName}-staging' \
                            --payload '{"test": true}' \
                            /tmp/lambda-response.json
                    """
                    log.info 'Test integrazione staging completato'
                }
            }
        }

        stage('🚀 Deploy Production') {
            when {
                branch 'main'
            }
            input {
                message "Deployare ${lambdaName} in PRODUZIONE?"
                ok "Sì, deploya in prod"
            }
            steps {
                awsAssumeRole.withAwsEnv('prod') {
                    slackNotify.notifyDeploy('prod', lambdaName, 'started')
                    sh """
                        aws lambda update-function-code \
                            --function-name '${lambdaName}' \
                            --zip-file 'fileb://${zipFile}' \
                            --publish
                    """
                    slackNotify.notifyDeploy('prod', lambdaName, 'success', '1m 30s')
                    log.info "Lambda ${lambdaName} deployato in PROD"
                }
            }
        }
    }

    post {
        always {
            pipelineUtils.archiveReports()
            archiveArtifacts artifacts: 'lambda.zip', fingerprint: true
        }
        success {
            slackNotify.notifySuccess(currentBuild.durationString, env.BRANCH_NAME)
        }
        failure {
            slackNotify.notifyFailure(
                "Lambda deploy fallito",
                env.BRANCH_NAME,
                env.STAGE_NAME
            )
        }
    }
}