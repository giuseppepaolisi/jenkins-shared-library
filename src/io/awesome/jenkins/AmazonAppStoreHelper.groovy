package io.awesome.jenkins

/**
 * Helper per upload app su Amazon AppStore enterprise.
 * Supporta: upload APK con Amazon AppStore API v2,
 * gestione versioni, query stato.
 */
class AmazonAppStoreHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String clientId
    private final String clientSecret
    private final String appId

    AmazonAppStoreHelper(Script script, String clientId, String clientSecret, String appId) {
        this.script = script
        this.clientId = clientId
        this.clientSecret = clientSecret
        this.appId = appId
    }

    /**
     * Ottiene access token OAuth 2.0 per Amazon API.
     */
    @NonCPS
    private String getAccessToken() {
        def tokenResult = script.sh(
            script: """
                curl -s -X POST \
                    "https://api.amazon.com/auth/O2/token" \
                    -H "Content-Type: application/x-www-form-urlencoded" \
                    -d "grant_type=client_credentials&client_id=${clientId}&client_secret=${clientSecret}&scope=appstore::apps:readwrite"
            """,
            returnStdout: true
        )

        def tokenJson = script.readJSON(text: tokenResult)
        if (!tokenJson.access_token) {
            script.error("[Amazon] Fallita autenticazione: ${tokenJson}")
        }
        return tokenJson.access_token
    }

    /**
     * Upload APK su Amazon AppStore.
     * @param filePath percorso APK locale
     * @param releaseNotes note di rilascio (max 3000 caratteri)
     * @param isProduction se true, distribuisce in produzione
     */
    def uploadApk(String filePath, String releaseNotes = '', boolean isProduction = false) {
        script.echo "[Amazon] ▶ Upload AppStore: ${filePath}"

        String accessToken = getAccessToken()

        // Step 1: Ottieni URL upload firmato
        def uploadUrlInfo = script.sh(
            script: """
                curl -s -X GET \
                    "https://developer.amazon.com/api/appstore/v2/applications/${appId}/uploads" \
                    -H "Authorization: Bearer ${accessToken}" \
                    -H "Content-Type: application/json"
            """,
            returnStdout: true
        )
        def urlJson = script.readJSON(text: uploadUrlInfo)

        if (!urlJson.uploadUrl) {
            script.error("[Amazon] Fallito ottenimento upload URL: ${urlJson}")
        }

        // Step 2: Upload APK su S3 (firmato)
        script.sh """
            curl -s -X PUT '${urlJson.uploadUrl}' \
                -H 'Content-Type: application/vnd.android.package-archive' \
                --upload-file '${filePath}' > /tmp/amazon-upload.json
        """

        // Step 3: Conferma upload
        def confirmResult = script.sh(
            script: """
                curl -s -X POST \
                    "https://developer.amazon.com/api/appstore/v2/applications/${appId}/uploads" \
                    -H "Authorization: Bearer ${accessToken}" \
                    -H "Content-Type: application/json" \
                    -d '{"uploadId":"${urlJson.uploadId}","numberOfParts":1}'
            """,
            returnStdout: true
        )
        def uploadResult = script.readJSON(text: confirmResult)

        if (uploadResult.status != 'COMPLETED') {
            script.error("[Amazon] Upload fallito: ${uploadResult}")
        }

        // Step 4: Crea release
        String submissionMode = isProduction ? 'PRODUCTION' : 'DRAFT'
        def releasePayload = [
            submissionMode: submissionMode,
            releaseNotes  : [[locale: 'it_IT', notes: releaseNotes], [locale: 'en_US', notes: releaseNotes]],
            apkIds        : [uploadResult.apkId]
        ]

        def releaseJson = script.writeJSON(json: releasePayload, returnText: true)
        script.sh """
            curl -s -X POST \
                "https://developer.amazon.com/api/appstore/v2/applications/${appId}/releases" \
                -H "Authorization: Bearer ${accessToken}" \
                -H "Content-Type: application/json" \
                -d '${releaseJson}' > /tmp/amazon-release.json
        """

        def releaseResult = script.readJSON(file: '/tmp/amazon-release.json')
        script.echo "[Amazon] ✓ Upload AppStore (${submissionMode}): ${filePath}"

        return releaseResult
    }

    /**
     * Query stato release.
     */
    @NonCPS
    def queryReleases() {
        String accessToken = getAccessToken()

        def result = script.sh(
            script: """
                curl -s -X GET \
                    "https://developer.amazon.com/api/appstore/v2/applications/${appId}/releases" \
                    -H "Authorization: Bearer ${accessToken}" \
                    -H "Content-Type: application/json"
            """,
            returnStdout: true
        )
        return script.readJSON(text: result)
    }

    /**
     * Ottiene dettagli applicazione.
     */
    @NonCPS
    def getAppDetails() {
        String accessToken = getAccessToken()

        def result = script.sh(
            script: """
                curl -s -X GET \
                    "https://developer.amazon.com/api/appstore/v2/applications/${appId}" \
                    -H "Authorization: Bearer ${accessToken}"
            """,
            returnStdout: true
        )
        return script.readJSON(text: result)
    }
}