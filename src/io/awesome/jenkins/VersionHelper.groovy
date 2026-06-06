package io.awesome.jenkins

/**
 * Helper enterprise per version bumping automatico/semiautomatico.
 * Supporta:
 * - Conventional Commits (feat → minor, fix → patch, BREAKING → major)
 * - Manuale (major, minor, patch)
 * - Progetti Node (package.json), Maven (pom.xml), Gradle (build.gradle)
 * - Generazione CHANGELOG automatica in formato Keep a Changelog
 * - Git tag firmato (GPG) + push sicuro
 */
class VersionHelper implements Serializable {
    private static final long serialVersionUID = 1L
    private static final String CHANGELOG_FILE = 'CHANGELOG.md'

    private final Script script
    private final String projectType // 'node', 'maven', 'gradle'
    private final String gitUserName
    private final String gitUserEmail
    private final boolean useGpgSign

    VersionHelper(Script script, String projectType, String gitUser = 'Jenkins CI',
                   String gitEmail = 'jenkins@azienda.local', boolean gpgSign = true) {
        this.script = script
        this.projectType = projectType
        this.gitUserName = gitUser
        this.gitUserEmail = gitEmail
        this.useGpgSign = gpgSign
    }

    /**
     * Rileva automaticamente il tipo di progetto basandosi sui file presenti.
     */
    @NonCPS
    static String detectProjectType(Script script) {
        if (script.fileExists('package.json')) return 'node'
        if (script.fileExists('pom.xml')) return 'maven'
        if (script.fileExists('build.gradle') || script.fileExists('build.gradle.kts')) return 'gradle'
        if (script.fileExists('pyproject.toml') || script.fileExists('setup.py') || script.fileExists('setup.cfg')) return 'python'
        if (script.fileExists('go.mod')) return 'go'
        if (script.fileExists('Cargo.toml')) return 'rust'
        return ''
    }

    /**
     * Determina il bump type basato sull'ultimo commit (Conventional Commits).
     * @return 'major', 'minor', o 'patch'
     */
    @NonCPS
    String detectBumpTypeFromCommit() {
        def lastCommitMsg = script.sh(
            script: 'git log --oneline -1 --format="%s"',
            returnStdout: true
        ).trim().toLowerCase()

        script.echo "[Version] Ultimo commit: ${lastCommitMsg}"

        // Conventional Commits parsing
        if (lastCommitMsg =~ /^.*breaking\s*change|^.*!\s*:|^.*major/) {
            return 'major'
        } else if (lastCommitMsg =~ /^feat[^a-z]|^feature[^a-z]/) {
            return 'minor'
        } else if (lastCommitMsg =~ /^fix[^a-z]|^bug|^patch|^docs|^chore|^refactor|^perf|^test|^ci/) {
            return 'patch'
        } else {
            // Default safe: patch
            script.echo "[Version] ⚠ Commit non conventional, default: patch"
            return 'patch'
        }
    }

    /**
     * Legge la versione corrente dal progetto.
     * @return versione corrente come stringa
     */
    @NonCPS
    String getCurrentVersion() {
        switch (projectType) {
            case 'node':
                return readNodeVersion()
            case 'maven':
                return readMavenVersion()
            case 'gradle':
                return readGradleVersion()
            case 'python':
                return readPythonVersion()
            case 'go':
                return readGoVersion()
            case 'rust':
                return readRustVersion()
            default:
                script.error("[Version] Tipo progetto sconosciuto: ${projectType}")
        }
    }

    /**
     * Calcola la nuova versione dato il bump type.
     */
    @NonCPS
    String calculateNewVersion(String currentVersion, String bumpType) {
        def parts = currentVersion.split('\\.')
        if (parts.size() != 3) {
            script.error("[Version] Versione non semantica: ${currentVersion}")
        }

        int major = parts[0] as int
        int minor = parts[1] as int
        int patch = parts[2] as int

        switch (bumpType) {
            case 'major':
                major += 1; minor = 0; patch = 0
                break
            case 'minor':
                minor += 1; patch = 0
                break
            case 'patch':
                patch += 1
                break
            default:
                script.error("[Version] Bump type non valido: ${bumpType}. Usa major, minor, patch.")
        }

        return "${major}.${minor}.${patch}"
    }

    /**
     * Applica la nuova versione al progetto (package.json, pom.xml, build.gradle).
     * @param newVersion versione da applicare
     */
    void applyVersion(String newVersion) {
        script.echo "[Version] Applico versione ${newVersion} al progetto ${projectType}..."

        switch (projectType) {
            case 'node':
                applyNodeVersion(newVersion)
                break
            case 'maven':
                applyMavenVersion(newVersion)
                break
            case 'gradle':
                applyGradleVersion(newVersion)
                break
            case 'python':
                applyPythonVersion(newVersion)
                break
            case 'go':
                applyGoVersion(newVersion)
                break
            case 'rust':
                applyRustVersion(newVersion)
                break
        }

        script.echo "[Version] ✓ Versione aggiornata: ${newVersion}"
    }

