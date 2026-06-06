/**
 * nexusOps.groovy — Operazioni su Nexus Repository Manager 3 enterprise.
 *
 * Utilizzo:
 *   nexusOps.uploadArtifact('build/app.apk', 'raw-releases', 'com.azienda.app', 'myapp', '1.0.0', 'apk')
 *   nexusOps.downloadArtifact('/tmp/app.jar', 'maven-releases', 'com.azienda.lib', 'mylib', '1.0.0')
 *   nexusOps.promoteSnapshotToRelease('com.azienda.app', 'myapp', '1.0.0-SNAPSHOT', '1.0.0')
 *   def latest = nexusOps.getLatestVersion('maven-releases', 'com.azienda.lib', 'mylib')
 *
 * Configurazione via env:
 *   NEXUS_URL  = 'https://nexus.azienda.local'
 *   NEXUS_CREDS = 'nexus-jenkins-creds' (Jenkins credential ID)
 */
import io.awesome.jenkins.NexusHelper

def uploadArtifact(String path, String repository, String groupId,
                    String artifactId, String version, String extension = 'jar') {
    def helper = createHelper()
    return helper.uploadArtifact(path, repository, groupId, artifactId, version, extension)
}

def downloadArtifact(String savePath, String repository, String groupId,
                      String artifactId, String version, String extension = 'jar') {
    def helper = createHelper()
    return helper.downloadArtifact(savePath, repository, groupId, artifactId, version, extension)
}

def mavenDeploy(String pomPath, String jarPath, String repository) {
    def helper = createHelper()
    helper.mavenDeploy(pomPath, jarPath, repository)
}

def promoteSnapshotToRelease(String groupId, String artifactId,
                              String snapshotVersion, String releaseVersion) {
    def helper = createHelper()
    helper.promoteSnapshotToRelease(groupId, artifactId, snapshotVersion, releaseVersion)
}

def getLatestVersion(String repository, String groupId, String artifactId) {
    def helper = createHelper()
    return helper.getLatestVersion(repository, groupId, artifactId)
}

@NonCPS
private NexusHelper createHelper() {
    def url = env.NEXUS_URL ?: error('[nexusOps] NEXUS_URL non configurato')
    def creds = env.NEXUS_CREDS ?: 'nexus-jenkins-creds'
    return new NexusHelper(this, url, creds)
}

return this