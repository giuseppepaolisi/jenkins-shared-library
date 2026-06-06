package io.awesome.jenkins

/**
 * Helper per operazioni AWS multi-account enterprise.
 * Gestisce assume-role cross-account con STS, caching sessioni, audit logging,
 * e policy di sicurezza per ambiente.
 */
class AWSHelper implements Serializable {
    private static final long serialVersionUID = 1L
    private static final int STS_DURATION_SECONDS = 3600 // 1 ora
    private static final int S3_UPLOAD_RETRIES = 3
    private static final int S3_UPLOAD_TIMEOUT_MINUTES = 30

    private final Script script
    private final Map<String, String> accountMap     // [env: accountId]
    private final Map<String, String> roleMap        // [env: roleArn]
    private final String defaultRegion

    // Cache delle sessioni STS
    @NonCPS
    private final Map<String, String> sessionCache = [:]

    AWSHelper(Script script, Map<String, String> accounts, Map<String, String> roles, String region = 'eu-west-1') {
        this.script = script
        this.accountMap = accounts ?: [:]
        this.roleMap = roles ?: [:]
        this.defaultRegion = region
    }

    /**
     * Assume un ruolo IAM in un account target con sicurezza e audit.
     * Utilizza caching delle sessioni per evitare assume-role ripetuti.
     * @param env ambiente target (dev, staging, prod)
     * @param region regione AWS (opzionale, default: defaultRegion)
     * @return sessionId per riferimento futuro
     */
    String assumeRole(String env, String region = defaultRegion) {
        validateEnvironment(env)
        validateBranchRestrictions(env)

        String cacheKey = "${env}:${region}"
        if (sessionCache[cacheKey]) {
            script.echo "[AWS] Usata sessione cache per ${env} (${region})"
            return sessionCache[cacheKey]
        }

        String accountId = accountMap[env]
        String roleArn = roleMap[env] ?: "arn:aws:iam::${accountId}:role/JenkinsCrossAccountRole"
        String sessionName = "jenkins-${script.env.JOB_NAME ?: 'unknown'}-${script.env.BUILD_NUMBER ?: '0'}"
        sessionName = sessionName.replaceAll(/[^a-zA-Z0-9_-]/, '-').take(64)

        script.echo "[AWS] Assumendo ruolo ${roleArn} per ambiente ${env}..."

        // Utilizzo AWS Steps Plugin per assume-role sicuro
        script.withAWS(credentials: 'aws-global', region: region) {
            def stsResult = script.sh(
                script: """
                    aws sts assume-role \
                        --role-arn "${roleArn}" \
                        --role-session-name "${sessionName}" \
                        --duration-seconds ${STS_DURATION_SECONDS} \
                        --region ${region} \
                        --output json
                """,
                returnStdout: true
            )

            // Esporto le credenziali temporanee per i comandi successivi
            def creds = script.readJSON(text: stsResult).Credentials
            script.env.AWS_ACCESS_KEY_ID = creds.AccessKeyId
            script.env.AWS_SECRET_ACCESS_KEY = creds.SecretAccessKey
            script.env.AWS_SESSION_TOKEN = creds.SessionToken
            script.env.AWS_DEFAULT_REGION = region

            sessionCache[cacheKey] = "${sessionName}@${accountId}"
            script.echo "[AWS] ✓ Sessione STS attiva: ${sessionName} (${STS_DURATION_SECONDS}s, account ${accountId})"
        }

        return sessionCache[cacheKey]
    }

    /**
     * Esegue una closure con le credenziali di un ambiente specifico.
     */
    def withAwsEnv(String env, String region = defaultRegion, Closure closure) {
        assumeRole(env, region)
        script.withAWS(region: region) {
            return closure.call()
        }
    }

    /**
     * Esegue una closure con le credenziali Jenkins AWS direttamente (senza assume-role).
     */
    def withDefaultCredentials(String region = defaultRegion, Closure closure) {
        script.withAWS(credentials: 'aws-global', region: region) {
            return closure.call()
        }
    }

