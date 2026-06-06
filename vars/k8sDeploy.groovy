/**
 * k8sDeploy.groovy — Deploy Kubernetes enterprise: Helm, kubectl, ArgoCD.
 *
 * Utilizzo:
 *   // Helm deploy
 *   k8sDeploy.helmDeploy('myapp', './charts/myapp', 'staging',
 *       valuesFiles: ['values.yaml', 'values-staging.yaml'],
 *       setValues: [image.tag: '1.0.0'],
 *       atomic: true)
 *
 *   // ArgoCD sync
 *   k8sDeploy.argocdSync('myapp-production')
 *
 *   // Canary deploy (Istio)
 *   k8sDeploy.canaryDeploy('myapp', 'staging', '1.1.0', 10, 'istio')
 *
 *   // Rollback
 *   k8sDeploy.helmRollback('myapp', 'prod', 5)
 *
 *   // Health check
 *   def health = k8sDeploy.checkClusterHealth()
 *
 * Configurazione via env:
 *   KUBECONFIG_CREDS = 'kubeconfig'  (Jenkins credential ID per kubeconfig)
 *   K8S_NAMESPACE    = 'default'
 */
import io.awesome.jenkins.KubernetesHelper

def helmDeploy(String releaseName, String chartPath, String namespace = '',
               Map opts = [:]) {
    def helper = createHelper()
    def ns = namespace ?: opts.namespace ?: env.K8S_NAMESPACE ?: 'default'
    helper.helmDeploy(releaseName, chartPath, ns,
        opts.valuesFiles ?: [],
        opts.setValues ?: [:],
        opts)
}

def helmRollback(String releaseName, String namespace = '', int revision = 0) {
    def helper = createHelper()
    def ns = namespace ?: env.K8S_NAMESPACE ?: 'default'
    helper.helmRollback(releaseName, ns, revision)
}

def helmTest(String releaseName, String namespace = '') {
    def helper = createHelper()
    helper.helmTest(releaseName, namespace ?: env.K8S_NAMESPACE ?: 'default')
}

def helmDiff(String releaseName, String chartPath, String namespace = '', Map opts = [:]) {
    def helper = createHelper()
    def ns = namespace ?: env.K8S_NAMESPACE ?: 'default'
    helper.helmDiff(releaseName, chartPath, ns, opts.valuesFiles ?: [], opts.setValues ?: [:])
}

def kubectlApply(String manifestPath, String namespace = '', Map opts = [:]) {
    def helper = createHelper()
    def ns = namespace ?: opts.namespace ?: env.K8S_NAMESPACE ?: 'default'
    helper.kubectlApply(manifestPath, ns, opts.validate != false, opts.prune ?: false, opts.pruneLabel ?: '')
}

def kubectlDelete(String kind, String name, String namespace = '') {
    def helper = createHelper()
    helper.kubectlDelete(kind, name, namespace ?: env.K8S_NAMESPACE ?: 'default')
}

def argocdSync(String appName, Map opts = [:]) {
    def helper = createHelper()
    helper.argocdSync(appName,
        opts.server ?: 'argocd.azienda.local',
        opts.prune != false,
        opts.dryRun ?: false,
        opts.revision ?: '',
        opts.wait != false)
}

def canaryDeploy(String serviceName, String namespace = '', String newVersion = '',
                  int weight = 10, String mesh = 'istio') {
    def helper = createHelper()
    helper.canaryDeploy(serviceName, namespace ?: env.K8S_NAMESPACE ?: 'default',
        newVersion, weight, mesh)
}

def blueGreenSwitch(String serviceName, String namespace = '', String label = 'green') {
    def helper = createHelper()
    helper.blueGreenSwitch(serviceName, namespace ?: env.K8S_NAMESPACE ?: 'default', label)
}

def checkClusterHealth() {
    def helper = createHelper()
    return helper.checkClusterHealth()
}

def detectDrift(String manifestPath, String namespace = '') {
    def helper = createHelper()
    return helper.detectDrift(manifestPath, namespace ?: env.K8S_NAMESPACE ?: 'default')
}

// Deploy pipeline completa: helm diff → approve → deploy → verify
def deployPipeline(String releaseName, String chartPath, String environment,
                   Map opts = [:]) {
    def ns = environment
    def helper = createHelper()

    pipelineUtils.safeStage("Helm Diff (${environment})", {
        def diff = helper.helmDiff(releaseName, chartPath, ns,
            opts.valuesFiles ?: [], opts.setValues ?: [:])
    }, false)

    if (environment == 'prod') {
        input(message: "Deployare ${releaseName} in PROD?", ok: "Sì, deploya")
    }

    pipelineUtils.safeStage("Helm Deploy (${environment})", {
        helper.helmDeploy(releaseName, chartPath, ns,
            opts.valuesFiles ?: [], opts.setValues ?: [:],
            opts + [atomic: true, timeout: opts.timeout ?: '10m'])
    })

    pipelineUtils.safeStage("Helm Test (${environment})", {
        helper.helmTest(releaseName, ns)
    }, false)
}

@NonCPS
private KubernetesHelper createHelper() {
    def kubeConfigId = env.KUBECONFIG_CREDS ?: 'kubeconfig'
    def ns = env.K8S_NAMESPACE ?: 'default'
    return new KubernetesHelper(this, kubeConfigId, ns)
}

return this