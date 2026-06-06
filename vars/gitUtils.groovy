/**
 * gitUtils.groovy — Operazioni Git enterprise sicure.
 * Commit, push, tag, branch, merge, clone, changelog, stash, blame, submodule, LFS.
 *
 * Utilizzo:
 *   // Configura Git user (automatico se GIT_USER_NAME/EMAIL impostati)
 *   gitUtils.configure()
 *
 *   // Commit e push
 *   def sha = gitUtils.commit('feat: aggiunto login OAuth')
 *   gitUtils.push()
 *
 *   // Commit + Tag + Push in un colpo solo
 *   gitUtils.commitTagAndPush('chore(release): bump to 1.2.0', '1.2.0')
 *
 *   // Tag
 *   gitUtils.createTag('1.3.0', 'Release 1.3.0 con fix critico')
 *   gitUtils.deleteTag('1.0.0-beta')
 *
 *   // Branch
 *   gitUtils.createBranch('feature/new-api', 'main', true)
 *   gitUtils.merge('feature/new-api', 'squash', 'feat: merged new API')
 *
 *   // Clone
 *   gitUtils.clone('git@github.com:azienda/repo.git', 'main', './repo', true)
 *
 *   // Pull
 *   gitUtils.pull('origin', 'main', true, true)
 *
 *   // Stash
 *   gitUtils.stash('WIP: refactoring in corso', 'save')
 *   gitUtils.stash('', 'pop')
 *
 *   // Informazioni
 *   def branch = gitUtils.getCurrentBranch()
 *   def sha = gitUtils.getCurrentSha(true)
 *   def tags = gitUtils.listTags('v*')
 *   def latest = gitUtils.getLatestTag('v*')
 *   def changelog = gitUtils.getChangelog()
 *
 *   // Submodule & LFS
 *   gitUtils.submoduleUpdate()
 *   gitUtils.lfsPull()
 *
 *   // Audit
 *   def annotations = gitUtils.blame('src/main.py', 42)
 *
 *   // PR
 *   gitUtils.fetchPR(142)  // fetch PR #142 in locale
 *
 * Configurazione via env:
 *   GIT_USER_NAME   = 'Jenkins CI'
 *   GIT_USER_EMAIL  = 'jenkins@azienda.local'
 *   GPG_SIGN        = 'true'
 *   GIT_SIGNING_KEY = 'ABC123DEF'   (opzionale, key ID GPG)
 */
import io.awesome.jenkins.GitHelper

def configure() {
    createHelper().configure()
}

def clone(String url, String branch = 'main', String targetDir = '.',
          boolean shallow = false, int depth = 1) {
    createHelper().clone(url, branch, targetDir, shallow, depth)
}

def pull(String remote = 'origin', String branch = '',
         boolean rebase = true, boolean autoStash = true) {
    createHelper().pull(remote, branch, rebase, autoStash)
}

def commit(String message, List<String> files = [], boolean allowEmpty = false) {
    return createHelper().commit(message, files, allowEmpty)
}

def push(String remote = 'origin', String branch = '',
         boolean force = false, boolean followTags = true) {
    createHelper().push(remote, branch, force, followTags)
}

def createTag(String tagName, String message = '', String commitSha = '',
              boolean push = true) {
    return createHelper().createTag(tagName, message, commitSha, push)
}

def deleteTag(String tagName) {
    createHelper().deleteTag(tagName)
}

def createBranch(String branchName, String baseBranch = '', boolean push = false) {
    createHelper().createBranch(branchName, baseBranch, push)
}

def merge(String sourceBranch, String strategy = 'merge', String message = '') {
    createHelper().merge(sourceBranch, strategy, message)
}

def cherryPick(String commitSha) {
    createHelper().cherryPick(commitSha)
}

def stash(String message = 'WIP', String action = 'save') {
    return createHelper().stash(message, action)
}

def ensureCleanWorkingDirectory() {
    createHelper().ensureCleanWorkingDirectory()
}

def hasUncommittedChanges() {
    return createHelper().hasUncommittedChanges()
}

def getChangelog(String from = '', String to = 'HEAD', String format = 'conventional') {
    return createHelper().getChangelog(from, to, format)
}

def getCurrentBranch() {
    return createHelper().getCurrentBranch()
}

def getCurrentSha(boolean shortFormat = true) {
    return createHelper().getCurrentSha(shortFormat)
}

def listTags(String pattern = 'v*', boolean sortByVersion = true) {
    return createHelper().listTags(pattern, sortByVersion)
}

def getLatestTag(String pattern = 'v*') {
    return createHelper().getLatestTag(pattern)
}

def diff(String from, String to = 'HEAD', String pathSpec = '', boolean stat = false) {
    return createHelper().diff(from, to, pathSpec, stat)
}

def submoduleUpdate(boolean recursive = true, boolean init = true) {
    createHelper().submoduleUpdate(recursive, init)
}

def lfsPull() {
    createHelper().lfsPull()
}

def blame(String filePath, int line = 0) {
    return createHelper().blame(filePath, line)
}

def fetchPR(int prNumber, String remote = 'origin') {
    createHelper().fetchPR(prNumber, remote)
}

def fetch(String remote = 'origin', boolean prune = true) {
    createHelper().fetch(remote, prune)
}

def reset(String target = 'HEAD', String mode = 'hard') {
    createHelper().reset(target, mode)
}

def commitTagAndPush(String commitMessage, String tagName = '', boolean push = true) {
    return createHelper().commitTagAndPush(commitMessage, tagName, push)
}

@NonCPS
private GitHelper createHelper() {
    def userName = env.GIT_USER_NAME ?: 'Jenkins CI'
    def userEmail = env.GIT_USER_EMAIL ?: 'jenkins@azienda.local'
    def gpgSign = (env.GPG_SIGN ?: 'true') == 'true'
    def signingKey = env.GIT_SIGNING_KEY ?: ''
    return new GitHelper(this, 'git', userName, userEmail, gpgSign, signingKey)
}

return this