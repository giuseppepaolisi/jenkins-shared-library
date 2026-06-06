# Jenkins Shared Library Enterprise

Libreria condivisa Jenkins per pipeline enterprise con supporto **multi-cloud**, **multi-registry**, **mobile publishing**, **Kubernetes**, **Terraform**, **security scanning**, **policy as code**, **supply chain signing**, e **notifiche multi-canale**.

---

## Indice

- [Panoramica](#panoramica)
- [Installazione](#installazione)
- [Step Disponibili](#step-disponibili-quick-reference)
- [Esempi Jenkinsfile](#esempi-jenkinsfile)
- [Configurazione](#configurazione)
- [Sicurezza](#sicurezza)
- [Documentazione](#documentazione)

---

## Panoramica

### Cosa Puoi Fare

| Area | Step | Helper Classe |
|------|------|---------------|
| **AWS Multi-Account** | `awsAssumeRole` · `awsAssumeRole.withAwsEnv()` · `s3Upload()` · `ecrLogin()` | `AWSHelper` |
| **Docker** | `dockerOps.build()` · `pushToRegistries()` · `multi-arch` · `securityScan()` · `generateSbom()` | `DockerHelper` |
| **Nexus** | `nexusOps.uploadArtifact()` · `downloadArtifact()` · `promoteSnapshotToRelease()` · `queryArtifact()` | `NexusHelper` |
| **Version Bumping** | `bumpVersion()` — Conventional Commits o manuale per Node/Maven/Gradle/Python/Go | `VersionHelper` |
| **Kubernetes** | `k8sDeploy.helmDeploy()` · `helmRollback()` · `argocdSync()` · `canaryDeploy()` · `blueGreenSwitch()` · `kubectlApply()` · `detectDrift()` | `KubernetesHelper` |
| **Terraform / IaC** | `terraformPipeline.run()` · `plan()` · `apply()` · `estimateCost()` · `opaPolicyCheck()` · `tflint()` · `detectDrift()` | `TerraformHelper` |
| **App Android** | `androidBuild.buildAndSign()` · `uploadToGooglePlay()` · `runLint()` · `bumpVersionCode()` | `AndroidHelper` |
| **App iOS** | `iosBuild.buildAndSign()` · `uploadToAppStore()` · `distributeToTestFlight()` · `runTests()` | `IOSHelper` |
| **Huawei AppGallery** | `huaweiUpload()` · `queryStatus()` · `listVersions()` | `HuaweiHelper` |
| **Amazon AppStore** | `amazonAppStore()` · `queryReleases()` · `getAppDetails()` | `AmazonAppStoreHelper` |
| **Security Scanning** | `securityScan.container()` · `filesystem()` · `sonarQube()` · `dependencyCheck()` · `fullScan()` | — |
| **Container Signing** | `cosignOps.sign()` · `verify()` · `attest()` · `verifySupplyChain()` · `generateKey()` | `CosignHelper` |
| **Supply Chain** | SBOM generation · Cosign attestation · Trivy gates | `DockerHelper` + `CosignHelper` |
| **Database Migrations** | `dbMigrate.run()` · `migrate()` · `rollback()` · `repair()` · `validate()` · `info()` · `baseline()` | `MigrationHelper` |
| **Performance Testing** | `performanceTest.k6()` · `gatling()` · `jmeter()` · `compareWithBaseline()` | `PerformanceHelper` |
| **Policy as Code** | `policyCheck.evaluate()` · `conftest()` · `k8s()` · `docker()` · `terraform()` · `compliance()` | `OPAHelper` |
| **Notifiche Multi-Canale** | `multiNotify.notifyStart()` · `notifySuccess()` · `notifyFailure()` · `notifyDeploy()` | `NotifierHelper` |
| **Slack** | `slackNotify.notifyStart()` · `notifySuccess()` · `notifyFailure()` · `notifyPhase()` · `notifyDeploy()` | `SlackHelper` |
| **Dependency Caching** | `cacheOps.withCache()` · `restore()` · `save()` per npm/maven/gradle/pip/go | `CacheHelper` |
| **Logging Enterprise** | `log.info()` · `warning()` · `error()` · `debug()` · `trace()` con mascheramento automatico secreti | — |
| **Pipeline Utilities** | `pipelineUtils.retryWithBackoff()` · `safeStage()` · `parallelSafe()` · `gitCleanCheck()` · `archiveReports()` | `Utils` |
| **Secret Management** | `SecretManager` — Jenkins Credentials + Vault + AWS Secrets Manager | `SecretManager` |
| **Raw Artifacts** | `rawArtifact.uploadToNexus()` · `uploadToS3()` · `archive()` · `uploadToMultipleDestinations()` | — |

### Architettura

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Jenkinsfile                                   │
│   Declarative pipeline che chiama step dalla libreria                │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────────────┐
│  vars/  —— 22 step richiamabili come variabili globali                │
│                                                                      │
│  log.groovy         dockerOps.groovy         k8sDeploy.groovy        │
│  bumpVersion.groovy terraformPipeline.groovy cacheOps.groovy         │
│  slackNotify.groovy multiNotify.groovy       cosignOps.groovy        │
│  androidBuild.groovy iosBuild.groovy         dbMigrate.groovy        │
│  huaweiUpload.groovy amazonAppStore.groovy   performanceTest.groovy  │
│  awsAssumeRole.groovy nexusOps.groovy        securityScan.groovy     │
│  policyCheck.groovy    rawArtifact.groovy    pipelineUtils.groovy    │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────────────┐
│  src/  —— 20 classi core Groovy (package io.awesome.jenkins)          │
│                                                                      │
│  AWSHelper          DockerHelper         KubernetesHelper            │
│  TerraformHelper    VersionHelper        NexusHelper                 │
│  AndroidHelper      IOSHelper            HuaweiHelper                │
│  AmazonAppStoreHelper  CosignHelper      MigrationHelper             │
│  SlackHelper        NotifierHelper       OPAHelper                   │
│  PerformanceHelper  CacheHelper          SecretManager               │
│  Utils                                                                 │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────────────┐
│  resources/        test/           examples/         docs/            │
│  template JSON     unit test       Jenkinsfile       ARCHITECTURE      │
│  changelog MD      Docker Compose  completi          SECURITY         │
│                                                      DEVELOPMENT      │
└────────────────────────────────────────────────────────────────────────
```

---

## Installazione

### 1. Aggiungi la libreria in Jenkins

**Manage Jenkins → Configure System → Global Pipeline Libraries**

| Campo | Valore |
|-------|--------|
| Name | `jenkins-shared-library` |
| Default version | `main` |
| Retrieval method | Modern SCM |
| Source Code Management | Git |
| Repository URL | `https://github.com/azienda/jenkins-shared-library.git` |

### 2. Usa in un Jenkinsfile

```groovy
@Library('jenkins-shared-library') _

pipeline {
    agent any
    stages {
        stage('Example') {
            steps {
                log.info('Ciao dalla shared library enterprise!')
                pipelineUtils.safeStage('Test', { sh 'echo test' })
            }
        }
    }
    post {
        success { slackNotify.notifySuccess(currentBuild.durationString, 'dev') }
        failure { slackNotify.notifyFailure('Build fallita', 'dev', env.STAGE_NAME) }
    }
}
```

---

## Step Disponibili — Quick Reference

### Logging
```groovy
log.info('Messaggio')                              // INFO
log.warning('Attenzione')                           // WARNING
log.error('Errore')                                 // ERROR
log.debug('Solo se LOG_DEBUG=true')                 // DEBUG
log.trace('Contesto', [key: 'val'])                 // TRACE con contesto
```

### Pipeline Utilities
```groovy
pipelineUtils.retryWithBackoff({ sh 'comando' }, 3, 1000, 'descrizione')
pipelineUtils.safeStage('Nome Stage', { ... }, false)
pipelineUtils.parallelSafe(['A': { ... }, 'B': { ... }])
pipelineUtils.gitCleanCheck()
pipelineUtils.ensureBranch('main')
pipelineUtils.archiveReports()
pipelineUtils.printBuildSummary('SUCCESS', '5m 12s', '1.2.3')
```

### AWS Multi-Account
```groovy
awsAssumeRole('staging')
awsAssumeRole.withAwsEnv('prod') { sh 'aws s3 ls' }
awsAssumeRole.s3Upload('bucket', 'path/key', 'file.txt', [encrypt: true])
awsAssumeRole.s3Download('bucket', 'path/key', './file.txt')
awsAssumeRole.ecrLogin('eu-west-1', '123456789012')
```

### Docker
```groovy
dockerOps.build('myapp', ['1.0.0', 'latest'], [VERSION: '1.0.0'])
dockerOps.build('myapp', ['1.0.0'], [:], './Dockerfile', '.', ['linux/amd64', 'linux/arm64'])
dockerOps.pushToRegistries('myapp', ['1.0.0'], ['ecr', 'nexus'], true)  // parallelo
dockerOps.securityScan('myapp:1.0.0', 'CRITICAL,HIGH')
dockerOps.generateSbom('myapp:1.0.0', 'cyclonedx', 'sbom.json')
dockerOps.login('nexus')
```

### Nexus
```groovy
nexusOps.uploadArtifact('build/app.apk', 'raw-releases', 'com.azienda', 'myapp', '1.0.0', 'apk')
nexusOps.downloadArtifact('/tmp/lib.jar', 'maven-releases', 'com.azienda', 'lib', '1.0.0')
nexusOps.promoteSnapshotToRelease('com.azienda', 'myapp', '1.0.0-SNAPSHOT', '1.0.0')
nexusOps.getLatestVersion('maven-releases', 'com.azienda', 'lib')
```

### Version Bumping
```groovy
bumpVersion(projectType: 'node')                      // Conventional Commits
bumpVersion(projectType: 'maven', bumpType: 'major')  // Manuale
bumpVersion(projectType: 'gradle', bumpType: 'minor', dryRun: true)
bumpVersion(projectType: 'python')                     // pyproject.toml/setup.py
bumpVersion(projectType: 'go')                         // go.mod
// Supportati: node, maven, gradle, python, go, rust
```

### Kubernetes
```groovy
k8sDeploy.helmDeploy('myapp', './charts/myapp', 'staging',
    valuesFiles: ['values-staging.yaml'],
    setValues: [image: { tag: '1.0.0' }],
    atomic: true)
k8sDeploy.helmRollback('myapp', 'prod', 5)
k8sDeploy.helmTest('myapp', 'staging')
k8sDeploy.argocdSync('myapp-production')
k8sDeploy.canaryDeploy('myapp', 'staging', '1.1.0', 10, 'istio')
k8sDeploy.blueGreenSwitch('myapp', 'prod', 'green')
k8sDeploy.kubectlApply('k8s/deployment.yaml', 'default', [validate: true, prune: true])
k8sDeploy.checkClusterHealth()
k8sDeploy.detectDrift('k8s/deployment.yaml', 'prod')
k8sDeploy.deployPipeline('myapp', './charts/myapp', 'prod',
    valuesFiles: ['values-prod.yaml'])
```

### Terraform / IaC
```groovy
terraformPipeline.run(workingDir: './infra', workspace: 'dev',
    varFile: 'environments/dev.tfvars', costEstimate: true, autoApply: true)
terraformPipeline.validate('./infra')
terraformPipeline.plan('./infra', varFile: 'prod.tfvars', vars: [region: 'eu-west-1'])
terraformPipeline.apply('./infra')
terraformPipeline.destroy('./infra', force: true)
terraformPipeline.estimateCost('./infra')
terraformPipeline.detectDrift('./infra')
terraformPipeline.opaPolicyCheck('./infra', 'policies/terraform')
terraformPipeline.tflint('./infra')
```

### Security Scanning
```groovy
securityScan.container('myapp:1.0.0', severity: 'CRITICAL,HIGH')
securityScan.filesystem('.', severity: 'CRITICAL,HIGH', exitOnFailure: false)
securityScan.sonarQube(projectKey: 'com.azienda:myapp', sources: '.')
securityScan.dependencyCheck(path: '.', format: 'HTML')
securityScan.fullScan(image: 'myapp:1.0.0', projectKey: 'com.azienda:myapp')
```

### Container Signing (Cosign)
```groovy
cosignOps.sign('registry.azienda.local/myapp:1.0.0',
    annotations: [version: '1.0.0', env: 'prod'])
cosignOps.sign('myapp:1.0.0', [keyless: true])               // Sigstore keyless
cosignOps.verify('myapp:1.0.0')
cosignOps.attest('myapp:1.0.0', 'cyclonedx', 'sbom.json')
cosignOps.verifySupplyChain('myapp:1.0.0')
```

### Database Migrations
```groovy
dbMigrate.run(url: 'jdbc:postgresql://db:5432/myapp', env: 'staging',
    type: 'flyway', schema: 'public')
dbMigrate.run(url: 'jdbc:mysql://db:3306/myapp', type: 'liquibase')
dbMigrate.rollback('jdbc:postgresql://db:5432/myapp', 2, 'staging')
dbMigrate.info('jdbc:postgresql://db:5432/myapp', 'staging')
dbMigrate.baseline('jdbc:postgresql://db:5432/myapp', '1', 'Baseline iniziale')
```

### Performance Testing
```groovy
performanceTest.k6('tests/load.js',
    stages: [[duration: '30s', target: 10], [duration: '1m', target: 50]],
    thresholds: ['http_req_duration': ['p(95)<500']])
performanceTest.gatling('simulations.LoadTest')
performanceTest.jmeter('tests/load.jmx', threads: 10, duration: 60)
performanceTest.compareWithBaseline('metrics.json')
```

### Policy as Code
```groovy
policyCheck.evaluate('policies/k8s', 'deployment.yaml', 'data.kubernetes.deny')
policyCheck.conftest('deployment.yaml', 'policies/k8s')
policyCheck.k8s('deployment.yaml')
policyCheck.docker('Dockerfile')
policyCheck.terraform('tfplan.json')
policyCheck.compliance('gdpr', 'config.json')
```

### Notifiche Multi-Canale
```groovy
multiNotify.notifyStart('MyApp CI', 'staging')
multiNotify.notifySuccess(currentBuild.durationString, 'prod', 'Fix: login bug')
multiNotify.notifyFailure('NullPointerException', 'prod', 'Deploy')
multiNotify.notifyDeploy('prod', '1.0.0', 'success', '5m 12s')
multiNotify.send('critical', 'ALLARME', 'Downtime rilevato',
    channels: ['slack', 'pagerduty'])
```

### Slack
```groovy
slackNotify.notifyStart('MyApp', 'staging')
slackNotify.notifySuccess('12m 34s', 'prod', 'Fix: login bug')
slackNotify.notifyFailure('Errore in Service.java:45', 'prod', 'Deploy')
slackNotify.notifyDeploy('prod', '1.0.0', 'success', '5m')
slackNotify.notifyPhase('Docker Build', 'staging')
```

### Android App
```groovy
def apks = androidBuild.buildAndSign(
    flavor: 'production', buildType: 'release',
    keystoreId: 'android-keystore', bundle: true, extraTasks: ['lint'])
androidBuild.uploadToGooglePlay(
    artifactPath: apks[0], serviceAccountId: 'gplay-svc',
    applicationId: 'com.azienda.app', track: 'internal')
androidBuild.runLint()
androidBuild.bumpVersionCode()
```

### iOS App
```groovy
def ipa = iosBuild.buildAndSign(
    matchGitUrl: 'git@github.com:azienda/match.git',
    bundleIdentifier: 'com.azienda.app')
iosBuild.uploadToAppStore(ipaPath: ipa, appStoreKeyId: 'asc-key', bundleId: 'com.azienda.app')
iosBuild.distributeToTestFlight(ipaPath: ipa, testers: ['tester@azienda.local'])
iosBuild.runTests(device: 'iPhone 14', osVersion: '16.4')
iosBuild.bumpBuildNumber()
```

### Huawei AppGallery
```groovy
huaweiUpload(filePath: 'build/app-release.apk', distributionPhase: 'Draft')
def status = huaweiUpload.queryStatus()
```

### Amazon AppStore
```groovy
amazonAppStore(filePath: 'build/app-release.apk', releaseNotes: 'Fix crash', isProduction: false)
def releases = amazonAppStore.queryReleases()
```

### Dependency Caching
```groovy
cacheOps.withCache('npm') { sh 'npm ci' }
cacheOps.withCache('maven') { sh 'mvn clean package' }
cacheOps.withCache('go') { sh 'go build ./...' }
cacheOps.withCache('pip') { sh 'pip install -r requirements.txt' }
cacheOps.restore('gradle')
cacheOps.save('gradle')
```

### Raw Artifacts
```groovy
rawArtifact.uploadToNexus(path: 'build/app.jar', repository: 'raw-releases',
    groupId: 'com.azienda', artifactId: 'myapp', version: '1.0.0')
rawArtifact.uploadToS3(bucket: 'artifacts', key: 'app.jar', filePath: 'build/app.jar')
rawArtifact.archive('build/**/*.jar')
rawArtifact.uploadToMultipleDestinations(
    filePath: 'build/app.jar',
    destinations: ['nexus', 's3', 'jenkins'],
    nexusParams: [repository: 'raw-releases', groupId: 'com.azienda', artifactId: 'myapp', version: '1.0.0'],
    s3Params: [bucket: 'artifacts', key: 'builds/myapp-1.0.0.jar'])
```

---

## Esempi Jenkinsfile

Nella directory `examples/` trovi pipeline complete:

| File | Descrizione |
|------|-------------|
| `examples/microservice.groovy` | Spring Boot + Docker + AWS + Nexus + version bumping |
| `examples/android-app.groovy` | Android multi-store (Google Play + Huawei + Amazon) |
| `examples/ios-app.groovy` | iOS + Xcode + Fastlane + App Store + TestFlight |
| `examples/node-library.groovy` | Node.js + npm + Nexus + dependency caching |
| `examples/aws-lambda.groovy` | AWS Lambda multi-account + staging/prod |

---

## Configurazione

### Variabili d'Ambiente Globali

```groovy
// Jenkins → Manage Jenkins → Configure System → Global Properties

// AWS Multi-Account
AWS_ACCOUNTS = '{"dev":"111111111111","staging":"222222222222","prod":"333333333333"}'
AWS_ROLES    = '{"dev":"arn:aws:iam::111111111111:role/JenkinsDeployRole"}'
AWS_REGION   = 'eu-west-1'

// Docker Registries
DOCKER_REGISTRIES = '{"ecr":{"url":"123456.dkr.ecr.eu-west-1.amazonaws.com","type":"ecr"},"nexus":{"url":"nexus.azienda.local:8083","type":"nexus","creds":"nexus-docker","username":"jenkins"}}'

// Notifiche Multi-Canale
NOTIFIER_CONFIG = '{"slack":{"webhookUrl":"https://hooks.slack.com/..."},"teams":{"webhookUrl":"https://teams.webhook/..."},"email":{"to":"team@azienda.local"},"pagerduty":{"routingKey":"..."}}'

// Slack (fallback)
SLACK_CONFIG = '{"channel":"#ci-general","webhookUrl":"...","channels":{"dev":"#ci-dev","staging":"#ci-staging","prod":"#ci-prod"}}'

// Nexus
NEXUS_URL   = 'https://nexus.azienda.local'
NEXUS_CREDS = 'nexus-jenkins-creds'

// Git
GIT_USER_NAME  = 'Jenkins CI'
GIT_USER_EMAIL = 'jenkins@azienda.local'
GPG_SIGN       = 'true'

// Cache
CACHE_TYPE      = 's3'
CACHE_S3_BUCKET = 'jenkins-cache'

// Debug
LOG_DEBUG = 'true'
LOG_JSON  = 'true'
```

### Jenkins Credentials Necessari

| ID | Tipo | Descrizione |
|----|------|-------------|
| `aws-global` | Secret text | AWS Access Key ID/Secret |
| `nexus-jenkins-creds` | Username/password | Accesso Nexus |
| `nexus-docker-creds` | Username/password | Docker registry su Nexus |
| `android-keystore` | Secret file | Keystore APK signing |
| `google-play-svc-account` | Secret file | Service account JSON Google Play |
| `asc-api-key` | Secret text | App Store Connect API Key |
| `fastlane-match-git-url` | Secret text | Git URL per Fastlane match |
| `cosign-key` | Secret file | Chiave privata Cosign |
| `cosign-password` | Secret text | Password chiave Cosign |
| `argocd-token` | Secret text | ArgoCD API token |
| `kubeconfig` | Secret file | kubeconfig per cluster K8s |

---

## Sicurezza

| Misura | Implementazione |
|--------|----------------|
| No hardcoded secrets | `SecretManager` + `withCredentials()` |
| Log sanitization | `log.groovy` — maschera automaticamente token, JWT, password |
| Branch protection | `AWSHelper.validateBranchRestrictions()` |
| GPG signing | `VersionHelper` con `git tag -s` |
| Input sanitization | `Utils.sanitizeInput()` — anti shell injection |
| SBOM generation | `DockerHelper.generateSbom()` |
| Container signing | `CosignHelper.sign()` + `verify()` |
| Vulnerability gates | `securityScan` con Trivy thresholds |
| Supply chain | Cosign attestation + SBOM verification |
| Policy as Code | `OPAHelper` con Rego policy |
| Audit logging | Log strutturati JSON per ELK/Splunk |

Vedi [docs/SECURITY.md](docs/SECURITY.md) per dettagli.

---

## Documentazione

| Documento | Descrizione |
|-----------|-------------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Guida teorica: CPS, @NonCPS, pattern Groovy, serializzazione, testing, security |
| [docs/SECURITY.md](docs/SECURITY.md) | Policy di sicurezza, credential management, supply chain |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | Standard di sviluppo, template, workflow, testing |

---

## Licenza

Proprietaria — Uso interno aziendale. Jenkins Shared Library Enterprise © 2026