package io.awesome.jenkins

/**
 * Helper per build, sign e upload di app iOS enterprise.
 * Supporta: Xcodebuild, Fastlane match per signing, App Store Connect upload,
 * TestFlight distribution, archive/export IPA.
 */
class IOSHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String workspace
    private final String scheme
    private final String exportMethod // 'app-store', 'ad-hoc', 'enterprise', 'development'
    private final String configuration

    IOSHelper(Script script, String workspace = '', String scheme = '',
              String exportMethod = 'app-store', String configuration = 'Release') {
        this.script = script
        this.workspace = workspace
        this.scheme = scheme
        this.exportMethod = exportMethod
        this.configuration = configuration
    }

    /**
     * Build, archive e export IPA con supporto Fastlane match per signing.
     * @param matchGitUrl URL del repo Git per Fastlane match (opzionale)
     * @param bundleIdentifier bundle identifier dell'app
     * @param appStoreKeyId Jenkins credentials ID per App Store Connect API Key
     */
    def buildAndSign(Map params = [:]) {
        String matchUrl = params.matchGitUrl ?: ''
        String bundleId = params.bundleIdentifier ?: ''
        String appStoreKeyId = params.appStoreKeyId ?: ''
        String provisioningProfileId = params.provisioningProfileId ?: ''

        script.echo "[iOS] ▶ Build iOS: scheme=${scheme}, exportMethod=${exportMethod}"

        // Setup Fastlane match per signing
        if (matchUrl) {
            setupFastlaneMatch(matchUrl, bundleId)
        } else if (provisioningProfileId) {
            setupProvisioningProfile(provisioningProfileId)
        }

        // Clean
        script.sh "xcodebuild clean -workspace '${workspace}' -scheme '${scheme}' -configuration ${configuration} -quiet 2>/dev/null || true"

        // Archive
        def archivePath = "${script.env.WORKSPACE}/build/${scheme}.xcarchive"
        script.sh """
            xcodebuild archive \
                -workspace '${workspace}' \
                -scheme '${scheme}' \
                -configuration ${configuration} \
                -archivePath '${archivePath}' \
                -allowProvisioningUpdates \
                -quiet 2>&1 | tee xcodebuild-archive.log
        """

        // Export IPA
        def exportOptionsPlist = generateExportOptions(bundleId)
        def ipaPath = "${script.env.WORKSPACE}/build/${scheme}.ipa"

        script.writeFile(file: 'ExportOptions.plist', text: exportOptionsPlist)
        script.sh """
            xcodebuild -exportArchive \
                -archivePath '${archivePath}' \
                -exportOptionsPlist 'ExportOptions.plist' \
                -exportPath '${script.env.WORKSPACE}/build/' \
                -allowProvisioningUpdates \
                -quiet 2>&1 | tee xcodebuild-export.log
        """

        // Archivia artefatti
        if (script.fileExists(ipaPath)) {
            script.archiveArtifacts(artifacts: ipaPath, fingerprint: true)
            script.archiveArtifacts(artifacts: 'xcodebuild-archive.log', allowEmpty: true)
        }

        script.echo "[iOS] ✓ Build completata: ${ipaPath}"
        return ipaPath
    }

    /**
     * Upload su App Store Connect tramite XCTest/Transporter o altool.
     * @param ipaPath percorso IPA
     * @param appStoreKeyId Jenkins credentials ID per App Store Connect API Key
     * @param bundleId bundle identifier
     */
    def uploadToAppStore(String ipaPath, String appStoreKeyId, String bundleId) {
        script.echo "[iOS] ▶ Upload App Store Connect: ${ipaPath}"

        if (appStoreKeyId) {
            // Usa App Store Connect API Key (JWT-based, più sicuro)
            script.withCredentials([
                script.string(credentialsId: "${appStoreKeyId}-key", variable: 'ASC_KEY'),
                script.string(credentialsId: "${appStoreKeyId}-key-id", variable: 'ASC_KEY_ID'),
                script.string(credentialsId: "${appStoreKeyId}-issuer", variable: 'ASC_ISSUER_ID')
            ]) {
                // Usa altool con API Key
                script.sh """
                    xcrun altool --upload-app \
                        -f '${ipaPath}' \
                        --apiKey '${script.env.ASC_KEY_ID}' \
                        --apiIssuer '${script.env.ASC_ISSUER_ID}' \
                        --verbose 2>&1 | tee altool-upload.log
                """
            }
        } else {
            // Fallback: Apple ID tradizionale (username/password)
            script.withCredentials([
                script.usernamePassword(credentialsId: 'apple-id',
                    usernameVariable: 'APPLE_ID',
                    passwordVariable: 'APPLE_PASS')
            ]) {
                script.sh """
                    xcrun altool --upload-app \
                        -f '${ipaPath}' \
                        -u '${script.env.APPLE_ID}' \
                        -p '${script.env.APPLE_PASS}' \
                        --verbose 2>&1 | tee altool-upload.log
                """
            }
        }

        script.echo "[iOS] ✓ Upload App Store Connect completato"
    }

    /**
     * Distribuisci a TestFlight.
     */
    def distributeToTestFlight(String ipaPath, List<String> testers = []) {
        // TestFlight distribuzione via Fastlane
        script.sh """
            fastlane pilot upload \
                -i '${ipaPath}' \
                -q ${testers.join(',')} \
                --skip_waiting_for_build_processing false
        """
        script.echo "[iOS] ✓ TestFlight distribuzione: ${testers.size()} tester"
    }

    /**
     * Incrementa build number in Xcode project.
     */
    @NonCPS
    def bumpBuildNumber() {
        def plistPath = findInfoPlist()
        def currentBuild = script.sh(
            script: "/usr/libexec/PlistBuddy -c 'Print CFBundleVersion' '${plistPath}'",
            returnStdout: true
        ).trim()
        def newBuild = (currentBuild as int) + 1

        script.sh "/usr/libexec/PlistBuddy -c 'Set CFBundleVersion ${newBuild}' '${plistPath}'"
        script.echo "[iOS] ✓ Build number: ${currentBuild} → ${newBuild}"
    }

    /**
     * Esegui test iOS (unit test + UI test).
     */
    def runTests(String device = 'iPhone 14', String osVersion = 'latest') {
        script.sh """
            xcodebuild test \
                -workspace '${workspace}' \
                -scheme '${scheme}' \
                -destination 'platform=iOS Simulator,name=${device},OS=${osVersion}' \
                -configuration Debug \
                -quiet 2>&1 | tee xcodebuild-test.log
        """

        script.junit(testResults: '**/*.xcodeproj/**/test-results/**/*.xml')
        script.echo "[iOS] ✓ Test completati"
    }

    // --- Metodi privati ---

    @NonCPS
    private void setupFastlaneMatch(String matchUrl, String bundleId) {
        script.withCredentials([
            script.string(credentialsId: 'fastlane-match-pass', variable: 'MATCH_PASSWORD')
        ]) {
            // Configura Fastlane Match
            script.sh """
                fastlane match ${exportMethod} \
                    --git_url '${matchUrl}' \
                    --app_identifier '${bundleId}' \
                    --readonly false \
                    --force \
                    --verbose
            """
        }
    }

    @NonCPS
    private void setupProvisioningProfile(String profileId) {
        // Scarica e installa provisioning profile
        script.withCredentials([script.file(credentialsId: profileId, variable: 'PROV_PROFILE')]) {
            script.sh """
                mkdir -p ~/Library/MobileDevice/Provisioning\\ Profiles/
                cp '${script.env.PROV_PROFILE}' ~/Library/MobileDevice/Provisioning\\ Profiles/
            """
        }
    }

    @NonCPS
    private String generateExportOptions(String bundleId) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>${exportMethod}</string>
    <key>teamID</key>
    <string>${script.env.APPLE_TEAM_ID ?: ''}</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>uploadSymbols</key>
    <true/>
    <key>compileBitcode</key>
    <false/>
    <key>destination</key>
    <string>export</string>
    <key>provisioningProfiles</key>
    <dict>
        <key>${bundleId}</key>
        <string>$(cat ~/Library/MobileDevice/Provisioning\\ Profiles/*.mobileprovision 2>/dev/null | grep -o '<key>UUID</key><string>[^<]*' | head -1 | sed 's/.*<string>//')</string>
    </dict>
</dict>
</plist>
"""
    }

    @NonCPS
    private String findInfoPlist() {
        def findResult = script.findFiles(glob: '**/Info.plist')
        if (!findResult) {
            script.error("[iOS] Info.plist non trovato")
        }
        return findResult[0].path
    }
}