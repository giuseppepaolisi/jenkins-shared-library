package io.awesome.jenkins

/**
 * Helper per build, sign e upload di app Android enterprise.
 * Supporta: Gradle build con flavor, keystore signing, Google Play Console upload,
 * e integrazione con vari store (Huawei, Amazon).
 */
class AndroidHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String gradleWrapper
    private final String androidHome

    AndroidHelper(Script script, String gradleWrapper = './gradlew', String androidHome = '') {
        this.script = script
        this.gradleWrapper = gradleWrapper
        this.androidHome = androidHome ?: script.env.ANDROID_HOME ?: '/opt/android-sdk'
    }

    /**
     * Build e sign dell'APK/AAB con flavor e buildType.
     * @param flavor product flavor (es: 'production', 'staging')
     * @param buildType build type (es: 'release', 'debug')
     * @param keystoreId Jenkins credentials ID per keystore
     * @param extraTasks task aggiuntivi Gradle (es: 'lint', 'test')
     */
    def buildAndSign(String flavor, String buildType, String keystoreId,
                     List<String> extraTasks = [], Map options = [:]) {
        script.echo "[Android] ▶ Build: flavor=${flavor}, buildType=${buildType}"

        // Imposta variabili ambiente Android
        script.env.ANDROID_HOME = androidHome
        script.env.ANDROID_SDK_ROOT = androidHome

        // Rendi il wrapper eseguibile
        script.sh "chmod +x ${gradleWrapper}"

        // Compila task Gradle
        def taskList = ["assemble${flavor.capitalize()}${buildType.capitalize()}"]
        if (options.bundle) {
            taskList.add("bundle${flavor.capitalize()}${buildType.capitalize()}")
        }
        taskList.addAll(extraTasks)

        // Esegui Gradle build
        script.sh "${gradleWrapper} ${taskList.join(' ')} --no-daemon --parallel --stacktrace"

        // Trova APK/AAB generato
        def artifactDir = "app/build/outputs"
        def apkPattern = options.bundle ? "bundle/${flavor}${buildType.capitalize()}/*.aab" : "apk/${flavor}${buildType.capitalize()}/*.apk"
        def artifacts = script.findFiles(glob: "${artifactDir}/${apkPattern}")

        if (!artifacts) {
            script.error("[Android] Nessun artefatto trovato per ${flavor}/${buildType}")
        }

        // Sign con apksigner
        if (keystoreId && buildType == 'release') {
            signWithKeystore(artifacts, keystoreId, options.keyAlias ?: '')
        }

        // Archivia artefatti
        artifacts.each { artifact ->
            script.archiveArtifacts(artifacts: artifact.path, fingerprint: true)
        }

        script.echo "[Android] ✓ Build completata: ${artifacts*.path.join(', ')}"
        return artifacts*.path
    }

    /**
     * Sign APK/AAB con keystore da Jenkins Credentials.
     */
    def signWithKeystore(List<String> artifacts, String keystoreId, String keyAlias = '') {
        script.withCredentials([
            script.file(credentialsId: keystoreId, variable: 'KEYSTORE_FILE'),
            script.string(credentialsId: "${keystoreId}-password", variable: 'KEYSTORE_PASS'),
            script.string(credentialsId: "${keystoreId}-keypass", variable: 'KEY_PASS')
        ]) {
            artifacts.each { artifact ->
                def isAab = artifact.endsWith('.aab')
                if (isAab) {
                    // jarsigner + zipalign per AAB
                    script.sh """
                        jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
                            -keystore '${script.env.KEYSTORE_FILE}' \
                            -storepass '${script.env.KEYSTORE_PASS}' \
                            -keypass '${script.env.KEY_PASS}' \
                            '${artifact}' \
                            '${keyAlias ?: 'android'}'
                    """
                } else {
                    // apksigner per APK
                    script.sh """
                        ${androidHome}/build-tools/*/apksigner sign \
                            --ks '${script.env.KEYSTORE_FILE}' \
                            --ks-pass env:KEYSTORE_PASS \
                            --key-pass env:KEY_PASS \
                            ${keyAlias ? "--ks-key-alias ${keyAlias}" : ''} \
                            '${artifact}'
                    """
                }
                script.echo "[Android] ✓ Firmato: ${artifact}"
            }
        }
    }

    /**
     * Upload su Google Play Console tramite Google Play Publisher API.
     * Usa service account JSON per autenticazione.
     * @param artifactPath percorso APK/AAB
     * @param serviceAccountId Jenkins credentials ID per service account JSON
     * @param applicationId package name (es: com.azienda.app)
     * @param track track di distribuzione (internal, alpha, beta, production)
     */
    def uploadToGooglePlay(String artifactPath, String serviceAccountId,
                            String applicationId, String track = 'internal') {
        script.echo "[Android] ▶ Upload Google Play: ${artifactPath} → ${track}"

        script.withCredentials([script.file(credentialsId: serviceAccountId, variable: 'SVC_ACCOUNT')]) {
            def artifactExtension = artifactPath.endsWith('.aab') ? 'aab' : 'apk'

            // Utilizzo buibdo (Brew Upgrade Interface for Build Delivery)
            // Oppure Google Play Developer API
            script.sh """
                java -jar /opt/bundletool-all-*.jar build-apks \
                    --bundle='${artifactPath}' \
                    --output=/tmp/app.apks \
                    --ks='${script.env.KEYSTORE_FILE}' 2>/dev/null || true
            """

            // Upload using Google Play Publisher API via gradle-play-publisher
            // o via curl diretto
            script.sh """
                ${script.env.GRADLEW_PATH ?: './gradlew'} \
                    publish${track.capitalize()}Bundle \
                    -PserviceAccountJson='${script.env.SVC_ACCOUNT}' \
                    -PapplicationId='${applicationId}' \
                    --no-daemon
            """
        }

        script.echo "[Android] ✓ Upload Google Play (${track}): ${applicationId}"
    }

    /**
     * Esegui lint Android e genera report.
     */
    def runLint() {
        script.sh "chmod +x ${gradleWrapper}"
        script.sh "${gradleWrapper} lint --no-daemon"
        script.echo "[Android] ✓ Lint completato"

        if (script.fileExists('app/build/reports/lint-results.html')) {
            script.publishHTML(target: [
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: 'app/build/reports',
                reportFiles: 'lint-results.html',
                reportName: 'Android Lint Report'
            ])
        }
    }

    /**
     * Esegui test Android (unit test + instrumented test se emulatore disponibile).
     */
    def runTests() {
        script.sh "${gradleWrapper} test --no-daemon"
        
        if (script.fileExists('app/build/reports/tests/')) {
            script.junit(testResults: 'app/build/reports/tests/**/*.xml')
        }
        
        script.echo "[Android] ✓ Test completati"
    }

    /**
     * Incrementa versionCode e versionName in app/build.gradle.
     */
    @NonCPS
    def bumpVersionCode() {
        def gradleFile = 'app/build.gradle'
        if (!script.fileExists(gradleFile)) {
            gradleFile = 'app/build.gradle.kts'
        }

        def content = script.readFile(file: gradleFile)
        def versionCodeMatch = content =~ /versionCode\s+(\d+)/
        if (versionCodeMatch) {
            def newCode = (versionCodeMatch[0][1] as int) + 1
            content = content.replaceFirst(/versionCode\s+\d+/, "versionCode ${newCode}")
            script.writeFile(file: gradleFile, text: content)
            script.echo "[Android] ✓ versionCode → ${newCode}"
        }
    }
}