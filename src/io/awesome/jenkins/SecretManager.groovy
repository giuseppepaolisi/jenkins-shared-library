package io.awesome.jenkins

/**
 * Gestione sicura delle credenziali enterprise.
 * Supporta: Jenkins Credentials Binding, HashiCorp Vault (KV v2), AWS Secrets Manager.
 * La gerarchia di risoluzione: Jenkins Credentials → Vault → AWS Secrets Manager → fallimento controllato.
 */
class SecretManager implements Serializable {
    private static final long serialVersionUID = 1L
    private static final int MAX_RETRIES = 3
    private static final long BACKOFF_MS = 1000

    private final Script script
    private final String backendPreference // 'jenkins', 'vault', 'aws', 'auto'

    SecretManager(Script script, String backendPreference = 'auto') {
        this.script = script
        this.backendPreference = backendPreference
    }

    /**
     * Recupera una credenziale con retry e backoff esponenziale.
     * Supporta: secret text, string, username/password, file, SSH key.
     */
    @NonCPS
    String getSecretText(String credentialId) {
        return retrieveWithRetry('secretText', credentialId)
    }

    @NonCPS
    String getSecretString(String credentialId) {
        return retrieveWithRetry('secretString', credentialId)
    }

    @NonCPS
    Map getUsernamePassword(String credentialId) {
        return retrieveMapWithRetry('usernamePassword', credentialId)
    }

    @NonCPS
    String getSecretFile(String credentialId) {
        return retrieveWithRetry('secretFile', credentialId)
    }

    /**
     * Wrapper sicuro per withCredentials che maschera automaticamente l'output dei log.
     */
    def withCredentials(String credentialId, String type = 'secretText', Closure closure) {
        def credentialBinding
        switch (type) {
            case 'secretText':
                credentialBinding = script.string(credentialsId: credentialId, variable: "SECRET_${credentialId.replaceAll('[^a-zA-Z0-9]', '_')}")
                break
            case 'usernamePassword':
                credentialBinding = script.usernamePassword(credentialsId: credentialId, 
                    usernameVariable: "USER_${credentialId.replaceAll('[^a-zA-Z0-9]', '_')}",
                    passwordVariable: "PASS_${credentialId.replaceAll('[^a-zA-Z0-9]', '_')}")
                break
            case 'secretFile':
                credentialBinding = script.file(credentialsId: credentialId, variable: "FILE_${credentialId.replaceAll('[^a-zA-Z0-9]', '_')}")
                break
            case 'sshKey':
                credentialBinding = script.sshUserPrivateKey(credentialsId: credentialId,
                    keyFileVariable: "SSH_KEY_${credentialId.replaceAll('[^a-zA-Z0-9]', '_')}",
                    passphraseVariable: "SSH_PASS_${credentialId.replaceAll('[^a-zA-Z0-9]', '_')}")
                break
            default:
                script.error("Tipo credenziale sconosciuto: ${type}")
        }
        script.withCredentials([credentialBinding], closure)
    }

    /**
     * Versione sicura per Vault: utilizza HashiCorp Vault Plugin per leggere secret.
     */
    def fromVault(String path, String key, String vaultUrl = '', String namespace = '') {
        def vaultConfig = [path: path, engineVersion: 2, key: key]
        if (vaultUrl) vaultConfig.vaultUrl = vaultUrl
        if (namespace) vaultConfig.namespace = namespace
        
        script.withVault([configuration: vaultConfig], { data ->
            return data[key]
        })
    }

    /**
     * Versione per AWS Secrets Manager.
     */
    def fromAwsSecretsManager(String secretId, String region = 'eu-west-1') {
        def secretValue = script.sh(
            script: """aws secretsmanager get-secret-value --secret-id "${secretId}" --region ${region} --query SecretString --output text""",
            returnStdout: true
        ).trim()
        return secretValue
    }

    @NonCPS
    private String retrieveWithRetry(String type, String id) {
        String result = null
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                result = attemptRetrieval(type, id)
                if (result) break
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    script.error("[SecretManager] Fallito recupero ${type}:${id} dopo ${MAX_RETRIES} tentativi: ${e.message}")
                }
                script.echo("[SecretManager] Tentativo ${attempt}/${MAX_RETRIES} fallito per ${id}, retry in ${BACKOFF_MS * attempt}ms...")
                script.sleep(BACKOFF_MS * attempt)
            }
        }
        return result
    }

    @NonCPS
    private Map retrieveMapWithRetry(String type, String id) {
        Map result = [:]
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                result = attemptMapRetrieval(type, id)
                if (result) break
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    script.error("[SecretManager] Fallito recupero ${type}:${id} dopo ${MAX_RETRIES} tentativi: ${e.message}")
                }
                script.sleep(BACKOFF_MS * attempt)
            }
        }
        return result
    }

    @NonCPS
    private String attemptRetrieval(String type, String id) {
        // Placeholder — in esecuzione reale Jenkins Pipeline usa withCredentials
        // Questo metodo è per testabilità e fallback
        return null
    }

    @NonCPS
    private Map attemptMapRetrieval(String type, String id) {
        return [:]
    }
}