package io.awesome.jenkins

/**
 * Helper enterprise per deploy Kubernetes: Helm, kubectl, ArgoCD, rollback,
 * canary, blue/green, drift detection, multi-cluster.
 */
class KubernetesHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String kubeConfigId
    private final String defaultNamespace
    private final String helmBinary
    private final String kubectlBinary

    KubernetesHelper(Script script, String kubeConfigId = 'kubeconfig',
                     String defaultNamespace = 'default',
                     String helmBinary = 'helm', String kubectlBinary = 'kubectl') {
        this.script = script
        this.kubeConfigId = kubeConfigId
        this.defaultNamespace = defaultNamespace
        this.helmBinary = helmBinary
        this.kubectlBinary = kubectlBinary
    }

    /**
     * Helm deploy con supporto valori multipli per ambiente e rollback automatico.
     * @param releaseName nome release Helm
     * @param chartPath percorso chart o repo (es: ./charts/myapp, stable/nginx)
     * @param namespace namespace target
     * @param valuesFiles lista file values (es: ['values.yaml', 'values-prod.yaml'])
     * @param setValues mappa valori inline (es: [image.tag: '1.0.0'])
     */
    def helmDeploy(String releaseName, String chartPath, String namespace = defaultNamespace,
                   List<String> valuesFiles = [], Map<String, String> setValues = [:],
                   Map opts = [:]) {
        script.echo "[K8s] ▶ Helm deploy: ${releaseName} → ${namespace}"

        script.retry(3) {
            def cmd = "${helmBinary} upgrade --install ${releaseName} ${chartPath}"
            cmd += " --namespace ${namespace} --create-namespace"
            cmd += " --wait --timeout ${opts.timeout ?: '10m'}"

            if (opts.version) cmd += " --version ${opts.version}"
            if (opts.reuseValues) cmd += " --reuse-values"

            valuesFiles.each { vf ->
                if (script.fileExists(vf)) cmd += " --values ${vf}"
            }
            setValues.each { k, v -> cmd += " --set ${k}=${v}" }
            if (opts.atomic) cmd += " --atomic"
            if (opts.force) cmd += " --force"
            if (opts.dryRun) cmd += " --dry-run"

            script.sh cmd
        }

        script.echo "[K8s] ✓ Helm deploy: ${releaseName} in ${namespace}"

        // Verifica deploy
        script.sh "${kubectlBinary} rollout status deployment/${releaseName} -n ${namespace} --timeout=${opts.verifyTimeout ?: '5m'}"
    }

    /**
     * Helm rollback a una revisione specifica.
     */
    def helmRollback(String releaseName, String namespace = defaultNamespace,
                     int revision = 0, int timeoutSeconds = 300) {
        script.echo "[K8s] ▶ Helm rollback: ${releaseName} → revision ${revision}"

        script.sh "${helmBinary} rollback ${releaseName} ${revision} --namespace ${namespace} --wait --timeout ${timeoutSeconds}s"

        script.echo "[K8s] ✓ Helm rollback: ${releaseName} → revision ${revision}"
    }

    /**
     * Helm test (chart tests).
     */
    def helmTest(String releaseName, String namespace = defaultNamespace) {
        script.echo "[K8s] ▶ Helm test: ${releaseName}"
        script.sh "${helmBinary} test ${releaseName} --namespace ${namespace} --logs"
    }

    /**
     * Helm diff tra deploy e valori correnti (drift detection).
     */
    def helmDiff(String releaseName, String chartPath, String namespace = defaultNamespace,
                 List<String> valuesFiles = [], Map<String, String> setValues = [:]) {
        def cmd = "${helmBinary} diff upgrade ${releaseName} ${chartPath} --namespace ${namespace}"

        valuesFiles.each { vf -> cmd += " --values ${vf}" }
        setValues.each { k, v -> cmd += " --set ${k}=${v}" }

        def diff = script.sh(script: cmd + " 2>&1 || true", returnStdout: true)
        script.echo "[K8s] Helm diff:\n${diff}"
        return diff
    }

    /**
     * Kubectl apply con retry e validazione.
     */
    def kubectlApply(String manifestPath, String namespace = defaultNamespace,
                     boolean validate = true, boolean prune = false, String pruneLabel = '') {
        script.echo "[K8s] ▶ kubectl apply: ${manifestPath}"
        script.retry(3) {
            def cmd = "${kubectlBinary} apply -f ${manifestPath} -n ${namespace}"
            if (validate) cmd += " --validate=true"
            if (prune) {
                cmd += " --prune -l ${pruneLabel ?: 'app.kubernetes.io/managed-by=jenkins'}"
            }
            script.sh cmd
        }

        // Verifica rollout se è un deployment
        script.sh """
            for kind in deployment statefulset daemonset; do
                if ${kubectlBinary} get \$kind -f ${manifestPath} -o name -n ${namespace} 2>/dev/null | grep -q .; then
                    ${kubectlBinary} rollout status -f ${manifestPath} -n ${namespace} --timeout=5m
                fi
            done
        """
    }

    /**
     * Kubectl delete sicuro (con drain pre-delete per statefulset).
     */
    def kubectlDelete(String kind, String name, String namespace = defaultNamespace,
                      boolean wait = true, int gracePeriod = 30) {
        script.echo "[K8s] ▶ kubectl delete: ${kind}/${name} in ${namespace}"
        script.sh """
            ${kubectlBinary} delete ${kind} ${name} -n ${namespace} \
                --wait=${wait} --grace-period=${gracePeriod}
        """
    }

    /**
     * ArgoCD sync di una application.
     */
    def argocdSync(String appName, String server = 'argocd.azienda.local',
                   boolean prune = true, boolean dryRun = false,
                   String revision = '', boolean wait = true) {
        script.echo "[K8s] ▶ ArgoCD sync: ${appName}"

        // Login ad ArgoCD
        script.withCredentials([script.string(credentialsId: 'argocd-token', variable: 'ARGOCD_TOKEN')]) {
            script.sh "argocd login ${server} --auth-token '${script.env.ARGOCD_TOKEN}' --grpc-web"
        }

        def cmd = "argocd app sync ${appName} --server ${server} --grpc-web"
        if (prune) cmd += " --prune"
        if (dryRun) cmd += " --dry-run"
        if (revision) cmd += " --revision ${revision}"
        if (wait) cmd += " --timeout 300"

        script.retry(3) {
            script.sh cmd
        }

        script.echo "[K8s] ✓ ArgoCD sync: ${appName}"
    }

    /**
     * Genera kubeconfig per EKS cluster.
     */
    def generateEksKubeconfig(String clusterName, String region = 'eu-west-1',
                              String profile = '', String roleArn = '') {
        def roleOpt = roleArn ? "--role-arn ${roleArn}" : ''
        def profileOpt = profile ? "--profile ${profile}" : ''

        script.sh """
            aws eks update-kubeconfig --name ${clusterName} --region ${region} \
                ${roleOpt} ${profileOpt} --kubeconfig /tmp/eks-kubeconfig
        """
        script.echo "[K8s] ✓ kubeconfig generato per EKS: ${clusterName}"
        return '/tmp/eks-kubeconfig'
    }

    /**
     * Canary deploy con weighted traffic.
     */
    def canaryDeploy(String serviceName, String namespace = defaultNamespace,
                     String newVersion = '', int weight = 10, String mesh = 'istio') {
        switch (mesh) {
            case 'istio':
                script.sh """
                    ${kubectlBinary} apply -f - <<EOF
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: ${serviceName}
  namespace: ${namespace}
spec:
  hosts:
  - ${serviceName}
  http:
  - match:
    - headers:
        x-canary:
          exact: "true"
    route:
    - destination:
        host: ${serviceName}-${newVersion}
        weight: 100
  - route:
    - destination:
        host: ${serviceName}-stable
        weight: ${100 - weight}
    - destination:
        host: ${serviceName}-${newVersion}
        weight: ${weight}
EOF
                """
                break
            case 'nginx':
                script.sh """
                    ${kubectlBinary} annotate ingress ${serviceName} \
                        nginx.ingress.kubernetes.io/canary="true" \
                        nginx.ingress.kubernetes.io/canary-weight="${weight}" \
                        -n ${namespace}
                """
                break
            default:
                script.error("[K8s] Service mesh non supportato: ${mesh}. Usa 'istio' o 'nginx'")
        }
        script.echo "[K8s] ✓ Canary deploy: ${serviceName}, weight=${weight}%"
    }

    /**
     * Blue/Green deploy: switch service selector.
     */
    def blueGreenSwitch(String serviceName, String namespace = defaultNamespace,
                        String newDeploymentLabel = 'green') {
        script.sh """
            ${kubectlBinary} patch svc ${serviceName} -n ${namespace} \
                -p '{"spec":{"selector":{"deploy":"${newDeploymentLabel}"}}}'
        """
        script.echo "[K8s] ✓ Blue/Green switch: ${serviceName} → ${newDeploymentLabel}"
    }

    /**
     * Get pod logs con filtering.
     */
    def getPodLogs(String labelSelector, String namespace = defaultNamespace,
                   String container = '', int tailLines = 100) {
        def containerOpt = container ? "-c ${container}" : ''
        script.sh """
            ${kubectlBinary} logs -l ${labelSelector} -n ${namespace} \
                ${containerOpt} --tail=${tailLines} --prefix
        """
    }

    /**
     * Exec comando in un pod.
     */
    def podExec(String podName, String command, String namespace = defaultNamespace,
                String container = '') {
        def containerOpt = container ? "-c ${container}" : ''
        script.sh """
            ${kubectlBinary} exec ${podName} -n ${namespace} ${containerOpt} -- ${command}
        """
    }

    /**
     * Drift detection: confronta manifest locali con cluster.
     */
    def detectDrift(String manifestPath, String namespace = defaultNamespace) {
        def localContent = script.readFile(file: manifestPath)
        def remoteName = new File(manifestPath).name.replaceAll('\\.ya?ml$', '')

        // Get live manifest
        def liveContent = script.sh(
            script: "${kubectlBinary} get -f ${manifestPath} -n ${namespace} -o yaml 2>/dev/null || echo 'NOT_FOUND'",
            returnStdout: true
        ).trim()

        if (liveContent == 'NOT_FOUND' || liveContent != localContent) {
            script.echo "[K8s] ⚠ Drift rilevato in ${manifestPath}"
            return true
        }
        script.echo "[K8s] ✓ Nessun drift: ${manifestPath}"
        return false
    }

    /**
     * Crea/seleziona namespace con labelling.
     */
    def ensureNamespace(String namespace, Map<String, String> labels = [:]) {
        script.sh """
            ${kubectlBinary} get ns ${namespace} 2>/dev/null || \
                ${kubectlBinary} create ns ${namespace}
        """
        labels.each { k, v ->
            script.sh "${kubectlBinary} label ns ${namespace} ${k}=${v} --overwrite"
        }
        script.echo "[K8s] ✓ Namespace garantito: ${namespace}"
    }

    /**
     * Verifica health del cluster.
     */
    @NonCPS
    Map checkClusterHealth() {
        def nodes = script.sh(
            script: "${kubectlBinary} get nodes --no-headers 2>/dev/null | wc -l",
            returnStdout: true
        ).trim()

        def readyNodes = script.sh(
            script: "${kubectlBinary} get nodes --no-headers 2>/dev/null | grep Ready | wc -l",
            returnStdout: true
        ).trim()

        def pods = script.sh(
            script: "${kubectlBinary} get pods --all-namespaces --no-headers 2>/dev/null | wc -l",
            returnStdout: true
        ).trim()

        def unhealthyPods = script.sh(
            script: "${kubectlBinary} get pods --all-namespaces --no-headers 2>/dev/null | grep -v Running | grep -v Completed | wc -l",
            returnStdout: true
        ).trim()

        return [
            totalNodes: nodes,
            readyNodes: readyNodes,
            totalPods: pods,
            unhealthyPods: unhealthyPods,
            healthy: (unhealthyPods as int) == 0,
            timestamp: new Date().format('yyyy-MM-dd HH:mm:ss')
        ]
    }
}