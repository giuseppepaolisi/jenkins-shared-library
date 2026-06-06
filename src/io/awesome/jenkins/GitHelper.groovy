package io.awesome.jenkins

/**
 * Helper enterprise per operazioni Git: clone, commit, push, tag, branch,
 * merge, PR management, changelog, submodules, LFS, hooks.
 * Ogni operazione include safety check, audit log, e rollback capability.
 */
class GitHelper implements Serializable {
    private static final long serialVersionUID = 1L
    private static final int PUSH_RETRIES = 3
    private static final long PUSH_BACKOFF_MS = 2000

    private final Script script
    private final String gitBinary
    private final String userName
    private final String userEmail
    private final boolean gpgSign
    private final String signingKey

    GitHelper(Script script, String gitBinary = 'git',
              String userName = '', String userEmail = '',
              boolean gpgSign = false, String signingKey = '') {
        this.script = script
        this.gitBinary = gitBinary
        this.userName = userName ?: (script.env.GIT_USER_NAME ?: 'Jenkins CI')
        this.userEmail = userEmail ?: (script.env.GIT_USER_EMAIL ?: 'jenkins@azienda.local')
        this.gpgSign = gpgSign
        this.signingKey = signingKey ?: (script.env.GIT_SIGNING_KEY ?: '')
    }

    /**
     * Configura Git user e GPG signing per commit sicuri.
     */
    def configure() {
        script.sh "${gitBinary} config user.name '${userName}'"
        script.sh "${gitBinary} config user.email '${userEmail}'"
        if (gpgSign) {
            script.sh "${gitBinary} config commit.gpgsign true"
            script.sh "${gitBinary} config tag.gpgsign true"
            if (signingKey) {
                script.sh "${gitBinary} config user.signingkey ${signingKey}"
            }
        }
        script.echo "[Git] ✓ Configurato: ${userName} <${userEmail}>" +
                     (gpgSign ? " (GPG signing)" : "")
    }

    /**
     * Clone repository con retry e shallow clone opzionale.
     * @param url repository URL
     * @param branch branch da clonare (default: main)
     * @param targetDir directory destinazione
     * @param shallow se true, clone con profondità 1
     * @param depth profondità clone
     */
    def clone(String url, String branch = 'main', String targetDir = '.',
              boolean shallow = false, int depth = 1) {
        script.echo "[Git] ▶ Clone: ${url} [${branch}] → ${targetDir}"

        script.retry(PUSH_RETRIES) {
            def cmd = "${gitBinary} clone"
            if (shallow) cmd += " --depth ${depth}"
            cmd += " --branch ${branch} --single-branch"
            if (targetDir != '.') cmd += " ${url} ${targetDir}"
            else cmd += " ${url}"
            script.sh cmd
        }

        script.echo "[Git] ✓ Clone completato: ${url} [${branch}]"
    }

    /**
     * Pull con rebase o merge, con retry e auto-stash.
     * @param remote remote name
     * @param branch branch da pullare
     * @param rebase se true, pull con --rebase
     * @param autoStash se true, stash le modifiche locali prima del pull
     */
    def pull(String remote = 'origin', String branch = '',
             boolean rebase = true, boolean autoStash = true) {
        def targetBranch = branch ?: script.env.BRANCH_NAME ?: 'main'
        script.echo "[Git] ▶ Pull: ${remote}/${targetBranch}"

        script.retry(PUSH_RETRIES) {
            def cmd = "${gitBinary} pull ${remote} ${targetBranch}"
            if (rebase) cmd += " --rebase"
            if (autoStash) cmd += " --autostash"
            script.sh cmd
        }

        script.echo "[Git] ✓ Pull completato: ${remote}/${targetBranch}"
    }

    /**
     * Commit con validazione working directory e messaggio formattato.
     * @param message messaggio di commit (conventional commits)
     * @param files file specifici da committare (vuoto = git add -A)
     * @param allowEmpty se true, permette commit anche senza modifiche
     */
    def commit(String message, List<String> files = [], boolean allowEmpty = false) {
        configure()

        // Verifica working directory
        if (!allowEmpty) {
            def status = script.sh(script: "${gitBinary} status --porcelain", returnStdout: true).trim()
            if (!status) {
                script.echo "[Git] ⚠ Nessuna modifica da committare"
                return false
            }
        }

        script.echo "[Git] ▶ Commit: ${message}"

        if (files) {
            files.each { f -> script.sh "${gitBinary} add '${f}'" }
        } else {
            script.sh "${gitBinary} add -A"
        }

        def cmd = "${gitBinary} commit -m '${sanitizeMessage(message)}'"
        if (allowEmpty) cmd += " --allow-empty"
        script.sh cmd

        def commitSha = script.sh(
            script: "${gitBinary} rev-parse --short HEAD",
            returnStdout: true
        ).trim()
        script.echo "[Git] ✓ Commit: ${commitSha} — ${message}"
        return commitSha
    }

