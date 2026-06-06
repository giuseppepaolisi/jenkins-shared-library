/**
 * rawArtifact.groovy — Upload/download artefatti raw enterprise.
 * Supporta: Nexus (raw repository), S3, archiviazione Jenkins.
 *
 * Utilizzo:
 *   // Nexus
 *   def url = rawArtifact.uploadToNexus(
 *       path: 'build/app.apk',
 *       repository: 'raw-releases',
 *       groupId: 'com.azienda.app',
 *       artifactId: 'myapp',
 *       version: '1.0.0',
 *       extension: 'apk'
 *   )
 *
 *   rawArtifact.downloadFromNexus(
 *       savePath: '/tmp/app.apk',
 *       repository: 'raw-releases',
 *       groupId: 'com.azienda.app',
 *       artifactId: 'myapp',
 *       version: '1.0.0'
 *   )
 *
 *   // S3
 *   rawArtifact.uploadToS3(
 *       filePath: 'build/report.html',
 *       bucket: 'my-artifacts',
 *       key: 'reports/build-42/report.html',
 *       acl: 'bucket-owner-full-control'
 *   )
 *
 *   rawArtifact.downloadFromS3(
 *       bucket: 'my-artifacts',
 *       key: 'reports/build-42/report.html',
 *       localPath: './report.html'
 *   )
 *
 *   // Jenkins Archive
 *   rawArtifact.archive('build/**/*.jar')
 */
def uploadToNexus(Map params = [:]) {
    nexusOps.uploadArtifact(
        params.path ?: error('[rawArtifact] path richiesto'),
        params.repository ?: error('[rawArtifact] repository richiesto'),
        params.groupId ?: error('[rawArtifact] groupId richiesto'),
        params.artifactId ?: error('[rawArtifact] artifactId richiesto'),
        params.version ?: error('[rawArtifact] version richiesto'),
        params.extension ?: 'raw'
    )
}

def downloadFromNexus(Map params = [:]) {
    nexusOps.downloadArtifact(
        params.savePath ?: error('[rawArtifact] savePath richiesto'),
        params.repository ?: error('[rawArtifact] repository richiesto'),
        params.groupId ?: error('[rawArtifact] groupId richiesto'),
        params.artifactId ?: error('[rawArtifact] artifactId richiesto'),
        params.version ?: error('[rawArtifact] version richiesto'),
        params.extension ?: 'raw'
    )
}

def uploadToS3(Map params = [:]) {
    awsAssumeRole.s3Upload(
        params.bucket ?: error('[rawArtifact] bucket richiesto'),
        params.key ?: error('[rawArtifact] key richiesto'),
        params.filePath ?: error('[rawArtifact] filePath richiesto'),
        params
    )
}

def downloadFromS3(Map params = [:]) {
    awsAssumeRole.s3Download(
        params.bucket ?: error('[rawArtifact] bucket richiesto'),
        params.key ?: error('[rawArtifact] key richiesto'),
        params.localPath ?: error('[rawArtifact] localPath richiesto')
    )
}

def archive(String pattern) {
    archiveArtifacts(artifacts: pattern, allowEmpty: false, fingerprint: true)
    echo "[rawArtifact] ✓ Archiviati: ${pattern}"
}

/**
 * Upload su multiple destinazioni in parallelo.
 */
def uploadToMultipleDestinations(Map params = [:]) {
    def filePath = params.filePath ?: error('[rawArtifact] filePath richiesto')
    def destinations = params.destinations ?: ['jenkins']  // 'nexus', 's3', 'jenkins'

    def branches = [:]
    if ('nexus' in destinations) {
        branches['nexus'] = {
            uploadToNexus([path: filePath] + params.nexusParams)
        }
    }
    if ('s3' in destinations) {
        branches['s3'] = {
            uploadToS3([filePath: filePath] + params.s3Params)
        }
    }
    if ('jenkins' in destinations || destinations.empty) {
        branches['jenkins'] = {
            archive(filePath)
        }
    }

    pipelineUtils.parallelSafe(branches, false)
}

return this