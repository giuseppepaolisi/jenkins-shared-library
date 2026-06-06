/**
 * pipelineUtils.groovy — Utility di base per tutte le pipeline enterprise.
 * Fornisce retry, safe stage, timing, cleanup, e gestione errori.
 *
 * Utilizzo:
 *   pipelineUtils.retryWithBackoff(3) { sh 'comando instabile' }
 *   pipelineUtils.safeStage('Test', false) { ... }   // non blocca se fallisce
 *   pipelineUtils.parallelSafe(['A': {}, 'B': {}])
 */
import io.awesome.jenkins.Utils

def retryWithBackoff(Closure closure, int retries = 3, long backoffMs = 1000, String description = '') {
    def utils = new Utils(this)
    return utils.retryWithBackoff(closure, retries, backoffMs, description)
}

def safeStage(String name, Closure closure, boolean failBuild = true) {
    def utils = new Utils(this)
    return utils.safeStage(name, closure, failBuild)
}

def parallelSafe(Map branches, boolean failFast = false) {
    def utils = new Utils(this)
    return utils.parallelSafe(branches, failFast)
}

def withTimeout(Closure closure, int minutes = 10) {
    timeout(time: minutes, unit: 'MINUTES') {
        return closure.call()
    }
}

def checkPreconditions(Map conditions) {
    conditions.each { description, check ->
        if (!check.call()) {
            error("[Precondition] ❌ Fallita: ${description}")
        }
        echo "[Precondition] ✓ OK: ${description}"
    }
}

def gitCleanCheck() {
    def status = sh(script: 'git status --porcelain', returnStdout: true).trim()
    if (status) {
        error("[Git] ❌ Working directory sporco. Commit o stash prima di procedere:\n${status}")
    }
    echo '[Git] ✓ Working directory pulito'
}

def ensureBranch(String allowedBranch) {
    def currentBranch = env.BRANCH_NAME ?: ''
    if (currentBranch != allowedBranch) {
        error("[Git] ❌ Branch richiesto: ${allowedBranch}, corrente: ${currentBranch}")
    }
    echo "[Git] ✓ Branch corretto: ${currentBranch}"
}

def archiveReports() {
    // Archivia report standard se esistono
    def patterns = [
        '**/build/reports/**/*',
        '**/target/surefire-reports/**/*.xml',
        '**/target/failsafe-reports/**/*.xml',
        '**/build/test-results/**/*.xml',
        '**/trivy-report.json',
        '**/sbom.json',
        '**/dependency-check-report.*'
    ]

    patterns.each { pattern ->
        def files = findFiles(glob: pattern)
        if (files) {
            archiveArtifacts(artifacts: pattern, allowEmpty: true, fingerprint: true)
            echo "[Archive] ✓ Archiviati: ${pattern}"
        }
    }
}

def printBuildSummary(String status, String duration = '', String version = '') {
    echo """
╔══════════════════════════════════════════════════════╗
║                RIEPILOGO BUILD                       ║
╠══════════════════════════════════════════════════════╣
║ Job:       ${env.JOB_NAME?.padRight(42) ?: 'N/D'.padRight(42)}║
║ Build #:   ${(env.BUILD_NUMBER ?: '').padRight(42)}║
║ Branch:    ${(env.BRANCH_NAME ?: '').padRight(42)}║
║ Status:    ${status.padRight(42)}║
║ Durata:    ${duration.padRight(42)}║
${version ? "║ Versione:  ${version.padRight(42)}║\n" : ''}║ Commit:    ${(env.GIT_COMMIT ?: '').take(40).padRight(42)}║
╚══════════════════════════════════════════════════════╝
"""
}

def cleanupWorkspace() {
    dir('**') {
        deleteDir()
    }
    echo '[Cleanup] ✓ Workspace pulito'
}

return this