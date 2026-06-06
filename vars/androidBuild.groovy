/**
 * androidBuild.groovy — Build, sign e upload app Android enterprise.
 * Supporta: Gradle build con flavor, keystore signing con apksigner,
 * Google Play Console upload (internal/alpha/beta/production),
 * Huawei AppGallery, Amazon AppStore.
 *
 * Utilizzo:
 *   def apks = androidBuild.buildAndSign(
 *       flavor: 'production',
 *       buildType: 'release',
 *       keystoreId: 'android-keystore',
 *       bundle: true,
 *       extraTasks: ['lint', 'test']
 *   )
 *
 *   androidBuild.uploadToGooglePlay(
 *       artifactPath: apks[0],
 *       serviceAccountId: 'google-play-svc-account',
 *       applicationId: 'com.azienda.app',
 *       track: 'internal'
 *   )
 *
 *   androidBuild.runLint()
 *   androidBuild.runTests()
 *   androidBuild.bumpVersionCode()
 *
 * Configurazione via env:
 *   GRADLEW_PATH  = './gradlew'
 *   ANDROID_HOME  = '/opt/android-sdk'
 */
import io.awesome.jenkins.AndroidHelper

def buildAndSign(Map params = [:]) {
    def helper = createHelper()
    def flavor = params.flavor ?: 'production'
    def buildType = params.buildType ?: 'release'
    def keystoreId = params.keystoreId ?: ''
    def extraTasks = params.extraTasks ?: []

    return helper.buildAndSign(flavor, buildType, keystoreId, extraTasks, params)
}

def uploadToGooglePlay(Map params = [:]) {
    def helper = createHelper()
    helper.uploadToGooglePlay(
        params.artifactPath ?: error('[androidBuild] artifactPath richiesto'),
        params.serviceAccountId ?: error('[androidBuild] serviceAccountId richiesto'),
        params.applicationId ?: error('[androidBuild] applicationId richiesto'),
        params.track ?: 'internal'
    )
}

def runLint() {
    def helper = createHelper()
    helper.runLint()
}

def runTests() {
    def helper = createHelper()
    helper.runTests()
}

def bumpVersionCode() {
    def helper = createHelper()
    helper.bumpVersionCode()
}

@NonCPS
private AndroidHelper createHelper() {
    def gradlew = env.GRADLEW_PATH ?: './gradlew'
    def androidHome = env.ANDROID_HOME ?: '/opt/android-sdk'
    return new AndroidHelper(this, gradlew, androidHome)
}

return this