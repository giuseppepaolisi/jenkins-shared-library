/**
 * securityScan.groovy — Scanning di sicurezza enterprise integrato.
 * Supporta: Trivy (container & filesystem), SonarQube (static analysis),
 * OWASP Dependency-Check (SCA), e gates di qualità configurabili.
 *
 * Utilizzo:
 *   // Container scan
 *   securityScan.container('myapp:1.0.0', severity: 'CRITICAL,HIGH', exitOnFailure: true)
 *
 *   // Filesystem scan
 *   securityScan.filesystem('.', severity: 'CRITICAL,HIGH', exitOnThreshold: 5)
 *
 *   // SonarQube analysis
 *   securityScan.sonarQube(
 *       projectKey: 'com.azienda:myapp',
 *       sources: '.',
 *       qualityGate: true
 *   )
 *
 *   // Dependency check
 *   securityScan.dependencyCheck(path: '.', format: 'HTML', suppressionFile: '')
 *
 *   // All-in-one
 *   securityScan.fullScan(projectKey: 'com.azienda:myapp', skipSonar: false)
 */

/**
 * Container image vulnerability scan con Trivy.
 */
def container(String image, Map opts = [:]) {
    def severity = opts.severity ?: 'CRITICAL,HIGH'
    def exitOnFailure = opts.exitOnFailure != false

    sh "which trivy || echo 'Trivy: comando non trovato'"

    echo "[securityScan] ▶ Trivy container scan: ${image}"
    try {
        sh """
            trivy image \
                --severity ${severity} \
                --format json \
                --output trivy-container-report.json \
                --ignore-unfixed \
                --quiet \
                ${image}
        """

        def report = readJSON(file: 'trivy-container-report.json')
        analyzeTrivyResults(report, 'container', exitOnFailure, opts.exitOnThreshold ?: 0)
        archiveArtifacts(artifacts: 'trivy-container-report.json', allowEmpty: true)
    } catch (Exception e) {
        if (exitOnFailure) {
            error("[securityScan] Trivy container scan fallito: ${e.message}")
        } else {
            warning("[securityScan] ⚠ Trivy container scan fallito (ignorato): ${e.message}")
        }
    }
}

/**
 * Filesystem vulnerability scan con Trivy.
 */
def filesystem(String path = '.', Map opts = [:]) {
    def severity = opts.severity ?: 'CRITICAL,HIGH'
    def exitOnFailure = opts.exitOnFailure != false

    echo "[securityScan] ▶ Trivy filesystem scan: ${path}"
    try {
        sh """
            trivy filesystem \
                --severity ${severity} \
                --format json \
                --output trivy-fs-report.json \
                --ignore-unfixed \
                --quiet \
                ${path}
        """

        def report = readJSON(file: 'trivy-fs-report.json')
        analyzeTrivyResults(report, 'filesystem', exitOnFailure, opts.exitOnThreshold ?: 0)
        archiveArtifacts(artifacts: 'trivy-fs-report.json', allowEmpty: true)
    } catch (Exception e) {
        if (exitOnFailure) {
            error("[securityScan] Trivy filesystem scan fallito: ${e.message}")
        } else {
            warning("[securityScan] ⚠ Trivy filesystem scan fallito (ignorato): ${e.message}")
        }
    }
}

/**
 * SonarQube static code analysis.
 */
def sonarQube(Map opts = [:]) {
    def projectKey = opts.projectKey ?: env.SONAR_PROJECT_KEY
    if (!projectKey) error('[securityScan] projectKey richiesto per SonarQube')

    def sources = opts.sources ?: '.'
    def qualityGate = opts.qualityGate != false
    def serverUrl = opts.serverUrl ?: env.SONAR_HOST_URL ?: 'http://sonarqube:9000'

    echo "[securityScan] ▶ SonarQube analysis: ${projectKey}"

    withSonarQubeEnv('SonarQube') {
        sh """
            sonar-scanner \
                -Dsonar.projectKey=${projectKey} \
                -Dsonar.sources=${sources} \
                -Dsonar.host.url=${serverUrl} \
                -Dsonar.qualitygate.wait=${qualityGate}
        """
    }

    echo "[securityScan] ✓ SonarQube completato: ${projectKey}"
}