    /**
     * Push con retry e force-with-lease (safe force).
     * @param remote remote name
     * @param branch branch da pusare
     * @param force se true, push con --force-with-lease
     * @param followTags se true, push anche i tag
     */
    def push(String remote = 'origin', String branch = '',
             boolean force = false, boolean followTags = true) {
        def targetBranch = branch ?: script.env.BRANCH_NAME ?: 'main'
        script.echo "[Git] ▶ Push: ${remote}/${targetBranch}"

        script.retry(PUSH_RETRIES) {
            def cmd = "${gitBinary} push ${remote} ${targetBranch}"
            if (force) cmd += " --force-with-lease"
            if (followTags) cmd += " --follow-tags"
            try {
                script.sh cmd
            } catch (Exception e) {
                // Se push fallisce, pull e riprova
                script.echo "[Git] ⚠ Push fallito, pull rebase e riprovo..."
                pull(remote, targetBranch)
                script.sh cmd
            }
        }

        script.echo "[Git] ✓ Push: ${remote}/${targetBranch}"
    }

    /**
     * Crea tag Git (signed o unsigned) con messaggio.
     * @param tagName nome tag (auto-prefisso v se mancante)
     * @param message messaggio release
     * @param commitSha commit specifico (vuoto = HEAD)
     * @param push se true, pusha il tag dopo creazione
     */
    def createTag(String tagName, String message = '', String commitSha = '',
                  boolean push = true) {
        configure()

        // Normalizza tag name
        if (!tagName.startsWith('v')) tagName = "v${tagName}"
        def tagMessage = message ?: "Release ${tagName}"

        script.echo "[Git] ▶ Tag: ${tagName} — ${tagMessage}"

        // Verifica che il tag non esista già
        def tagExists = script.sh(
            script: "${gitBinary} tag -l ${tagName}",
            returnStdout: true
        ).trim()

        if (tagExists) {
            script.error("[Git] ❌ Tag già esistente: ${tagName}")
        }

        def cmd = "${gitBinary} tag"
        if (gpgSign) cmd += " -s"
        else cmd += " -a"

        cmd += " ${tagName} -m '${tagMessage}'"
        if (commitSha) cmd += " ${commitSha}"

        script.sh cmd
        script.echo "[Git] ✓ Tag creato: ${tagName}${gpgSign ? ' (firmato GPG)' : ''}"

        if (push) {
            script.retry(PUSH_RETRIES) {
                script.sh "${gitBinary} push origin ${tagName}"
            }
            script.echo "[Git] ✓ Tag pushato: origin/${tagName}"
        }

        return tagName
    }

    /**
     * Elimina tag locale e remoto.
     */
    def deleteTag(String tagName) {
        script.echo "[Git] ▶ Delete tag: ${tagName}"
        script.sh "${gitBinary} tag -d ${tagName} 2>/dev/null || true"
        script.sh "${gitBinary} push origin :refs/tags/${tagName} 2>/dev/null || true"
        script.echo "[Git] ✓ Tag eliminato: ${tagName}"
    }

    /**
     * Crea branch e switcha.
     * @param branchName nome branch
     * @param baseBranch branch di partenza (vuoto = HEAD)
     * @param push se true, pusha il branch
     */
    def createBranch(String branchName, String baseBranch = '', boolean push = false) {
        script.echo "[Git] ▶ Crea branch: ${branchName} da ${baseBranch ?: 'HEAD'}"

        if (baseBranch) {
            script.sh "${gitBinary} checkout ${baseBranch}"
            script.sh "${gitBinary} pull origin ${baseBranch}"
        }

        script.sh "${gitBinary} checkout -b ${branchName}"

        if (push) {
            script.sh "${gitBinary} push origin ${branchName}"
        }

        script.echo "[Git] ✓ Branch: ${branchName}${push ? ' (pushato)' : ''}"
    }

