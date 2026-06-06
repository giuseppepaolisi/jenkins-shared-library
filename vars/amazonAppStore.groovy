/**
 * amazonAppStore.groovy — Upload app su Amazon AppStore enterprise.
 *
 * Utilizzo:
 *   amazonAppStore(
 *       filePath: 'build/app-release.apk',
 *       releaseNotes: 'Fix: crash login screen',
 *       isProduction: false             // true → PRODUCTION, false → DRAFT
 *   )
 *
 *   def releases = amazonAppStore.queryReleases()
 *   def details = amazonAppStore.getAppDetails()
 *
 * Configurazione via env:
 *   AMAZON_CLIENT_ID     = 'amzn1.application-oa2-client.xxxxx'
 *   AMAZON_CLIENT_SECRET = 'xxxxx' (Jenkins credential ID)
 *   AMAZON_APP_ID        = 'amzn1.android.app.xxxxx'
 */
import io.awesome.jenkins.AmazonAppStoreHelper

def call(Map params = [:]) {
    def helper = createHelper()
    helper.uploadApk(
        params.filePath ?: error('[amazonAppStore] filePath richiesto'),
        params.releaseNotes ?: '',
        params.isProduction ?: false
    )
}

def queryReleases() {
    def helper = createHelper()
    return helper.queryReleases()
}

def getAppDetails() {
    def helper = createHelper()
    return helper.getAppDetails()
}

@NonCPS
private AmazonAppStoreHelper createHelper() {
    def clientId = env.AMAZON_CLIENT_ID ?: error('[amazonAppStore] AMAZON_CLIENT_ID non configurato')
    def clientSecret = env.AMAZON_CLIENT_SECRET ?: error('[amazonAppStore] AMAZON_CLIENT_SECRET non configurato')
    def appId = env.AMAZON_APP_ID ?: error('[amazonAppStore] AMAZON_APP_ID non configurato')
    return new AmazonAppStoreHelper(this, clientId, clientSecret, appId)
}

return this