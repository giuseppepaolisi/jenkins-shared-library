/**
 * terraformPipeline.groovy — Pipeline Terraform enterprise completa.
 *
 * Utilizzo:
 *   // Pipeline completa: validate → init → plan → (approve) → apply
 *   terraformPipeline.run(
 *       workingDir: './infra',
 *       workspace: env.BRANCH_NAME ?: 'default',
 *       varFile: 'environments/staging.tfvars',
 *       costEstimate: true,
 *       autoApply: (env.BRANCH_NAME != 'main')
 *   )
 *
 *   // Step singoli
 *   terraformPipeline.validate('./infra')
 *   terraformPipeline.plan('./infra', varFile: 'prod.tfvars')
 *   terraformPipeline.apply('./infra')
 *   terraformPipeline.destroy('./infra')
 *
 *   // Cost estimation
 *   terraformPipeline.estimateCost('./infra')
 *
 *   // Drift detection
 *   terraformPipeline.detectDrift('./infra')
 *
 *   // Policy check
 *   terraformPipeline.opaPolicyCheck('./infra', 'policies/terraform')
 *
 * Configurazione via env:
 *   TF_BACKEND_CONFIG = 'backend-s3.hcl'   (file backend config)
 *   TF_VAR_FILE       = 'terraform.tfvars' (file variabili default)
 */
import io.awesome.jenkins.TerraformHelper

def run(Map opts = [:]) {
    def helper = createHelper(opts.workingDir ?: '.')
    helper.runPipeline(opts)
}

def validate(String workDir = '.') {
    def helper = createHelper(workDir)
    helper.validateCode()
}

def plan(String workDir = '.', Map opts = [:]) {
    def helper = createHelper(workDir)
    helper.init(opts)
    helper.plan(opts)
}

def apply(String workDir = '.', Map opts = [:]) {
    def helper = createHelper(workDir)
    helper.init(opts)
    helper.plan(opts)
    helper.apply(opts)
}

def destroy(String workDir = '.', Map opts = [:]) {
    def helper = createHelper(workDir)
    helper.init(opts)
    helper.destroy(opts)
}

def estimateCost(String workDir = '.', Map opts = [:]) {
    def helper = createHelper(workDir)
    helper.estimateCost(opts)
}

def detectDrift(String workDir = '.', Map opts = [:]) {
    def helper = createHelper(workDir)
    return helper.detectDrift(opts)
}

def opaPolicyCheck(String workDir = '.', String policyPath = 'policies/terraform', Map opts = [:]) {
    def helper = createHelper(workDir)
    helper.opaPolicyCheck(policyPath, opts)
}

def tflint(String workDir = '.') {
    def helper = createHelper(workDir)
    helper.tflint()
}

@NonCPS
private TerraformHelper createHelper(String workDir) {
    def backendConfig = env.TF_BACKEND_CONFIG ?: ''
    return new TerraformHelper(this, 'terraform', workDir, backendConfig)
}

return this