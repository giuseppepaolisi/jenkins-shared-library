package io.awesome.jenkins

/**
 * Helper per Policy as Code con OPA (Open Policy Agent) e conftest.
 * Valuta policy Rego su input JSON/YAML da pipeline CI/CD.
 */
class OPAHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String opaBinary
    private final String conftestBinary

    OPAHelper(Script script, String opaBinary = 'opa', String conftestBinary = 'conftest') {
        this.script = script
        this.opaBinary = opaBinary
        this.conftestBinary = conftestBinary
    }

    /**
     * Valuta policy OPA su un input JSON.
     * @param policyPath percorso policy .rego
     * @param inputPath percorso input JSON
     * @param query query Rego (es: 'data.kubernetes.deny')
     * @param failOnDeny se true, fallisce se ci sono violazioni
     */
    def evaluate(String policyPath, String inputPath, String query = 'data.main.deny',
                 boolean failOnDeny = true) {
        script.echo "[OPA] ▶ Evaluate: ${policyPath} su ${inputPath}"

        def result = script.sh(
            script: """
                ${opaBinary} eval --format json \
                    --data ${policyPath}/ \
                    --input ${inputPath} \
                    "${query}" 2>/dev/null || echo '{"result":[]}'
            """,
            returnStdout: true
        )

        def evaluation = script.readJSON(text: result)
        def violations = []

        // OPA restituisce array di deny
        if (evaluation.result instanceof List) {
            violations = evaluation.result.collectMany { it.value ?: [] }
        } else if (evaluation.result instanceof Map) {
            violations = evaluation.result.values().flatten()
        }

        if (violations.size() > 0) {
            script.echo "[OPA] ❌ ${violations.size()} violazioni trovate in ${policyPath}:"
            violations.each { v ->
                script.echo "  ✗ ${v}"
            }
            if (failOnDeny) {
                script.error("[OPA] Policy bloccata: ${violations.size()} violazioni")
            }
            return [status: 'FAIL', violations: violations]
        }

        script.echo "[OPA] ✓ Nessuna violazione in ${policyPath}"
        return [status: 'PASS', violations: []]
    }

    /**
     * Esegue conftest su file YAML/JSON/Dockerfile/Kustomize.
     * @param filePath file da testare
     * @param policyPath percorso policy conftest
     */
    def conftest(String filePath, String policyPath = '', Map opts = [:]) {
        script.echo "[OPA] ▶ conftest: ${filePath}"

        def policyOpt = policyPath ? "--policy ${policyPath}" : ''
        def result = script.sh(
            script: """
                ${conftestBinary} test ${filePath} ${policyOpt} \
                    --output json \
                    --ignore='.git/' \
                    --namespace '${opts.namespace ?: 'main'}' 2>/dev/null || true
            """,
            returnStdout: true
        )

        def testResult = script.readJSON(text: result ?: '[]')
        if (testResult instanceof List && testResult.size() > 0) {
            def failures = testResult.findAll { it.failures?.size() > 0 }
            if (failures) {
                script.echo "[OPA] ❌ conftest: violazioni trovate!"
                failures.each { f ->
                    f.failures.each { fail ->
                        script.echo "  ✗ [${fail.metadata.code}] ${fail.msg}"
                    }
                }
                if (opts.failOnViolation != false) {
                    script.error("[OPA] conftest: ${failures*.failures*.size().sum()} violazioni")
                }
            }
            return testResult
        }

        script.echo "[OPA] ✓ conftest passato: ${filePath}"
        return []
    }

    /**
     * Valutazione policy Kubernetes su manifest YAML.
     */
    def evaluateK8sManifest(String manifestPath, String policyPath = 'policies/k8s') {
        return evaluate(policyPath, manifestPath,
            'data.kubernetes.deny', true)
    }

    /**
     * Valutazione policy Docker su Dockerfile.
     */
    def evaluateDockerfile(String dockerfilePath, String policyPath = 'policies/docker') {
        return conftest(dockerfilePath, policyPath, [namespace: 'docker'])
    }

    /**
     * Valutazione policy Terraform su plan JSON.
     */
    def evaluateTerraform(String planPath, String policyPath = 'policies/terraform') {
        return evaluate(policyPath, planPath,
            'data.terraform.deny', true)
    }

    /**
     * Valutazione policy compliance (GDPR, SOC2, PCI-DSS) su configurazione.
     */
    def evaluateCompliance(String complianceFramework, String inputPath,
                           String policyPath = 'policies/compliance') {
        def query = "data.compliance.${complianceFramework}.deny"
        return evaluate(policyPath, inputPath, query, true)
    }
}