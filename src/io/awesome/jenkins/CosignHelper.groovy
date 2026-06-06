package io.awesome.jenkins

/**
 * Helper per firma crittografica container con Cosign (sigstore).
 * Garantisce supply chain security: firma, verifica, attestation, SBOM signing.
 */
class CosignHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String cosignBinary
    private final String keyRef // 'kms://...', 'azure-kms://...', o 'cosign.key'

    CosignHelper(Script script, String keyRef = '', String cosignBinary = 'cosign') {
        this.script = script
        this.keyRef = keyRef
        this.cosignBinary = cosignBinary
    }

    /**
     * Firma un'immagine Docker con Cosign (key pair o keyless).
     * @param image immagine da firmare (es: registry.azienda.local/myapp:1.0.0)
     * @param keyless se true, usa sigstore keyless (OIDC identity)
     * @param annotations annotazioni aggiuntive per l'attestazione
     */
    def sign(String image, boolean keyless = false, Map<String, String> annotations = [:]) {
        script.echo "[Cosign] ▶ Firma immagine: ${image}"

        script.retry(3) {
            def cmd = "${cosignBinary} sign"
            def annotArgs = annotations.collect { k, v -> "-a '${k}=${v}'" }.join(' ')

            if (keyless) {
                // Keyless signing (OIDC with Sigstore)
                cmd += " ${annotArgs} ${image}"
            } else if (keyRef.startsWith('kms://') || keyRef.startsWith('azure-kms://') ||
                       keyRef.startsWith('awskms://') || keyRef.startsWith('gcpkms://')) {
                // Cloud KMS
                cmd += " --key ${keyRef} ${annotArgs} ${image}"
            } else {
                // Local key pair
                script.withCredentials([script.string(credentialsId: 'cosign-password', variable: 'COSIGN_PASSWORD')]) {
                    script.withCredentials([script.file(credentialsId: 'cosign-key', variable: 'COSIGN_KEY')]) {
                        cmd = "COSIGN_PASSWORD='${script.env.COSIGN_PASSWORD}' ${cosignBinary} sign --key '${script.env.COSIGN_KEY}' ${annotArgs} ${image}"
                    }
                }
            }
            script.sh cmd
        }

        script.echo "[Cosign] ✓ Immagine firmata: ${image}"
    }

    /**
     * Verifica la firma di un'immagine.
     */
    def verify(String image, boolean keyless = false, String identity = '') {
        script.echo "[Cosign] ▶ Verifica firma: ${image}"

        def cmd = "${cosignBinary} verify"
        if (keyless) {
            cmd += " ${image}"
            if (identity) cmd += " --identity ${identity}"
        } else if (keyRef) {
            cmd += " --key ${keyRef} ${image}"
        } else {
            script.withCredentials([script.string(credentialsId: 'cosign-password', variable: 'COSIGN_PASSWORD')]) {
                script.withCredentials([script.file(credentialsId: 'cosign-key', variable: 'COSIGN_KEY')]) {
                    cmd = "COSIGN_PASSWORD='${script.env.COSIGN_PASSWORD}' ${cosignBinary} verify --key '${script.env.COSIGN_KEY}' ${image}"
                }
            }
        }

        def result = script.sh(script: cmd, returnStdout: true)
        script.echo "[Cosign] ✓ Firma verificata: ${image}"

        // Ottieni dettagli firma
        def payload = script.sh(
            script: "${cosignBinary} verify-attestation ${image} 2>/dev/null || echo 'No attestation'",
            returnStdout: true
        )
        return [result: result, payload: payload]
    }

    /**
     * Genera e firma un'attestazione (in-toto attestation).
     * @param image immagine
     * @param predicate tipo attestazione (custom, spdx, cyclonedx, slsaprovenance)
     * @param predicatePath percorso file JSON/SPDX/CycloneDX
     */
    def attest(String image, String predicateType = 'cyclonedx', String predicatePath = 'sbom.json') {
        script.echo "[Cosign] ▶ Attestazione: ${image} con ${predicatePath}"

        script.retry(3) {
            def cmd = "${cosignBinary} attest"

            if (keyRef) {
                cmd += " --key ${keyRef}"
            } else {
                script.withCredentials([script.string(credentialsId: 'cosign-password', variable: 'COSIGN_PASSWORD')]) {
                    script.withCredentials([script.file(credentialsId: 'cosign-key', variable: 'COSIGN_KEY')]) {
                        cmd = "COSIGN_PASSWORD='${script.env.COSIGN_PASSWORD}' ${cosignBinary} attest --key '${script.env.COSIGN_KEY}'"
                    }
                }
            }

            cmd += " --predicate ${predicatePath} --type ${predicateType} ${image}"
            script.sh cmd
        }

        script.echo "[Cosign] ✓ Attestazione creata per: ${image}"
    }

    /**
     * Cleanup vecchie firme per un'immagine.
     */
    def clean(String image) {
        script.echo "[Cosign] ▶ Cleanup firme: ${image}"
        script.sh "${cosignBinary} clean ${image}"
    }

    /**
     * Verifica supply chain: firma + attestazione SBOM + provenienza.
     */
    def verifySupplyChain(String image, boolean keyless = false) {
        Map results = [:]

        results.verify = verify(image, keyless)

        try {
            def attResult = script.sh(
                script: "${cosignBinary} verify-attestation ${image} 2>&1",
                returnStdout: true
            )
            results.attestation = attResult
            script.echo "[Cosign] ✓ Attestazione verificata"
        } catch (Exception e) {
            results.attestation = "Nessuna attestazione trovata"
            script.echo "[Cosign] ⚠ Nessuna attestazione: ${e.message}"
        }

        return results
    }

    /**
     * Genera chiave Cosign (se non esiste già nel vault).
     */
    def generateKey() {
        script.echo "[Cosign] ▶ Generazione chiave Cosign..."
        script.withCredentials([script.string(credentialsId: 'cosign-password', variable: 'COSIGN_PASSWORD')]) {
            script.sh """
                COSIGN_PASSWORD='${script.env.COSIGN_PASSWORD}' \
                ${cosignBinary} generate-key-pair \
                    --output-key-prefix cosign
            """
            script.echo "[Cosign] ✓ Chiave generata: cosign.key / cosign.pub"
            // TODO: Caricare in Vault/AWS Secrets Manager
        }
    }
}