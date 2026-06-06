/**
 * cosignOps.groovy — Container image signing con Cosign (sigstore).
 *
 * Utilizzo:
 *   // Firma immagine con chiave Cosign
 *   cosignOps.sign('registry.azienda.local/myapp:1.0.0',
 *       annotations: [version: '1.0.0', env: 'prod'])
 *
 *   // Firma keyless (OIDC)
 *   cosignOps.sign('myapp:1.0.0', [keyless: true])
 *
 *   // Verifica firma
 *   cosignOps.verify('registry.azienda.local/myapp:1.0.0')
 *
 *   // Attestazione con SBOM
 *   cosignOps.attest('myapp:1.0.0', 'cyclonedx', 'sbom.json')
 *
 *   // Supply chain verification completa
 *   def result = cosignOps.verifySupplyChain('myapp:1.0.0')
 *
 * Configurazione via env:
 *   COSIGN_KEY_ID     = 'cosign-key'         (Jenkins credential ID per chiave privata)
 *   COSIGN_PASS_ID    = 'cosign-password'     (Jenkins credential ID per password chiave)
 *   COSIGN_PUB_KEY    = 'cosign.pub'          (percorso chiave pubblica per verify)
 */
import io.awesome.jenkins.CosignHelper

def sign(String image, Map opts = [:]) {
    def helper = new CosignHelper(this, resolveKeyRef())
    helper.sign(image, opts.keyless ?: false, opts.annotations ?: [:])
}

def verify(String image, Map opts = [:]) {
    def helper = new CosignHelper(this, resolveKeyRef())
    return helper.verify(image, opts.keyless ?: false, opts.identity ?: '')
}

def attest(String image, String predicateType = 'cyclonedx', String predicatePath = 'sbom.json') {
    def helper = new CosignHelper(this, resolveKeyRef())
    helper.attest(image, predicateType, predicatePath)
}

def verifySupplyChain(String image, Map opts = [:]) {
    def helper = new CosignHelper(this, resolveKeyRef())
    return helper.verifySupplyChain(image, opts.keyless ?: false)
}

def generateKey() {
    def helper = new CosignHelper(this, '')
    helper.generateKey()
}

@NonCPS
private String resolveKeyRef() {
    if (env.COSIGN_KMS_KEY) return env.COSIGN_KMS_KEY
    if (env.COSIGN_PUB_KEY) return env.COSIGN_PUB_KEY
    if (env.COSIGN_KEY_ID) return "jenkins://${env.COSIGN_KEY_ID}"
    return ''
}

return this