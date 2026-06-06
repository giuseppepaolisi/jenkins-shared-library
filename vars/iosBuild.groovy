/**
 * iosBuild.groovy — Build, sign e upload app iOS enterprise.
 * Supporta: Xcodebuild archive/export, Fastlane match signing,
 * App Store Connect upload, TestFlight distribution.
 *
 * Utilizzo:
 *   def ipa = iosBuild.buildAndSign(
 *       matchGitUrl: 'git@github.com:azienda/fastlane-match.git',
 *       bundleIdentifier: 'com.azienda.app',
 *       appStoreKeyId: 'asc-api-key'
 *   )
 *
 *   iosBuild.uploadToAppStore(
 *       ipaPath: ipa,
 *       appStoreKeyId: 'asc-api-key',
 *       bundleId: 'com.azienda.app'
 *   )
 *
 *   iosBuild.distributeToTestFlight(
 *       ipaPath: ipa,
 *       testers: ['tester1@azienda.local']
 *   )
 *
 *   iosBuild.runTests(device: 'iPhone 14', osVersion: '16.4')
 *   iosBuild.bumpBuildNumber()
 *
 * Configurazione via env:
 *   IOS_WORKSPACE    = 'MyApp.xcworkspace'
 *   IOS_SCHEME       = 'MyApp'
 *   EXPORT_METHOD    = 'app-store'
 *   IOS_CONFIG       = 'Release'
 *   APPLE_TEAM_ID    = 'ABC123DEFG'
 */
import io.awesome.jenkins.IOSHelper

def buildAndSign(Map params = [:]) {
    def helper = createHelper()
    return helper.buildAndSign(params)
}

def uploadToAppStore(Map params = [:]) {
    def helper = createHelper()
    helper.uploadToAppStore(
        params.ipaPath ?: error('[iosBuild] ipaPath richiesto'),
        params.appStoreKeyId ?: error('[iosBuild] appStoreKeyId richiesto'),
        params.bundleId ?: error('[iosBuild] bundleId richiesto')
    )
}

def distributeToTestFlight(Map params = [:]) {
    def helper = createHelper()
    helper.distributeToTestFlight(
        params.ipaPath ?: error('[iosBuild] ipaPath richiesto'),
        params.testers ?: []
    )
}

def runTests(Map params = [:]) {
    def helper = createHelper()
    helper.runTests(
        params.device ?: 'iPhone 14',
        params.osVersion ?: 'latest'
    )
}

def bumpBuildNumber() {
    def helper = createHelper()
    helper.bumpBuildNumber()
}

@NonCPS
private IOSHelper createHelper() {
    def workspace = env.IOS_WORKSPACE ?: ''
    def scheme = env.IOS_SCHEME ?: ''
    def exportMethod = env.EXPORT_METHOD ?: 'app-store'
    def configuration = env.IOS_CONFIG ?: 'Release'

    if (!workspace) {
        // Cerca workspace automaticamente
        def wsFiles = findFiles(glob: '*.xcworkspace')
        workspace = wsFiles ? wsFiles[0].path : ''
    }
    if (!scheme) {
        // Cerca scheme nel workspace/project
        scheme = sh(script: "xcodebuild -list 2>/dev/null | grep -A1 'Schemes:' | tail -1 | xargs", returnStdout: true).trim()
    }

    return new IOSHelper(this, workspace, scheme, exportMethod, configuration)
}

return this