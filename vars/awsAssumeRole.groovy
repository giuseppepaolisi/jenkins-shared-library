/**
 * awsAssumeRole.groovy — Assume ruolo IAM cross-account per operazioni AWS.
 *
 * Utilizzo:
 *   def sessionId = awsAssumeRole('staging')                              // default region
 *   awsAssumeRole.withAwsEnv('prod', 'us-east-1') { ... }                 // con closure
 *   awsAssumeRole.s3Upload('my-bucket', 'path/to/artifact.jar', './build/artifact.jar')
 *   awsAssumeRole.ecrLogin('eu-west-1', '123456789012')
 *
 * Configurazione nel Jenkinsfile:
 *   environment {
 *       AWS_ACCOUNTS = [dev: '111111111111', staging: '222222222222', prod: '333333333333']
 *       AWS_ROLES    = [dev: 'arn:aws:iam::111111111111:role/JenkinsDeployRole']
 *       AWS_REGION   = 'eu-west-1'
 *   }
 */
import io.awesome.jenkins.AWSHelper

/**
 * Assume un ruolo IAM nell'account specificato.
 * @param env ambiente (dev, staging, prod)
 * @param region regione AWS (opzionale)
 * @return sessionId
 */
def call(String env, String region = '') {
    def accounts = parseAccountMap()
    def roles = parseRoleMap()
    def awsRegion = region ?: env.AWS_REGION ?: 'eu-west-1'
    
    def helper = new AWSHelper(this, accounts, roles, awsRegion)
    return helper.assumeRole(env, awsRegion)
}

def withAwsEnv(String env, Closure closure) {
    def accounts = parseAccountMap()
    def roles = parseRoleMap()
    def region = env.AWS_REGION ?: 'eu-west-1'

    def helper = new AWSHelper(this, accounts, roles, region)
    return helper.withAwsEnv(env, region, closure)
}

def s3Upload(String bucket, String key, String filePath, Map options = [:]) {
    def accounts = parseAccountMap()
    def roles = parseRoleMap()
    def helper = new AWSHelper(this, accounts, roles)
    return helper.s3Upload(bucket, key, filePath, options)
}

def s3Download(String bucket, String key, String localPath) {
    def accounts = parseAccountMap()
    def roles = parseRoleMap()
    def helper = new AWSHelper(this, accounts, roles)
    return helper.s3Download(bucket, key, localPath)
}

def ecrLogin(String region = '', String accountId = '') {
    def accounts = parseAccountMap()
    def roles = parseRoleMap()
    def awsRegion = region ?: env.AWS_REGION ?: 'eu-west-1'
    def helper = new AWSHelper(this, accounts, roles, awsRegion)
    return helper.ecrLogin(awsRegion, accountId)
}

@NonCPS
private Map parseAccountMap() {
    def raw = env.AWS_ACCOUNTS ?: '{}'
    try {
        return readJSON(text: raw)
    } catch (Exception e) {
        echo "[awsAssumeRole] ⚠ AWS_ACCOUNTS non configurato o JSON non valido: ${raw}"
        return [dev: '000000000000']
    }
}

@NonCPS
private Map parseRoleMap() {
    def raw = env.AWS_ROLES ?: '{}'
    try {
        return readJSON(text: raw)
    } catch (Exception e) {
        echo '[awsAssumeRole] ⚠ AWS_ROLES non configurato, uso default'
        return [:]
    }
}

return this