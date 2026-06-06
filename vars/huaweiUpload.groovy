/**
 * huaweiUpload.groovy — Upload app su Huawei AppGallery Connect enterprise.
 *
 * Utilizzo:
 *   huaweiUpload(
 *       filePath: 'build/app-release.apk',
 *       distributionPhase: 'Draft',   // o 'Production'
 *       releaseType: 1                // 1=full, 2=incremental
 *   )
 *
 *   def status = huaweiUpload.queryStatus()
 *   def versions = huaweiUpload.listVersions(1, 10)
 *
 * Configurazione via env:
 *   HUAWEI_CLIENT_ID     = 'xxxxx'
 *   HUAWEI_CLIENT_SECRET = 'xxxxx' (Jenkins credential ID)
 *   HUAWEI_APP_ID        = '123456789'
 */
import io.awesome.jenkins.HuaweiHelper

def call(Map params = [:]) {
    def helper = createHelper()
    helper.uploadApp(
        params.filePath ?: error('[huaweiUpload] filePath richiesto'),
        params.distributionPhase ?: 'Draft',
        params.releaseType ?: 1
    )
}

def queryStatus() {
    def helper = createHelper()
    return helper.queryStatus()
}

def listVersions(int page = 1, int pageSize = 10) {
    def helper = createHelper()
    return helper.listVersions(page, pageSize)
}

@NonCPS
private HuaweiHelper createHelper() {
    def clientId = env.HUAWEI_CLIENT_ID ?: error('[huaweiUpload] HUAWEI_CLIENT_ID non configurato')
    def clientSecret = env.HUAWEI_CLIENT_SECRET ?: error('[huaweiUpload] HUAWEI_CLIENT_SECRET non configurato')
    def appId = env.HUAWEI_APP_ID ?: error('[huaweiUpload] HUAWEI_APP_ID non configurato')
    return new HuaweiHelper(this, clientId, clientSecret, appId)
}

return this