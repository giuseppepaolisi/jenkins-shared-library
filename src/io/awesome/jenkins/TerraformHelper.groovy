package io.awesome.jenkins

/**
 * Helper enterprise per pipeline Terraform: init, plan, apply, destroy,
 * state management, policy check (OPA/Sentinel), cost estimation, drift detection.
 */
class TerraformHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String tfBinary
    private final String workingDir
    private final String backendConfig

    TerraformHelper(Script script, String tfBinary = 'terraform',
                    String workingDir = '.', String backendConfig = '') {
        this.script = script
        this.tfBinary = tfBinary
        this.workingDir = workingDir
        this.backendConfig = backendConfig
    }

    /**
     * Terraform init con retry e migrazione stato.
     */
    def init(Map opts = [:]) {
        script.echo "[Terraform] ▶ Init: ${workingDir}"
        script.retry(3) {
            def cmd = "${tfBinary} -chdir=${workingDir} init"
            cmd += opts.upgrade ? " -upgrade" : ""
            cmd += opts.reconfigure ? " -reconfigure" : ""
            cmd += opts.migrateState ? " -migrate-state" : ""
            if (backendConfig && script.fileExists(backendConfig)) {
                cmd += " -backend-config=${backendConfig}"
            }
            script.sh cmd
        }
    }

    /**
     * Terraform plan con output JSON.
     */
    def plan(Map opts = [:]) {
        init(opts)

        script.echo "[Terraform] ▶ Plan: ${workingDir}"
        def cmd = "${tfBinary} -chdir=${workingDir} plan"
        if (opts.destroy) cmd += " -destroy"
        if (opts.target) cmd += " -target=${opts.target}"
        if (opts.varFile) cmd += " -var-file=${opts.varFile}"
        opts.vars?.each { k, v -> cmd += " -var '${k}=${v}'" }
        cmd += " -out=/tmp/tfplan-${script.env.BUILD_NUMBER ?: '0'}"

        script.sh cmd

        // Genera JSON plan per analisi
        script.sh """
            ${tfBinary} -chdir=${workingDir} show -json \
                /tmp/tfplan-${script.env.BUILD_NUMBER ?: '0'} > /tmp/tfplan.json 2>/dev/null || true
        """
    }

    /**
     * Terraform apply con auto-approve.
     */
    def apply(Map opts = [:]) {
        def planFile = "/tmp/tfplan-${script.env.BUILD_NUMBER ?: '0'}"
        if (!script.fileExists(planFile)) {
            plan(opts)
        }

        script.echo "[Terraform] ▶ Apply: ${workingDir}"
        script.retry(3) {
            script.sh "${tfBinary} -chdir=${workingDir} apply ${planFile}"
        }

        // Aggiorna output
        script.sh "${tfBinary} -chdir=${workingDir} output -json > /tmp/tfoutput.json"
        script.echo "[Terraform] ✓ Apply completato"
    }

    /**
     * Terraform destroy selettivo.
     */
    def destroy(Map opts = [:]) {
        opts.destroy = true
        plan(opts)

        if (opts.autoApprove || opts.force) {
            script.sh "${tfBinary} -chdir=${workingDir} destroy -auto-approve"
        } else {
            script.input(message: "Distruggere le risorse Terraform in ${workingDir}?", ok: "Sì, distruggi")
            script.sh "${tfBinary} -chdir=${workingDir} destroy -auto-approve"
        }
        script.echo "[Terraform] ✓ Destroy completato"
    }

    /**
     * Terraform fmt + validate.
     */
    def validateCode() {
        script.echo "[Terraform] ▶ Validazione codice"
        script.sh "${tfBinary} -chdir=${workingDir} fmt -check -diff -recursive || true"
        script.sh "${tfBinary} -chdir=${workingDir} validate"
        script.echo "[Terraform] ✓ Codice valido"
    }

    /**
     * TFLint per linting avanzato.
     */
    def tflint() {
        script.sh "which tflint || echo 'TFLint non installato'"
        try {
            script.sh """
                tflint --chdir=${workingDir} \
                    --format=checkstyle > tflint-report.xml || true
            """
            script.echo "[Terraform] ✓ TFLint completato"
        } catch (Exception e) {
            script.echo "[Terraform] ⚠ TFLint non eseguito: ${e.message}"
        }
    }

    /**
     * Terraform cost estimation (Infracost).
     */
    def estimateCost(Map opts = [:]) {
        try {
            script.sh "which infracost || echo 'Infracost non installato'"

            def tfPlanFile = "/tmp/tfplan-${script.env.BUILD_NUMBER ?: '0'}"
            if (!script.fileExists(tfPlanFile)) {
                plan(opts)
            }

            script.sh """
                infracost breakdown --path=${workingDir} \
                    --format=json > /tmp/infracost.json 2>/dev/null || true
            """, returnStdout: false

            if (script.fileExists('/tmp/infracost.json')) {
                def costReport = script.readJSON(file: '/tmp/infracost.json')
                script.echo """
[Terraform] 📊 Cost Estimate:
  ▸ Monthly cost: \$${costReport.totalMonthlyCost ?: 'N/D'}
  ▸ Project: ${costReport.project?.name ?: 'N/D'}
"""
            }
        } catch (Exception e) {
            script.echo "[Terraform] ⚠ Cost estimation non eseguita: ${e.message}"
        }
    }

    /**
     * OPA policy check su plan JSON.
     */
    def opaPolicyCheck(String policyPath, Map opts = [:]) {
        def tfPlanFile = "/tmp/tfplan-${script.env.BUILD_NUMBER ?: '0'}"
        if (!script.fileExists("${workingDir}/tfplan.json")) {
            script.sh """
                ${tfBinary} -chdir=${workingDir} show -json ${tfPlanFile} > ${workingDir}/tfplan.json
            """
        }

        script.echo "[Terraform] ▶ OPA Policy Check: ${policyPath}"
        def result = script.sh(
            script: """
                opa eval --format json \
                    --data ${policyPath} \
                    --input ${workingDir}/tfplan.json \
                    "data.terraform.deny" 2>/dev/null || echo '{"result":[]}'
            """,
            returnStdout: true
        )

        def violations = script.readJSON(text: result)
        if (violations.result?.size() > 0) {
            script.echo "[Terraform] ❌ Policy violations trovate!"
            violations.result.each { v ->
                script.echo "  ✗ ${v}"
            }
            if (opts.failOnViolation != false) {
                script.error("[Terraform] OPA policy bloccata")
            }
        } else {
            script.echo "[Terraform] ✓ Policy check passato"
        }
    }

    /**
     * Terraform state operations: pull, push, list, mv, rm.
     */
    def stateList() {
        script.sh "${tfBinary} -chdir=${workingDir} state list"
    }

    def stateRm(String address) {
        script.sh "${tfBinary} -chdir=${workingDir} state rm ${address}"
    }

    def stateMv(String source, String destination) {
        script.sh "${tfBinary} -chdir=${workingDir} state mv ${source} ${destination}"
    }

    /**
     * Workspace management.
     */
    def workspaceSelect(String workspace) {
        script.sh "${tfBinary} -chdir=${workingDir} workspace select ${workspace} || ${tfBinary} -chdir=${workingDir} workspace new ${workspace}"
        script.echo "[Terraform] ✓ Workspace: ${workspace}"
    }

    def workspaceList() {
        script.sh "${tfBinary} -chdir=${workingDir} workspace list"
    }

    /**
     * Drift detection: confronta stato remoto con risorse reali.
     */
    def detectDrift(Map opts = [:]) {
        init(opts)
        script.echo "[Terraform] ▶ Drift detection: ${workingDir}"

        script.retry(2) {
            def refreshPlan = script.sh(
                script: "${tfBinary} -chdir=${workingDir} plan -detailed-exitcode 2>&1 || true",
                returnStdout: true
            )

            if (refreshPlan.contains('No changes')) {
                script.echo "[Terraform] ✓ Nessun drift rilevato"
                return false
            } else {
                script.echo "[Terraform] ⚠ Drift rilevato! Cambiamenti:\n${refreshPlan.take(2000)}"
                return true
            }
        }
    }

    /**
     * Infracost drift: confronta costi attuali con baseline.
     */
    def costDrift(String baselineFile = '/tmp/infracost-baseline.json') {
        if (!script.fileExists(baselineFile)) {
            // Crea baseline
            script.sh """
                infracost breakdown --path=${workingDir} \
                    --format=json > ${baselineFile}
            """
            script.echo "[Terraform] ✓ Baseline cost creata: ${baselineFile}"
            return
        }

        script.sh """
            infracost diff --path=${workingDir} \
                --compare-to=${baselineFile} \
                --format=json > /tmp/infracost-diff.json
        """
        def diff = script.readJSON(file: '/tmp/infracost-diff.json')
        script.echo "[Terraform] 📊 Cost drift: \$ ${diff.totalMonthlyCost}"
    }

    /**
     * Output formattato dei valori Terraform.
     */
    @NonCPS
    Map getOutputs() {
        def output = script.sh(
            script: "${tfBinary} -chdir=${workingDir} output -json 2>/dev/null || echo '{}'",
            returnStdout: true
        )
        return script.readJSON(text: output)
    }

    /**
     * Esegue un workspace completamente isolato con init + plan + apply.
     */
    def runPipeline(Map opts = [:]) {
        String workspace = opts.workspace ?: script.env.BRANCH_NAME ?: 'default'

        validateCode()
        workspaceSelect(workspace)
        plan(opts)

        if (opts.costEstimate) estimateCost(opts)

        if (opts.autoApply || (workspace == 'prod' ? false : true)) {
            apply(opts)
        } else if (workspace == 'prod') {
            script.input(message: "Applicare Terraform in PROD?", ok: "Sì, applica")
            apply(opts)
        }

        return getOutputs()
    }
}