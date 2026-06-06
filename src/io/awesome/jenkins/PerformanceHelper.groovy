package io.awesome.jenkins

/**
 * Helper per performance/load testing enterprise: k6, Gatling, JMeter.
 * Integra pipeline CI/CD con threshold, report generation, e comparison.
 */
class PerformanceHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String tool // 'k6', 'gatling', 'jmeter'

    PerformanceHelper(Script script, String tool = 'k6') {
        this.script = script
        this.tool = tool
    }

    /**
     * Esegue test di carico con k6.
     * @param scriptPath percorso script k6 (.js)
     * @param stages stages di carico (es: [[duration: '30s', target: 10], [duration: '1m', target: 50]])
     * @param thresholds soglie (es: ['http_req_duration': ['p(95)<500']])
     * @param envVars variabili d'ambiente per lo script
     */
    Map runK6(String scriptPath, List<Map> stages = [],
              Map<String, List<String>> thresholds = [:],
              Map<String, String> envVars = [:], Map opts = [:]) {
        script.echo "[Performance] ▶ k6 test: ${scriptPath}"

        // Genera opzioni inline k6
        def options = [
            stages: stages,
            thresholds: thresholds,
            vus: opts.vus ?: 1,
            duration: opts.duration ?: '30s'
        ]

        // Passa opzioni come environment variable JSON
        def envStr = envVars.collect { k, v -> "-e ${k}=${v}" }.join(' ')

        def result = script.sh(
            script: """
                ${envStr} k6 run \
                    --out json=/tmp/k6-report.json \
                    --out summary=/tmp/k6-summary.txt \
                    ${scriptPath} 2>&1 | tee /tmp/k6-output.log || true
            """,
            returnStdout: true
        )

        // Analizza risultati
        def report = [:]
        if (script.fileExists('/tmp/k6-summary.txt')) {
            report.raw = script.readFile(file: '/tmp/k6-summary.txt')
            script.echo "[Performance] k6 summary:\n${report.raw.take(2000)}"
        }

        // Verifica threshold
        if (thresholds && result.contains('thresholds on metrics have been crossed')) {
            script.echo "[Performance] ❌ Threshold superati!"
            if (opts.failOnThreshold != false) {
                script.error("[Performance] Test fallito: threshold superati")
            }
        }

        // Archivia report
        script.archiveArtifacts(artifacts: '/tmp/k6-report.json', allowEmpty: true)

        script.echo "[Performance] ✓ k6 test completato"
        return report
    }

    /**
     * Esegue test Gatling (Scala/Java).
     * @param simulationClass classe Gatling (es: simulations.LoadTest)
     * @param gatlingHome home directory Gatling
     */
    Map runGatling(String simulationClass, String gatlingHome = '',
                   Map opts = [:]) {
        def gatling = gatlingHome ?: script.env.GATLING_HOME ?: '/opt/gatling'
        script.echo "[Performance] ▶ Gatling: ${simulationClass}"

        script.sh """
            ${gatling}/bin/gatling.sh \
                -s ${simulationClass} \
                -rf /tmp/gatling-results \
                ${opts.simulations ? "-sf ${opts.simulations}" : ''} \
                ${opts.config ? "-config ${opts.config}" : ''} 2>&1 || true
        """

        // Trova report generato
        def reports = script.findFiles(glob: '/tmp/gatling-results/*/index.html')
        if (reports) {
            script.publishHTML(target: [
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                reportDir: reports[0].path.replace('/index.html', ''),
                reportFiles: 'index.html',
                reportName: 'Gatling Performance Report'
            ])
        }

        return [reports: reports*.path]
    }

    /**
     * Esegue test JMeter.
     * @param jmxPath percorso file test (.jmx)
     * @param props proprietà JMeter
     */
    Map runJMeter(String jmxPath, Map<String, String> props = [:], Map opts = [:]) {
        def jmeterHome = opts.jmeterHome ?: script.env.JMETER_HOME ?: '/opt/jmeter'
        script.echo "[Performance] ▶ JMeter: ${jmxPath}"

        def propArgs = props.collect { k, v -> "-J${k}=${v}" }.join(' ')
        def reportDir = '/tmp/jmeter-report'

        script.sh """
            ${jmeterHome}/bin/jmeter \
                -n -t ${jmxPath} \
                -l /tmp/jmeter-results.jtl \
                -e -o ${reportDir} \
                ${propArgs} \
                ${opts.threads ? "-Jthreads=${opts.threads}" : ''} \
                ${opts.duration ? "-Jduration=${opts.duration}" : ''} \
                2>&1 || true
        """

        if (script.fileExists("${reportDir}/index.html")) {
            script.publishHTML(target: [
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                reportDir: reportDir,
                reportFiles: 'index.html',
                reportName: 'JMeter Performance Report'
            ])
        }

        return [reportDir: reportDir]
    }

    /**
     * Confronta risultati performance con baseline.
     */
    @NonCPS
    def compareWithBaseline(String metricFile, String baselineFile = '') {
        def baseline = baselineFile ?: '/tmp/performance-baseline.json'

        if (!script.fileExists(baseline)) {
            // Prima esecuzione: crea baseline
            script.sh "cp ${metricFile} ${baseline}"
            script.echo "[Performance] ✓ Baseline creata: ${baseline}"
            return [status: 'BASELINE_CREATED']
        }

        def current = script.readJSON(file: metricFile)
        def base = script.readJSON(file: baseline)

        def diff = [:]
        current.each { key, value ->
            if (base[key] != null && (value instanceof Number)) {
                def change = ((value - base[key]) / base[key]) * 100
                diff[key] = [
                    current: value,
                    baseline: base[key],
                    changePct: Math.round(change * 100) / 100
                ]

                if (Math.abs(change) > 20) {
                    script.echo "[Performance] ⚠ ${key}: ${base[key]} → ${value} (${change.round(1)}%)"
                }
            }
        }

        return diff
    }
}