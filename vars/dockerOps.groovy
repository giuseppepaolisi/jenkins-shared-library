/**
 * dockerOps.groovy — Operazioni Docker enterprise multi-registry.
 *
 * Utilizzo:
 *   dockerOps.login('nexus')                                    // login a Nexus registry
 *   dockerOps.build('myapp', ['1.0.0', 'latest'], [VERSION: '1.0.0'])
 *   dockerOps.pushToRegistries('myapp', ['1.0.0', 'latest'], ['ecr', 'nexus'])
 *   dockerOps.securityScan('myapp:1.0.0', 'CRITICAL,HIGH', true)
 *   dockerOps.generateSbom('myapp:1.0.0')
 *
 * Configurazione via env.DOCKER_REGISTRIES (JSON):
 *   {
 *     "ecr":    {"url": "123456.dkr.ecr.eu-west-1.amazonaws.com", "type": "ecr"},
 *     "nexus":  {"url": "nexus.azienda.local:8083", "type": "nexus", "creds": "nexus-docker", "username": "jenkins"},
 *     "dockerhub": {"url": "", "type": "dockerhub", "creds": "dockerhub-creds", "username": "azienda"}
 *   }
 */
import io.awesome.jenkins.DockerHelper

def login(String registryName, String region = '') {
    def helper = createHelper()
    def awsRegion = region ?: env.AWS_REGION ?: 'eu-west-1'
    helper.login(registryName, awsRegion)
}

def build(String imageName, List<String> tags, Map buildArgs = [:],
          String dockerfile = './Dockerfile', String context = '.') {
    def helper = createHelper()
    return helper.build(imageName, tags, buildArgs, dockerfile, context)
}

def pushToRegistries(String imageName, List<String> tags, List<String> registryNames) {
    def helper = createHelper()
    helper.pushToRegistries(imageName, tags, registryNames)
}

def tag(String sourceImage, List<String> newTags) {
    def helper = createHelper()
    helper.tag(sourceImage, newTags)
}

def pull(String image, int retries = 3) {
    def helper = createHelper()
    helper.pull(image, retries)
}

def securityScan(String image, String severity = 'CRITICAL,HIGH',
                  boolean exitOnFailure = true) {
    def helper = createHelper()
    return helper.securityScan(image, severity, exitOnFailure)
}

def generateSbom(String image, String format = 'cyclonedx', String outputFile = 'sbom.json') {
    def helper = createHelper()
    helper.generateSbom(image, format, outputFile)
}

@NonCPS
private DockerHelper createHelper() {
    def registriesJson = env.DOCKER_REGISTRIES ?: '{}'
    Map registries = [:]
    try {
        registries = readJSON(text: registriesJson)
    } catch (Exception e) {
        echo "[dockerOps] ⚠ DOCKER_REGISTRIES non configurato o JSON non valido"
    }
    return new DockerHelper(this, registries)
}

return this