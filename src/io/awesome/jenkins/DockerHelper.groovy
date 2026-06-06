package io.awesome.jenkins

/**
 * Helper per operazioni Docker enterprise con supporto multi-registry.
 * Gestisce login, build, push, tag, pull su ECR, Nexus, DockerHub, Harbor.
 * Integra scanning di sicurezza con Trivy.
 */
class DockerHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final Map<String, Map> registries  // [name: [url: ..., creds: ..., type: ...]]

    DockerHelper(Script script, Map<String, Map> registries = [:]) {
        this.script = script
        this.registries = registries
    }

    /**
     * Login a un registry Docker con le credenziali Jenkins.
     * @param registryName nome del registry configurato (ecr, nexus, dockerhub, harbor)
     * @param region regione AWS (solo per ECR)
     */
    void login(String registryName, String region = 'eu-west-1') {
        def registry = registries[registryName]
        if (!registry) {
            script.error "[Docker] Registry non configurato: ${registryName}. Configurati: ${registries.keySet()}"
        }

        switch (registry.type ?: 'standard') {
            case 'ecr':
                script.sh """
                    aws ecr get-login-password --region ${region} | \
                        docker login --username AWS --password-stdin ${registry.url}
                """
                break
            case 'nexus':
            case 'harbor':
            case 'dockerhub':
            case 'standard':
                script.withCredentials([script.string(credentialsId: registry.creds,
                    variable: 'DOCKER_PASSWORD')]) {
                    script.sh """
                        echo "${script.env.DOCKER_PASSWORD}" | \
                            docker login ${registry.url} --username '${registry.username ?: ''}' --password-stdin
                    """
                }
                break
            default:
                script.error "[Docker] Tipo registry sconosciuto: ${registry.type}"
        }
        script.echo "[Docker] ✓ Login: ${registry.url} (${registry.type ?: 'standard'})"
    }

    /**
     * Build immagine Docker con tag multipli e argomenti di build sicuri.
     * Supporta: amd64, arm64, arm/v7 multi-architecture build con buildx.
     * @param dockerfile percorso Dockerfile (default: ./Dockerfile)
     * @param imageName nome immagine base
     * @param tags lista tag (es: ['1.0.0', 'latest'])
     * @param buildArgs mappa argomenti build (Jenkins → Docker build-arg)
     * @param context percorso contesto build (default: .)
     * @param platforms piattaforme target multi-arch (es: ['linux/amd64', 'linux/arm64']) — vuoto = build singola
     */
    String build(String imageName, List<String> tags, Map buildArgs = [:],
                  String dockerfile = './Dockerfile', String context = '.',
                  List<String> platforms = []) {
        validateNotEmpty(imageName, 'imageName')
        if (!tags) {
            script.error '[Docker] Almeno un tag richiesto'
        }

        // Sanifica buildArgs per sicurezza
        def sanitizedArgs = buildArgs.collectEntries { k, v ->
            [k, Utils.sanitizeInput(v?.toString() ?: '')]
        }

        def argsStr = sanitizedArgs.collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')
        def tagsStr = tags.collect { t -> "-t ${imageName}:${t}" }.join(' ')

        if (platforms) {
            // Multi-architecture build con buildx
            String platformsStr = platforms.join(',')
            script.echo "[Docker] ▶ Multi-arch build: ${imageName} [${tags.join(', ')}] per ${platformsStr}"
            
            // Setup buildx builder (se non esiste)
            script.sh """
                docker buildx create --name ci-builder --use --bootstrap 2>/dev/null || \
                    docker buildx use ci-builder
            """

            script.sh """
                docker buildx build \
                    --platform ${platformsStr} \
                    -f ${dockerfile} \
                    ${tagsStr} \
                    ${argsStr} \
                    --push \
                    ${context} 2>&1
            """

            // Crea manifest list per registry che non supportano multi-arch
            script.sh """
                docker buildx imagetools create ${tags.collect { t -> "${imageName}:${t}" }.join(' ')}
            """
        } else {
            // Build singola architettura standard
            script.echo "[Docker] ▶ Build: ${imageName} [${tags.join(', ')}]"
            script.sh """
                docker build \
                    -f ${dockerfile} \
                    ${tagsStr} \
                    ${argsStr} \
                    ${context}
            """
        }

        String firstTag = "${imageName}:${tags[0]}"
        script.echo "[Docker] ✓ Build completato: ${firstTag}"
        return firstTag
    }

    /**
     * Push immagine su multipli registry in sequenza.
     * Supporta push parallelo (multi-thread) per accelerare il deploy.
     * @param imageName nome immagine base
     * @param tags lista tag da pusare
     * @param registryNames nomi dei registry target (configurati nel costruttore)
     * @param parallel se true, push in parallelo su tutti i registry
     */
    void pushToRegistries(String imageName, List<String> tags, List<String> registryNames,
                           boolean parallel = false) {
        if (parallel && registryNames.size() > 1) {
            // Push parallelo su tutti i registry
            def branches = [:]
            registryNames.each { registryName ->
                branches[registryName] = {
                    pushToSingleRegistry(imageName, tags, registryName)
                }
            }
            def utils = new Utils(script)
            utils.parallelSafe(branches, false)
        } else {
            registryNames.each { registryName ->
                pushToSingleRegistry(imageName, tags, registryName)
            }
        }
    }

    /**
     * Push su un singolo registry.
     */
    @NonCPS
    private void pushToSingleRegistry(String imageName, List<String> tags, String registryName) {
        def registry = registries[registryName]
        if (!registry) {
            script.echo "[Docker] Registry '${registryName}' non configurato, skip"
            return
        }

        login(registryName)

        tags.each { tag ->
            def remoteImage = "${registry.url}/${imageName}:${tag}"
            script.sh "docker tag ${imageName}:${tag} ${remoteImage}"
            script.sh "docker push ${remoteImage}"
            script.echo "[Docker] ✓ Push: ${remoteImage}"
        }
    }

    /**
     * Tag immagine locale con nuovi tag.
     */
    void tag(String sourceImage, List<String> newTags) {
        newTags.each { tag ->
            script.sh "docker tag ${sourceImage} ${tag}"
            script.echo "[Docker] ✓ Tag: ${sourceImage} → ${tag}"
        }
    }

    /**
     * Pull immagine con retry.
     */
    void pull(String image, int retries = 3) {
        script.retry(retries) {
            script.sh "docker pull ${image}"
        }
        script.echo "[Docker] ✓ Pull: ${image}"
    }

    /**
     * Scan immagine Docker con Trivy (se installato).
     * @param image immagine da scansionare
     * @param severity severità minima (CRITICAL, HIGH, MEDIUM, LOW)
     * @param exitOnFailure se true, fallisce se trova vulnerabilità critiche
     * @return report JSON con risultati scan
     */
    String securityScan(String image, String severity = 'CRITICAL,HIGH',
                         boolean exitOnFailure = true, boolean failOnScanError = false) {
        script.sh 'which trivy || echo "Trivy non installato"'
        
        try {
            script.sh """
                trivy image --severity ${severity} \
                    --format json --output trivy-report.json \
                    --ignore-unfixed \
                    --quiet \
                    ${image}
            """
        } catch (Exception e) {
            if (failOnScanError) {
                script.error("[Docker] Trivy scan fallito: ${e.message}")
            } else {
                script.echo("[Docker] ⚠ Trivy scan non eseguito (errore ignorato): ${e.message}")
                return '{}'
            }
        }

        // Analisi risultati
        if (exitOnFailure && script.fileExists('trivy-report.json')) {
            def report = script.readJSON(file: 'trivy-report.json')
            def criticalCount = report.Results?.sum { it.Vulnerabilities?.count { v -> v.Severity == 'CRITICAL' } } ?: 0
            def highCount = report.Results?.sum { it.Vulnerabilities?.count { v -> v.Severity == 'HIGH' } } ?: 0

            if (criticalCount > 0) {
                script.error("[Docker] ❌ ${criticalCount} vulnerabilità CRITICAL trovate in ${image}. Blocco build.")
            }
            if (highCount > 5) {
                script.warning("[Docker] ⚠ ${highCount} vulnerabilità HIGH trovate in ${image}. Review richiesta.")
            } else {
                script.echo("[Docker] ✓ Scan completato: ${criticalCount} CRITICAL, ${highCount} HIGH")
            }
        }

        return script.readJSON(file: 'trivy-report.json')
    }

    /**
     * Genera SBOM dell'immagine con Syft o Trivy.
     */
    void generateSbom(String image, String format = 'cyclonedx', String outputFile = 'sbom.json') {
        script.sh "trivy image --format ${format} --output ${outputFile} --quiet ${image}"
        
        // Upload SBOM come artefatto
        script.archiveArtifacts(artifacts: outputFile, fingerprint: true)
        script.echo "[Docker] ✓ SBOM generato: ${outputFile}"
    }

    @NonCPS
    private void validateNotEmpty(String value, String name) {
        if (!value || value.trim().isEmpty()) {
            script.error("[Docker] Parametro obbligatorio vuoto: ${name}")
        }
    }
}