    /**
     * Upload sicuro su S3 con retry e SSE-S3 crittografia.
     */
    def s3Upload(String bucket, String key, String filePath, Map options = [:]) {
        validateNotEmpty(bucket, 'bucket')
        validateNotEmpty(key, 'key')

        String acl = options.acl ?: 'bucket-owner-full-control'
        String storageClass = options.storageClass ?: 'STANDARD'
        boolean encrypt = options.encrypt != false

        script.retry(S3_UPLOAD_RETRIES) {
            script.timeout(time: S3_UPLOAD_TIMEOUT_MINUTES, unit: 'MINUTES') {
                def sseOption = encrypt ? '--sse AES256' : ''
                script.sh """
                    aws s3 cp '${filePath}' 's3://${bucket}/${key}' \
                        --acl ${acl} \
                        --storage-class ${storageClass} \
                        ${sseOption} \
                        --only-show-errors
                """
                script.echo "[S3] ✓ Upload: s3://${bucket}/${key}"
            }
        }
    }

    /**
     * Download sicuro da S3 con retry.
     */
    def s3Download(String bucket, String key, String localPath) {
        validateNotEmpty(bucket, 'bucket')
        validateNotEmpty(key, 'key')

        script.retry(S3_UPLOAD_RETRIES) {
            script.sh "aws s3 cp 's3://${bucket}/${key}' '${localPath}' --only-show-errors"
            script.echo "[S3] ✓ Download: s3://${bucket}/${key} → ${localPath}"
        }
    }

    /**
     * Esegue login su Amazon ECR e restituisce l'URI del registry.
     */
    String ecrLogin(String region = defaultRegion, String accountId = '') {
        if (!accountId) {
            accountId = script.sh(
                script: 'aws sts get-caller-identity --query Account --output text',
                returnStdout: true
            ).trim()
        }

        def registryUri = "${accountId}.dkr.ecr.${region}.amazonaws.com"

        script.sh """
            aws ecr get-login-password --region ${region} | \
                docker login --username AWS --password-stdin ${registryUri}
        """
        script.echo "[ECR] ✓ Login: ${registryUri}"
        return registryUri
    }

    /**
     * Verifica che il branch corrente sia autorizzato per l'ambiente target.
     * Regole: prod solo da main/release, staging da develop/feature, dev da qualsiasi.
     */
    @NonCPS
    private void validateBranchRestrictions(String env) {
        String branch = script.env.BRANCH_NAME ?: 'unknown'
        boolean isPR = script.env.CHANGE_ID != null

        def allowedPatterns
        switch (env) {
            case 'prod':
            case 'production':
                allowedPatterns = [/^main$/, /^release\//, /^hotfix\//]
                if (isPR) {
                    script.error("[AWS] ❌ PROD: non consentito su Pull Request (branch: ${branch})")
                }
                break
            case 'staging':
                allowedPatterns = [/^main$/, /^develop$/, /^release\//, /^feature\//]
                break
            default: // dev, test, qualsiasi altro
                return // nessuna restrizione
        }

        boolean allowed = allowedPatterns.any { pattern -> branch =~ pattern }
        if (!allowed) {
            script.error("[AWS] ❌ Branch '${branch}' non autorizzato per ambiente '${env}'. Pattern: ${allowedPatterns}")
        }
        script.echo "[AWS] ✓ Branch '${branch}' autorizzato per '${env}'"
    }

    @NonCPS
    private void validateEnvironment(String env) {
        if (!accountMap[env]) {
            script.error("[AWS] Ambiente sconosciuto: ${env}. Configurati: ${accountMap.keySet()}")
        }
    }

    @NonCPS
    private void validateNotEmpty(String value, String name) {
        if (!value || value.trim().isEmpty()) {
            script.error("[AWS] Parametro obbligatorio vuoto: ${name}")
        }
    }
}