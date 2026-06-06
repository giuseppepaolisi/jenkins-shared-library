package io.awesome.jenkins

/**
 * Helper per upload app su Huawei AppGallery Connect enterprise.
 * Supporta: upload APK/AAB, distribuzione a fase (Draft, Production),
 * query stato, e gestione versioni.
 */
class HuaweiHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String clientId
    private final String clientSecret
    private final String appId

    HuaweiHelper(Script script, String clientId, String clientSecret, String appId) {
        this.script = script
        this.clientId = clientId
        this.clientSecret = clientSecret
        this.appId = appId
    }

    /**
     * Ottiene access token OAuth 2.0 per API Huawei.
     */
    @NonCPS
    private String getAccessToken() {
        def tokenResult = script.sh(
            script: """
                curl -s -X POST \
                    "https://connect-api.cloud.huawei.com/api/oauth2/v1/token" \
                    -H "Content-Type: application/x-www-form-urlencoded" \
                    -d "grant_type=client_credentials&client_id=${clientId}&client_secret=${clientSecret}"
            """,
            returnStdout: true
        )
        def tokenJson = script.readJSON(text: tokenResult)
        if (!tokenJson.access_token) {
            script.error("[Huawei] Fallita autenticazione: ${tokenJson}")
        }
        return tokenJson.access_token
    }

    /**
     * Upload APK/AAB su Huawei AppGallery Connect.
     * @param filePath percorso APK/AAB locale
     * @param distributionPhase fase distribuzione: 'Draft' o 'Production'
     * @param releaseType tipo release: '1' (full), '2' (incremental)
     */
    def uploadApp(String filePath, String distributionPhase = 'Draft',
                   int releaseType = 1) {
        script.echo "[Huawei] ▶ Upload AppGallery: ${filePath}"

        String accessToken = getAccessToken()
        String fileName = filePath.split('/').last()

        // Step 1: Ottieni URL upload
        def uploadUrlInfo = script.sh(
            script: """
                curl -s -X POST \
                    "https://connect-api.cloud.huawei.com/api/publish/v2/app/${appId}/upload-url" \
                    -H "Authorization: Bearer ${accessToken}" \
                    -H "Content-Type: application/json" \
                    -d '{"suffix":"${fileName.endsWith('.aab') ? '.aab' : '.apk}'}'
            """,
            returnStdout: true
        )
        def uploadUrlJson = script.readJSON(text: uploadUrlInfo)

        if (!uploadUrlJson.uploadUrl) {
            script.error("[Huawei] Fallito ottenimento upload URL: ${uploadUrlJson}")
        }

        // Step 2: Upload file
        script.sh """
            curl -s -X POST '${uploadUrlJson.uploadUrl}' \
                -F 'authCode=${uploadUrlJson.authCode}' \
                -F 'fileCount=1' \
                -F 'file=@${filePath}' \
                -F 'parseType=1' \
                -F 'releaseType=${releaseType}' > /tmp/huawei-upload.json
        """

        // Step 3: Verifica upload
        def uploadResult = script.readJSON(file: '/tmp/huawei-upload.json')
        if (uploadResult.result?.resultCode != '0') {
            script.error("[Huawei] Upload fallito: ${uploadResult}")
        }

        // Step 4: Distribuisci
        def updateInfo = [
            fileInfoList: [[
                fileDestUrl: uploadResult.result?.uploadFileRsp?.fileInfoList[0]?.fileDestUrl
            ]],
            distributionPhase: distributionPhase
        ]

        def updateJson = script.writeJSON(json: updateInfo, returnText: true)
        script.sh """
            curl -s -X PUT \
                "https://connect-api.cloud.huawei.com/api/publish/v2/app/${appId}/update" \
                -H "Authorization: Bearer ${accessToken}" \
                -H "Content-Type: application/json" \
                -d '${updateJson}' > /tmp/huawei-update.json
        """

        def updateResult = script.readJSON(file: '/tmp/huawei-update.json')
        if (updateResult.result?.resultCode != '0') {
            script.error("[Huawei] Distribuzione fallita: ${updateResult}")
        }

        script.echo "[Huawei] ✓ Upload AppGallery (${distributionPhase}): ${filePath}"
    }

    /**
     * Query stato ultimo upload.
     */
    @NonCPS
    def queryStatus() {
        String accessToken = getAccessToken()

        def statusResult = script.sh(
            script: """
                curl -s -X GET \
                    "https://connect-api.cloud.huawei.com/api/publish/v2/app/${appId}/status" \
                    -H "Authorization: Bearer ${accessToken}"
            """,
            returnStdout: true
        )
        return script.readJSON(text: statusResult)
    }

    /**
     * Ottiene lista versioni caricate.
     */
    @NonCPS
    def listVersions(int page = 1, int pageSize = 10) {
        String accessToken = getAccessToken()

        def result = script.sh(
            script: """
                curl -s -X GET \
                    "https://connect-api.cloud.huawei.com/api/publish/v2/app/${appId}/versions?page=${page}&pageSize=${pageSize}" \
                    -H "Authorization: Bearer ${accessToken}"
            """,
            returnStdout: true
        )
        return script.readJSON(text: result)
    }
}