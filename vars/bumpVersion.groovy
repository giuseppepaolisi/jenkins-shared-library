/**
 * bumpVersion.groovy — Version bumping automatico/semiautomatico enterprise.
 *
 * Utilizzo:
 *   // Automatico (conventional commits)
 *   def newVer = bumpVersion(projectType: 'node')
 *   def newVer = bumpVersion(projectType: 'maven')
 *   def newVer = bumpVersion(projectType: 'gradle')
 *
 *   // Manuale
 *   def newVer = bumpVersion(projectType: 'node', bumpType: 'major')
 *   def newVer = bumpVersion(projectType: 'node', bumpType: 'minor')
 *   def newVer = bumpVersion(projectType: 'node', bumpType: 'patch')
 *
 *   // Dry-run (solo calcolo, nessun commit/push)
 *   def newVer = bumpVersion(projectType: 'node', bumpType: 'auto', dryRun: true)
 *
 *   // Senza changelog
 *   def newVer = bumpVersion(projectType: 'node', changelog: false)
 *
 * Configurazione via env:
 *   GIT_USER_NAME  = 'Jenkins CI'
 *   GIT_USER_EMAIL = 'jenkins@azienda.local'
 *   GPG_SIGN       = 'true' (usa GPG signing per i tag)
 */
import io.awesome.jenkins.VersionHelper

def call(Map params = [:]) {
    String projectType = params.projectType ?: detectProjectType()
    String bumpType = params.bumpType ?: 'auto'
    boolean dryRun = params.dryRun ?: false
    boolean changelog = params.changelog != false
    boolean gpgSign = (env.GPG_SIGN ?: 'true') == 'true'

    def helper = new VersionHelper(
        this,
        projectType,
        env.GIT_USER_NAME ?: 'Jenkins CI',
        env.GIT_USER_EMAIL ?: 'jenkins@azienda.local',
        gpgSign
    )

    if (bumpType == 'auto') {
        echo "[bumpVersion] Rilevamento automatico bump type da conventional commit..."
    } else {
        echo "[bumpVersion] Bump manuale: ${bumpType}"
    }

    def newVersion = helper.bump([
        bumpType : bumpType,
        dryRun   : dryRun,
        changelog: changelog
    ])

    echo "[bumpVersion] ✓ Nuova versione: ${newVersion}"
    return newVersion
}

/**
 * Rileva automaticamente il tipo di progetto basandosi sui file presenti.
 */
@NonCPS
private String detectProjectType() {
    if (fileExists('package.json')) {
        echo '[bumpVersion] Rilevato progetto Node.js (package.json)'
        return 'node'
    }
    if (fileExists('pom.xml')) {
        echo '[bumpVersion] Rilevato progetto Maven (pom.xml)'
        return 'maven'
    }
    if (fileExists('build.gradle') || fileExists('build.gradle.kts')) {
        echo '[bumpVersion] Rilevato progetto Gradle (build.gradle)'
        return 'gradle'
    }
    error('[bumpVersion] Impossibile rilevare tipo progetto. Specifica projectType: node|maven|gradle')
}

return this