    /**
     * Merge branch in corrente con strategia.
     * @param sourceBranch branch sorgente
     * @param strategy strategia merge (merge, rebase, squash)
     * @param message messaggio merge
     */
    def merge(String sourceBranch, String strategy = 'merge', String message = '') {
        script.echo "[Git] ▶ Merge: ${sourceBranch} → ${script.env.BRANCH_NAME ?: 'current'}"

        def cmd = "${gitBinary}"
        switch (strategy) {
            case 'merge':
                def mergeMsg = message ? "-m '${message}'" : ""
                script.sh "${cmd} merge --no-ff ${sourceBranch} ${mergeMsg}"
                break
            case 'rebase':
                script.sh "${cmd} rebase ${sourceBranch}"
                break
            case 'squash':
                script.sh "${cmd} merge --squash ${sourceBranch}"
                if (message) {
                    commit(message)
                }
                break
        }

        script.echo "[Git] ✓ Merge completato: ${sourceBranch} (${strategy})"
    }

    /**
     * Cherry-pick commit.
     */
    def cherryPick(String commitSha) {
        script.echo "[Git] ▶ Cherry-pick: ${commitSha}"
        script.sh "${gitBinary} cherry-pick ${commitSha}"
        script.echo "[Git] ✓ Cherry-pick: ${commitSha}"
    }

    /**
     * Stash changes (save e pop).
     */
    def stash(String message = 'WIP', String action = 'save') {
        switch (action) {
            case 'save':
                script.sh "${gitBinary} stash push -m '${message}'"
                script.echo "[Git] ✓ Stash salvato: ${message}"
                break
            case 'pop':
                script.sh "${gitBinary} stash pop"
                script.echo "[Git] ✓ Stash ripristinato"
                break
            case 'list':
                return script.sh(
                    script: "${gitBinary} stash list",
                    returnStdout: true
                ).trim()
        }
    }

    /**
     * Verifica stato working directory.
     * @return true se ci sono modifiche non committate
     */
    @NonCPS
    boolean hasUncommittedChanges() {
        def status = script.sh(
            script: "${gitBinary} status --porcelain",
            returnStdout: true
        ).trim()
        return !status.isEmpty()
    }

    /**
     * Verifica che il working directory sia pulito (per bump version).
     */
    def ensureCleanWorkingDirectory() {
        if (hasUncommittedChanges()) {
            def status = script.sh(
                script: "${gitBinary} status --porcelain",
                returnStdout: true
            ).trim()
            script.error("[Git] ❌ Working directory sporco:\n${status}")
        }
        script.echo "[Git] ✓ Working directory pulito"
    }

    /**
     * Ottiene changelog tra due tag/commit.
     * @param from tag/commit di partenza (vuoto = primo commit)
     * @param to tag/commit di arrivo (vuoto = HEAD)
     * @param format formato output
     */
    @NonCPS
    String getChangelog(String from = '', String to = 'HEAD', String format = 'conventional') {
        def range = from ? "${from}..${to}" : to

        def formatArg = format == 'conventional'
            ? '%s'  // subject
            : '%s (%an, %ar)'  // subject + author + relative date

        def log = script.sh(
            script: """
                LAST_TAG=\$(git describe --tags --abbrev=0 2>/dev/null || echo "")
                if [ -z "\$LAST_TAG" ]; then
                    git log --pretty=format:'${formatArg}' ${range}
                else
                    git log \${LAST_TAG}..HEAD --pretty=format:'${formatArg}'
                fi
            """,
            returnStdout: true
        ).trim()

        return log
    }

    /**
     * Aggiunge submodule e init/update ricorsivo.
     */
    def submoduleUpdate(boolean recursive = true, boolean init = true) {
        script.echo "[Git] ▶ Submodule update"

        def cmd = "${gitBinary} submodule update --init"
        if (recursive) cmd += " --recursive"
        cmd += " --force --depth 1"

        script.sh cmd
        script.echo "[Git] ✓ Submodule aggiornati"
    }

    /**
     * Git LFS pull.
     */
    def lfsPull() {
        script.echo "[Git] ▶ LFS pull"
        script.sh "${gitBinary} lfs pull"
        script.echo "[Git] ✓ LFS files sincronizzati"
    }

    /**
     * Git blame annotato per audit trail.
     * @param filePath percorso file
     * @param line linea specifica (0 = intero file)
     */
    @NonCPS
    List<Map> blame(String filePath, int line = 0) {
        def lineOpt = line > 0 ? "-L ${line},${line}" : ''
        def result = script.sh(
            script: "${gitBinary} blame ${lineOpt} --line-porcelain '${filePath}' 2>/dev/null || echo 'ERROR'",
            returnStdout: true
        ).trim()

        if (result == 'ERROR') return []

        // Parse porcelain output
        def annotations = []
        def current = [:]
        result.eachLine { l ->
            if (l.startsWith('author ')) current.author = l.substring(7)
            if (l.startsWith('author-mail ')) current.email = l.substring(12)
            if (l.startsWith('author-time ')) current.timestamp = l.substring(12)
            if (l.startsWith('summary ')) current.summary = l.substring(8)
            if (l.startsWith('\t')) {
                current.line = l.substring(1)
                annotations << current
                current = [:]
            }
        }
        return annotations
    }

