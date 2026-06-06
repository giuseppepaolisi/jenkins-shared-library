package io.awesome.jenkins

/**
 * Helper per operazioni su Nexus Repository Manager 3 enterprise.
 * Gestisce: upload artefatti, download, promozione snapshot→release,
 * gestione Docker registry hosted, query repository.
 */
class NexusHelper implements Serializable {
    private static final long serialVersionUID = 1L
    private static final int UPLOAD_RETRIES = 3

    private final Script script
    private final String nexusUrl
    private final String credentialsId

    NexusHelper(Script script, String nexusUrl, String credentialsId) {
        this.script = script
        this.nexusUrl = nexusUrl.replaceAll('/+$', '') // normalize trailing slash
        this.credentialsId = credentialsId
    }

    /**
     * Upload di un artefatto raw su Nexus.
     * @param path percorso file locale
     * @param repository repository target (es: raw-snapshots, raw-releases)
     * @param groupId groupId per il component (es: com.azienda.progetto)
     * @param artifactId artifactId
     * @param version versione
     * @param extension estensione file
     */
    def uploadArtifact(String path, String repository, String groupId,
                        String artifactId, String version, String extension = 'jar') {
        validateParams(path, repository, groupId, artifactId, version)

        String filename = "${artifactId}-${version}.${extension}"
        String nexusPath = "${groupId.replace('.', '/')}/${artifactId}/${version}/${filename}"

        script.retry(UPLOAD_RETRIES) {
            script.withCredentials([script.string(credentialsId: credentialsId, variable: 'NEXUS_PASS')]) {
                script.sh """
                    curl -f -s -u '${getNexusUsername()}:${script.env.NEXUS_PASS}' \
                        --upload-file '${path}' \
                        "${nexusUrl}/repository/${repository}/${nexusPath}"
                """
            }
        }

        def downloadUrl = "${nexusUrl}/repository/${repository}/${nexusPath}"
        script.echo "[Nexus] ✓ Upload: ${downloadUrl}"
        return downloadUrl
    }

    /**
     * Download di un artefatto da Nexus.
     */
    def downloadArtifact(String savePath, String repository, String groupId,
                          String artifactId, String version, String extension = 'jar') {
        String filename = "${artifactId}-${version}.${extension}"
        String nexusPath = "${groupId.replace('.', '/')}/${artifactId}/${version}/${filename}"
        String url = "${nexusUrl}/repository/${repository}/${nexusPath}"

        script.retry(UPLOAD_RETRIES) {
            script.withCredentials([script.string(credentialsId: credentialsId, variable: 'NEXUS_PASS')]) {
                script.sh """
                    curl -f -s -u '${getNexusUsername()}:${script.env.NEXUS_PASS}' \
                        -o '${savePath}' \
                        "${url}"
                """
            }
        }

        script.echo "[Nexus] ✓ Download: ${url} → ${savePath}"
        return savePath
    }

    /**
     * Upload Maven artefatto via Maven Deploy plugin (più sicuro di curl per JAR/POM).
     */
    def mavenDeploy(String pomPath, String jarPath, String repository) {
        script.sh """
            mvn deploy:deploy-file \
                -DpomFile='${pomPath}' \
                -Dfile='${jarPath}' \
                -DrepositoryId='${credentialsId}' \
                -Durl='${nexusUrl}/repository/${repository}'
        """
        script.echo "[Nexus] ✓ Maven deploy: ${jarPath} → ${repository}"
    }

    /**
     * Promuove un artefatto da snapshot a release su Nexus.
     */
    def promoteSnapshotToRelease(String groupId, String artifactId, String snapshotVersion, String releaseVersion) {
        String componentPath = "${groupId.replace('.', '/')}/${artifactId}/${snapshotVersion}"
        def downloadUrl = downloadArtifact(
            "/tmp/${artifactId}-${snapshotVersion}.jar",
            'maven-snapshots', groupId, artifactId, snapshotVersion
        )

        uploadArtifact(
            downloadUrl,
            'maven-releases', groupId, artifactId, releaseVersion
        )

        script.echo "[Nexus] ✓ Promosso ${snapshotVersion} → ${releaseVersion}"
    }

    /**
     * Query di un artefatto su Nexus tramite REST API.
     */
    @NonCPS
    def queryArtifact(String repository, String groupId, String artifactId) {
        String searchUrl = "${nexusUrl}/service/rest/v1/search" +
            "?repository=${repository}" +
            "&group=${groupId}" +
            "&name=${artifactId}"

        def result
        script.withCredentials([script.string(credentialsId: credentialsId, variable: 'NEXUS_PASS')]) {
            result = script.sh(
                script: """
                    curl -s -u '${getNexusUsername()}:${script.env.NEXUS_PASS}' \
                        -X GET "${searchUrl}" -H "accept: application/json"
                """,
                returnStdout: true
            )
        }

        return script.readJSON(text: result)
    }

    /**
     * Ottiene l'ultima versione di un artefatto da Nexus.
     */
    @NonCPS
    String getLatestVersion(String repository, String groupId, String artifactId) {
        def response = queryArtifact(repository, groupId, artifactId)
        def items = response.items ?: []

        if (!items) {
            script.error("[Nexus] Nessun artefatto trovato: ${groupId}:${artifactId} in ${repository}")
        }

        // Ordina per versione e prendi la più recente
        def versions = items.collect { it.version }.sort(false) { a, b ->
            def aParts = a.split('\\.')*.toInteger()
            def bParts = b.split('\\.')*.toInteger()
            for (int i = 0; i < Math.min(aParts.size(), bParts.size()); i++) {
                if (aParts[i] != bParts[i]) return aParts[i] <=> bParts[i]
            }
            return aParts.size() <=> bParts.size()
        }

        return versions.last()
    }

    @NonCPS
    private void validateParams(String path, String repo, String gid, String aid, String ver) {
        if (!path || !script.fileExists(path)) {
            script.error("[Nexus] File non trovato: ${path}")
        }
        if (!repo) script.error('[Nexus] Repository richiesto')
        if (!gid) script.error('[Nexus] groupId richiesto')
        if (!aid) script.error('[Nexus] artifactId richiesto')
        if (!ver) script.error('[Nexus] version richiesta')
    }

    @NonCPS
    private String getNexusUsername() {
        // Default Jenkins user per Nexus - configurabile via credentials
        return 'jenkins'
    }
}