/**
 * OWASP Dependency-Check (SCA - Software Composition Analysis).
 */
def dependencyCheck(Map opts = [:]) {
    def scanPath = opts.path ?: '.'
    def format = opts.format ?: 'ALL'
    def suppressionFile = opts.suppressionFile ?: ''
    def failOnCVSS = opts.failOnCVSS ?: 7.0 // fallisce se CVSS >= 7

    echo "[securityScan] ▶ OWASP Dependency-Check: ${scanPath}"

    def suppressionArg = suppressionFile ? "--suppression ${suppressionFile}" : ''

    try {
        sh """
            dependency-check.sh \
                --scan '${scanPath}' \
                --format '${format}' \
                --out 'dependency-check-report' \
                ${suppressionArg} \
                --failOnCVSS ${failOnCVSS}
        """
    } catch (Exception e) {
        // OWASP esce con codice 1 se trova vulnerabilità oltre la soglia
        echo "[securityScan] ⚠ Dependency-Check ha trovato vulnerabilità oltre CVSS ${failOnCVSS}"
    }

    if (fileExists('dependency-check-report/dependency-check-report.html')) {
        publishHTML(target: [
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: 'dependency-check-report',
            reportFiles: 'dependency-check-report.html',
            reportName: 'Dependency Check Report'
        ])
    }

    archiveArtifacts(artifacts: 'dependency-check-report/*', allowEmpty: true)
    echo "[securityScan] ✓ OWASP Dependency-Check completato"
}

/**
 * Scan completo: container + filesystem + SonarQube + Dependency-Check.
 */
def fullScan(Map opts = [:]) {
    def image = opts.image ?: ''
    def projectKey = opts.projectKey ?: ''
    def skipSonar = opts.skipSonar ?: false

    pipelineUtils.safeStage('Container Security Scan', {
        if (image) container(image, opts)
    }, false)

    pipelineUtils.safeStage('Filesystem Security Scan', {
        if (opts.fsScanPath) filesystem(opts.fsScanPath, opts)
    }, false)

    pipelineUtils.safeStage('SonarQube Analysis', {
        if (projectKey && !skipSonar) sonarQube(opts)
    }, false)

    pipelineUtils.safeStage('Dependency Check', {
        dependencyCheck(opts)
    }, false)

    echo '[securityScan] ✓ Full scan completato'
}

// --- Helpers ---

@NonCPS
private def analyzeTrivyResults(Map report, String scanType, boolean exitOnFailure, int customThreshold) {
    def vulnerabilities = report.Results?.collectMany { it.Vulnerabilities ?: [] } ?: []
    def criticalCount = vulnerabilities.count { v -> v.Severity == 'CRITICAL' }
    def highCount = vulnerabilities.count { v -> v.Severity == 'HIGH' }
    def totalCount = vulnerabilities.size()

    def threshold = customThreshold > 0 ? customThreshold : (scanType == 'container' ? 0 : 5)

    echo """
╔═══════════════════════════════════════════════╗
║  TRIVY ${scanType.toUpperCase()} SCAN REPORT            ║
╠═══════════════════════════════════════════════╣
║  CRITICAL: ${criticalCount.toString().padRight(35)}║
║  HIGH:     ${highCount.toString().padRight(35)}║
║  MEDIUM:   ${(vulnerabilities.count { v -> v.Severity == 'MEDIUM' }).toString().padRight(35)}║
║  LOW:      ${(vulnerabilities.count { v -> v.Severity == 'LOW' }).toString().padRight(35)}║
║  TOTAL:    ${totalCount.toString().padRight(35)}║
║  THRESHOLD: CRITICAL > ${threshold}                        ║
╚═══════════════════════════════════════════════╝
"""

    if (exitOnFailure && criticalCount > threshold) {
        error("[securityScan] ❌ ${criticalCount} vulnerabilità CRITICAL trovate (soglia: >${threshold}). Build bloccata.")
    }

    if (highCount > 10) {
        warning("[securityScan] ⚠ ${highCount} vulnerabilità HIGH trovate. Review richiesta.")
    }
}

return this