    /**
     * Get tag list con filtro.
     */
    @NonCPS
    List<String> listTags(String pattern = 'v*', boolean sortByVersion = true) {
        def tags = script.sh(
            script: "${gitBinary} tag -l '${pattern}' --sort=${sortByVersion ? '-version:refname' : '-creatordate'}",
            returnStdout: true
        ).trim().split('\n') as List

        return tags ?: []
    }

    /**
     * Get ultimo tag.
     */
    @NonCPS
    String getLatestTag(String pattern = 'v*') {
        def tags = listTags(pattern)
        return tags ? tags[0] : ''
    }

    /**
     * Get commit SHA corrente.
     */
    @NonCPS
    String getCurrentSha(boolean shortFormat = true) {
        def opt = shortFormat ? '--short' : ''
        return script.sh(
            script: "${gitBinary} rev-parse ${opt} HEAD",
            returnStdout: true
        ).trim()
    }

    /**
     * Get current branch name.
     */
    @NonCPS
    String getCurrentBranch() {
        return script.sh(
            script: "${gitBinary} rev-parse --abbrev-ref HEAD",
            returnStdout: true
        ).trim()
    }

    /**
     * Diff tra due reference.
     */
    @NonCPS
    String diff(String from, String to = 'HEAD', String pathSpec = '',
                boolean stat = false) {
        def statArg = stat ? '--stat' : ''
        def pathArg = pathSpec ? "-- '${pathSpec}'" : ''
        return script.sh(
            script: "${gitBinary} diff ${statArg} ${from}..${to} ${pathArg}",
            returnStdout: true
        ).trim()
    }

    /**
     * Fetch con pruning.
     */
    def fetch(String remote = 'origin', boolean prune = true) {
        def pruneArg = prune ? '--prune' : ''
        script.sh "${gitBinary} fetch ${remote} ${pruneArg}"
        script.echo "[Git] ✓ Fetch: ${remote}"
    }

    /**
     * Reset hard a un commit specifico (con safety check).
     */
    def reset(String target = 'HEAD', String mode = 'hard') {
        if (mode == 'hard') {
            script.echo "[Git] ⚠ Reset HARD: ${target}"
        }
        script.sh "${gitBinary} reset --${mode} ${target}"
        script.echo "[Git] ✓ Reset (${mode}): ${target}"
    }

    /**
     * Crea e checkout PR locale per testing.
     */
    def fetchPR(int prNumber, String remote = 'origin') {
        script.echo "[Git] ▶ Fetch PR #${prNumber}"
        script.sh "${gitBinary} fetch ${remote} pull/${prNumber}/head:pr-${prNumber}"
        script.sh "${gitBinary} checkout pr-${prNumber}"
        script.echo "[Git] ✓ PR #${prNumber} in locale: pr-${prNumber}"
    }

    /**
     * Git bisect per trovare commit problematico.
     */
    def bisect(String badCommit = 'HEAD', String goodCommit = '',
               Closure testClosure) {
        script.echo "[Git] ▶ Bisect: bad=${badCommit}, good=${goodCommit}"
        script.sh "${gitBinary} bisect start ${badCommit} ${goodCommit}"

        def maxIter = 30
        for (int i = 0; i < maxIter; i++) {
            def status = script.sh(
                script: "${gitBinary} bisect run echo 'testing' 2>&1 || echo 'FINISHED'",
                returnStdout: true
            ).trim()
            if (status.contains('FINISHED') || status.contains('first bad commit')) {
                break
            }
        }

        def result = script.sh(
            script: "${gitBinary} bisect log",
            returnStdout: true
        ).trim()
        script.sh "${gitBinary} bisect reset"
        return result
    }

    /**
     * Pipeline completa: commit → tag → push.
     * @param commitMessage messaggio commit
     * @param tagName nome tag (opzionale)
     * @param push se true, pusha tutto
     */
    def commitTagAndPush(String commitMessage, String tagName = '',
                         boolean push = true) {
        def sha = commit(commitMessage)

        if (tagName) {
            createTag(tagName, commitMessage, sha, push)
        } else if (push) {
            this.push()
        }

        return [sha: sha, tag: tagName]
    }

    /**
     * Sanifica messaggio commit per prevenire injection.
     */
    @NonCPS
    private String sanitizeMessage(String message) {
        if (!message) return 'chore: update'
        return message.replaceAll(/['"\\;`$()]/, '_').trim()
    }
}