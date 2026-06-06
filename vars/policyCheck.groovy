/**
 * policyCheck.groovy — Policy as Code enterprise: OPA + conftest.
 *
 * Utilizzo:
 *   // Valutazione OPA su file JSON/YAML
 *   policyCheck.evaluate('policies/k8s', 'deployment.yaml', 'data.kubernetes.deny')
 *
 *   // Conftest su Kubernetes manifest
 *   policyCheck.conftest('deployment.yaml', 'policies/k8s')
 *
 *   // Shortcut K8s
 *   policyCheck.k8s('deployment.yaml')
 *
 *   // Shortcut Docker
 *   policyCheck.docker('Dockerfile')
 *
 *   // Shortcut Terraform
 *   policyCheck.terraform('tfplan.json')
 */
import io.awesome.jenkins.OPAHelper

def evaluate(String policyPath, String inputPath, String query = 'data.main.deny',
              boolean failOnDeny = true) {
    def helper = new OPAHelper(this)
    return helper.evaluate(policyPath, inputPath, query, failOnDeny)
}

def conftest(String filePath, String policyPath = '', Map opts = [:]) {
    def helper = new OPAHelper(this)
    return helper.conftest(filePath, policyPath, opts)
}

def k8s(String manifestPath, String policyPath = 'policies/k8s') {
    def helper = new OPAHelper(this)
    return helper.evaluateK8sManifest(manifestPath, policyPath)
}

def docker(String dockerfilePath, String policyPath = 'policies/docker') {
    def helper = new OPAHelper(this)
    return helper.evaluateDockerfile(dockerfilePath, policyPath)
}

def terraform(String planPath, String policyPath = 'policies/terraform') {
    def helper = new OPAHelper(this)
    return helper.evaluateTerraform(planPath, policyPath)
}

def compliance(String framework, String inputPath, String policyPath = 'policies/compliance') {
    def helper = new OPAHelper(this)
    return helper.evaluateCompliance(framework, inputPath, policyPath)
}

return this