    /**
     * Esegue bump completo: calcola nuova versione, applica, crea tag, pusha.
     * @param bumpType 'auto' (detect da commit), 'major', 'minor', 'patch'
     * @param dryRun se true, non fa commit/push
     */
    String bump(Map params = [:]) {
        String bumpType = params.bumpType ?: 'auto'
        boolean dryRun = params.dryRun ?: false
        boolean createChangelog = params.changelog != false

        // Safety check: working directory pulito
        def status = script.sh(script: 'git status --porcelain', returnStdout: true).trim()
        if (!dryRun && status) {
            script.error("[Version] ❌ Working directory sporco. Commit o stash prima di bump:\n${status}")
        }

        // Determina bump type
        String resolvedType = (bumpType == 'auto') ? detectBumpTypeFromCommit() : bumpType
        script.echo "[Version] Bump type risolto: ${resolvedType}"

        // Leggi versione corrente e calcola nuova
        String currentVersion = getCurrentVersion()
        String newVersion = calculateNewVersion(currentVersion, resolvedType)
        script.echo "[Version] ${currentVersion} → ${newVersion} (${resolvedType})"

        if (dryRun) {
            script.echo "[Version] Dry-run: versione ${newVersion} non applicata"
            return newVersion
        }

        // Applica versione
        applyVersion(newVersion)

        // Genera CHANGELOG
        if (createChangelog) {
            generateChangelog(newVersion)
        }

        // Configura Git per commit sicuro
        configureGitUser()

        // Commit, tag, push
        script.sh "git add -A"
        script.sh "git commit -m 'chore(release): bump version to ${newVersion}'"
        script.sh "npm install 2>/dev/null || true" // update package-lock.json se esiste
        script.sh "git add -A && git commit --amend --no-edit 2>/dev/null || true"

        // Tag con o senza GPG sign
        if (useGpgSign) {
            script.sh "git tag -s -m 'Release ${newVersion}' v${newVersion}"
        } else {
            script.sh "git tag -a -m 'Release ${newVersion}' v${newVersion}"
        }

        script.sh "git push origin --follow-tags"
        script.echo "[Version] ✓ Release v${newVersion} completata"

        return newVersion
    }

    // --- Metodi privati per tipo progetto ---

    @NonCPS
    private String readNodeVersion() {
        def pkg = script.readJSON(file: 'package.json')
        return pkg.version
    }

    @NonCPS
    private String readMavenVersion() {
        return script.sh(
            script: """mvn -q -Dexec.executable='echo' -Dexec.args='\${project.version}' --non-recursive exec:exec 2>/dev/null || grep -m1 '<version>' pom.xml | head -1 | sed 's/.*<version>\\(.*\\)<\\/version>.*/\\1/'""",
            returnStdout: true
        ).trim()
    }

    @NonCPS
    private String readGradleVersion() {
        return script.sh(
            script: """grep -E "^version\\s*=" build.gradle | sed 's/.*version\\s*=\\s*["'\'']\\(.*\\)["'\'']/\\1/' || grep -E "^version\\s*=" build.gradle.kts | sed 's/.*version\\s*=\\s*"\\(.*\\)"/\\1/'""",
            returnStdout: true
        ).trim() ?: '0.0.0'
    }

    @NonCPS
    private void applyNodeVersion(String newVersion) {
        script.sh "npm version ${newVersion} --no-git-tag-version --allow-same-version"
    }

    @NonCPS
    private void applyMavenVersion(String newVersion) {
        script.sh "mvn versions:set -DnewVersion=${newVersion} -DgenerateBackupPoms=false -q"
    }

    @NonCPS
    private void applyGradleVersion(String newVersion) {
        if (script.fileExists('build.gradle')) {
            script.sh "sed -i 's/^version\\s*=.*/version = \"${newVersion}\"/' build.gradle"
        } else if (script.fileExists('build.gradle.kts')) {
            script.sh "sed -i 's/^version\\s*=.*/version = \"${newVersion}\"/' build.gradle.kts"
        }
    }

    @NonCPS
    private void configureGitUser() {
        script.sh "git config user.name '${gitUserName}'"
        script.sh "git config user.email '${gitUserEmail}'"
        if (useGpgSign) {
            script.sh "git config commit.gpgsign true"
            script.sh "git config tag.gpgsign true"
        }
    }

    /**
     * Genera CHANGELOG.md in formato Keep a Changelog.
     */
    @NonCPS
    private void generateChangelog(String newVersion) {
        def changelog = "# Changelog\n\n"
        changelog += "## [${newVersion}] - ${new Date().format('yyyy-MM-dd')}\n\n"

        // Estrai commit dall'ultimo tag
        def commitLog = script.sh(
            script: """
                LAST_TAG=\$(git describe --tags --abbrev=0 2>/dev/null || echo "")
                if [ -z "\$LAST_TAG" ]; then
                    git log --pretty=format:"- %s (%an)"
                else
                    git log \${LAST_TAG}..HEAD --pretty=format:"- %s (%an)"
                fi
            """,
            returnStdout: true
        ).trim()

        if (commitLog) {
            changelog += "### Cambiamenti\n\n${commitLog}\n\n"
        }

        def existingChangelog = script.fileExists(CHANGELOG_FILE) ? script.readFile(file: CHANGELOG_FILE) : ''
        def combined = existingChangelog ? changelog + "\n" + existingChangelog.replaceFirst(/^# Changelog\n*/, '') : changelog
        script.writeFile(file: CHANGELOG_FILE, text: combined)
        script.echo "[Version] ✓ CHANGELOG.md aggiornato"
    }
}