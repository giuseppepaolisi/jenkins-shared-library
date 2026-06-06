# Architettura delle Jenkins Shared Library in Groovy Enterprise

## Indice

1. [Fondamenti Groovy per Jenkins Pipeline](#1-fondamenti-groovy-per-jenkins-pipeline)
2. [CPS e @NonCPS — Il cuore delle Pipeline](#2-cps-e-noncps)
3. [Pattern Architetturale per Shared Library Enterprise](#3-pattern-architetturale)
4. [Serializzazione e Persistent State](#4-serializzazione)
5. [Error Handling Pattern](#5-error-handling)
6. [Testing delle Shared Library](#6-testing)
7. [Security Pattern](#7-security-pattern)
8. [Performance Optimization](#8-performance)
9. [Design Pattern Specifici](#9-design-pattern)
10. [Anti-Pattern da Evitare](#10-anti-pattern)

---

## 1. Fondamenti Groovy per Jenkins Pipeline

### 1.1 CPS Transformation

Jenkins Pipeline esegue il codice Groovy attraverso un trasformatore **CPS (Continuation Passing Style)**. Questo permette alla pipeline di:

- **Mettere in pausa** tra uno step e l'altro (es: `sh`, `input`)
- **Riprendere** dopo un restart di Jenkins
- **Serializzare** lo stato della pipeline su disco

**Implicazione critica**: Non tutto il codice Groovy standard funziona nelle pipeline Jenkins.

```groovy
// ❌ QUESTO NON FUNZIONA nelle pipeline CPS
def items = []
(1..10).each { items.add(it) }

// ✅ QUESTO FUNZIONA
def items = []
for (int i = 1; i <= 10; i++) { items.add(i) }
```

### 1.2 Il Dilemma `@NonCPS`

L'annotazione `@NonCPS` è uno degli strumenti più importanti ma anche più fraintesi.

```groovy
import groovy.transform.NonCPS

@NonCPS
def complexLogic(String input) {
    // Questo codice NON viene trasformato in CPS
    // Puoi usare costrutti Groovy puri (collect, each, closure, regex, etc.)
    return input.tokenize('.').collect { it.toInteger() }.sum()
}
```

**Regole d'oro per `@NonCPS`**:

| ✅ Usa `@NonCPS` per | ❌ NON usare `@NonCPS` per |
|----------------------|---------------------------|
| Calcoli puri (matematica, stringhe) | Chiamate a `sh`, `echo`, `readJSON` |
| Trasformazioni dati (map, collect, sort) | Accesso a `env`, `currentBuild` |
| Validazione e parsing | Qualsiasi step Jenkins |
| Metodi statici utility | Operazioni I/O |
| Pattern matching complessi | `withCredentials`, `retry`, `timeout` |

### 1.3 Struttura Directory

```
src/                          # Classi Groovy compilate
  io/awesome/jenkins/         # Package standard
    AWSHelper.groovy          # Ogni classe = 1 file
vars/                         # Step accessibili come variabili
  log.groovy                  → log.info(), log.warning()
  dockerOps.groovy            → dockerOps.build(), dockerOps.push()
  pipelineUtils.groovy        → pipelineUtils.retryWithBackoff()
resources/                    # File statici
  templates/                  # Template JSON, YAML, HTML
test/                         # Test unitari
  src/                        # Test per classi src/
  vars/                       # Test per step vars/
```

**Differenza chiave**:
- `vars/` — script che diventano **variabili globali** nel Jenkinsfile
- `src/` — classi importabili con package, **riutilizzabili** tra più vars
- `resources/` — file caricabili con `libraryResource()`

---

## 2. CPS e @NonCPS

### 2.1 Come CPS Trasforma il Codice

Il trasformatore CPS prende il bytecode Groovy e lo riscrive in una macchina a stati:

```groovy
// Codice originale
def pipeline() {
    echo "Step 1"
    def result = sh(script: 'comando', returnStdout: true)
    echo "Risultato: ${result}"
}
```

Viene trasformato in qualcosa come:

```groovy
// CPS trasformato (concettualmente)
def pipeline() {
    callCC { returnCont ->
        STATE = 1
        echo "Step 1"
        // Punto di interruzione: lo stato viene serializzato
        sh(script: 'comando', returnStdout: true).then { result ->
            STATE = 2
            echo "Risultato: ${result}"
            returnCont()
        }
    }
}
```

### 2.2 Limitazioni CPS

1. **No classi Java/Groovy non serializzabili** che contengono closure
2. **No `new Thread()` o `Thread.start()`**
3. **No metodi su classi Java che usano reflection** (`Method.invoke`, `Constructor.newInstance`)
4. **No `for(;;)` infiniti** — rischio stack overflow
5. **Collection e loop** — `each`, `collect`, `findAll` sono safe solo in `@NonCPS`

### 2.3 Pattern Sicuri per Loop

```groovy
// ❌ CPS UNSAFE — usa each
def processList(list) {
    list.each { item -> sh "echo ${item}" }
}

// ✅ CPS SAFE — usa for-in o each con metodo helper @NonCPS
def processList(List list) {
    // for-in è safe in CPS
    for (item in list) {
        sh "echo ${item}"
    }
}

// ✅ ANCORA MEGLIO — sposta la logica in @NonCPS
@NonCPS
def transformList(List input) {
    return input.collect { it.toUpperCase() }.sort()
}

def process() {
    def items = ['a', 'b', 'c']
    def transformed = transformList(items)  // @NonCPS → safe
    transformed.each { item ->              // NO! each non è safe qui
        sh "echo ${item}"
    }
}
```

### 2.4 Strategy: Sandwich Pattern

```
┌─────────────────────────────────────┐
│         CPS (Jenkins Steps)         │  ← sh, echo, withCredentials
├─────────────────────────────────────┤
│         @NonCPS (Logica pura)      │  ← calcoli, parsing, validazione
├─────────────────────────────────────┤
│         CPS (Jenkins Steps)         │  ← sh, input, archive
└─────────────────────────────────────┘
```

```groovy
def processAndDeploy(String version) {
    // CPS — Step Jenkins
    def rawData = sh(script: 'cat config.json', returnStdout: true)

    // @NonCPS — Logica
    def config = parseConfig(rawData, version)

    // CPS — Step Jenkins
    sh "deploy.sh ${config.deployVersion} ${config.env}"
}

@NonCPS
private Map parseConfig(String raw, String version) {
    def json = new groovy.json.JsonSlurper().parseText(raw)
    return [
        deployVersion: version ?: json.defaultVersion,
        env: json.environment,
        replicas: Math.max(json.minReplicas, 3)
    ]
}
```

---

## 3. Pattern Architetturale per Shared Library Enterprise

### 3.1 Layered Architecture

```
Jenkinsfile (Declarative/ Scripted)
    │
    ▼
vars/ (Step Pubblici — Facciata)
    │  log.info(), dockerOps.build(), slackNotify.notifySuccess()
    │
    ▼
src/ (Classi Core — Business Logic)
    │  AWSHelper.groovy, DockerHelper.groovy, SlackHelper.groovy
    │
    ▼
resources/ (Template — Config)
    │  slack-message-template.json, changelog-template.md
```

### 3.2 Pattern Facciata (vars → src)

```groovy
// vars/dockerOps.groovy — Facciata pubblica
import io.awesome.jenkins.DockerHelper

def build(String image, List<String> tags, Map args = [:]) {
    def helper = new DockerHelper(this, parseRegistries())
    return helper.build(image, tags, args)
}

def pushToRegistries(String image, List<String> tags, List<String> registries) {
    def helper = new DockerHelper(this, parseRegistries())
    helper.pushToRegistries(image, tags, registries)
}

@NonCPS
private Map parseRegistries() {
    try {
        return readJSON(text: env.DOCKER_REGISTRIES ?: '{}')
    } catch (Exception e) {
        return [:]
    }
}
```

### 3.3 Pattern Helper Stateful

```groovy
// src/io/awesome/jenkins/DockerHelper.groovy
class DockerHelper implements Serializable {
    private static final long serialVersionUID = 1L

    // State: configurato nel costruttore, immutable dopo
    private final Script script       // Reference allo script Jenkins
    private final Map registries      // Configurazione registries

    // NO variabili mutabili dopo l'inizializzazione
    // NO closure non serializzabili

    DockerHelper(Script script, Map registries) {
        this.script = script
        this.registries = registries
    }

    def build(String image, List<String> tags, Map args) {
        // Usa script.sh, script.echo, script.withCredentials
    }
}
```

---

## 4. Serializzazione e Persistent State

### 4.1 Serializable

Tutte le classi in `src/` **DEVONO** implementare `Serializable`:

```groovy
class MyHelper implements Serializable {
    private static final long serialVersionUID = 1L
    // ...
}
```

**Perché**: Jenkins serializza l'intero stack CPS su disco dopo ogni step. Se una classe non è serializzabile, la pipeline crasha con `NotSerializableException`.

### 4.2 Campi Transient

```groovy
class MyHelper implements Serializable {
    private static final long serialVersionUID = 1L
    private final Script script          // Serializable (CpsScript)
    private final Map config             // Serializable (HashMap)
    private transient SomeService svc    // NON serializzabile → transient

    // Ricrea a runtime
    private SomeService getService() {
        if (svc == null) {
            svc = new SomeService(config)
        }
        return svc
    }
}
```

### 4.3 Closure e Serializzazione

Le closure **non sono serializzabili di default** in Groovy.

```groovy
// ❌ NON serializzabile
def closure = { sh "echo hello" }

// ✅ serializzabile (se SerializableClosure è disponibile)
import org.jenkinsci.plugins.workflow.cps.CpsClosure2
def closure = { sh "echo hello" } as SerializableClosure
```

**Miglior pratica**: non memorizzare closure come campi di classe. Passale come parametri di metodo e usale immediatamente.

---

## 5. Error Handling Pattern

### 5.1 Retry with Exponential Backoff

```groovy
def retryWithBackoff(int maxRetries, long baseDelayMs, Closure closure) {
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            return closure.call()
        } catch (Exception e) {
            if (attempt == maxRetries) throw e
            long jitter = Math.round(Math.random() * baseDelayMs * 0.2)
            long delay = (baseDelayMs * Math.pow(2, attempt - 1)) + jitter
            echo "[Retry] Tentativo ${attempt}/${maxRetries} fallito. Retry tra ${delay}ms"
            sleep(delay)
        }
    }
}
```

### 5.2 Safe Stage Pattern

```groovy
def safeStage(String name, Closure closure, boolean failBuild = true) {
    try {
        echo "[Stage] ▶ ${name}"
        def result = closure.call()
        echo "[Stage] ✓ ${name}"
        return result
    } catch (Exception e) {
        echo "[Stage] ✗ ${name}: ${e.message}"
        if (failBuild) throw e
        return [status: 'FAILURE', error: e.message]
    }
}
```

### 5.3 Checkpoint Pattern (Recovery)

```groovy
class CheckpointManager implements Serializable {
    private final Script script

    def executeOrSkip(String checkpointName, Closure closure) {
        def checkpointFile = "/tmp/checkpoint-${checkpointName}"
        if (fileExists(checkpointFile)) {
            echo "[Checkpoint] ✓ ${checkpointName} già eseguito, skip"
            return
        }
        closure.call()
        sh "touch ${checkpointFile}"
    }
}
```

---

## 6. Testing delle Shared Library

### 6.1 JenkinsPipelineUnit Framework

```groovy
// test/MyHelperTest.groovy
import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Test

class MyHelperTest extends BasePipelineTest {

    @Override
    @Before
    void setUp() {
        super.setUp()
        // Mock variabili d'ambiente
        binding.setVariable('env', [
            JOB_NAME: 'test-job',
            BUILD_NUMBER: '42',
            BRANCH_NAME: 'main'
        ])
        // Mock comandi sh
        helper.registerAllowedMethod('sh', [Map.class], { return 'mock output' })
        helper.registerAllowedMethod('fileExists', [String.class], { true })
        helper.registerAllowedMethod('readJSON', [Map.class], { [version: '1.0.0'] })
    }

    @Test
    void testCalculateVersion() {
        def helper = new VersionHelper(this, 'node')
        def result = helper.calculateNewVersion('1.2.3', 'patch')
        assertEquals('1.2.4', result)
    }

    @Test
    void testBuildPipeline() {
        def script = loadScript('vars/dockerOps.groovy')
        // Esegui metodo
        // Verifica comportamento
    }
}
```

### 6.2 Test Pyramid per Shared Library

```
         ┌──────────┐
         │   E2E    │  ← Rari: Docker Compose + Jenkins reale
         │ (1-2)    │
        ┌┴──────────┴┐
        │Integration │  ← Alcuni: vars che chiamano src
        │  (5-10)    │
       ┌┴────────────┴┐
       │  Unit Tests  │  ← Molti: classi src pure (@NonCPS)
       │  (20-50+)    │
       └──────────────┘
```

### 6.3 Test di Serializzazione

```groovy
@Test
void testSerialization() {
    def helper = new DockerHelper(mockScript, [:])
    def baos = new ByteArrayOutputStream()
    def oos = new ObjectOutputStream(baos)
    oos.writeObject(helper)  // Deve non lanciare eccezioni
    oos.close()

    def bais = new ByteArrayInputStream(baos.toByteArray())
    def ois = new ObjectInputStream(bais)
    def deserialized = ois.readObject()
    assertNotNull(deserialized)
}
```

---

## 7. Security Pattern

### 7.1 No Hardcoded Secrets

```groovy
// ❌ MAI
def password = 's3cr3t'

// ✅ SEMPRE
withCredentials([string(credentialsId: 'my-secret', variable: 'SECRET')]) {
    sh "deploy.sh --token '${env.SECRET}'"
}
```

### 7.2 Log Sanitization

```groovy
@NonCPS
def sanitizeForLog(String message) {
    def patterns = [
        ~/(?i)(password|secret|token|key)\s*[:=]\s*\S+/ : { it[0][0..it[0].indexOf(':')] + ' ****' },
        ~/\b[A-Za-z0-9+/]{40,}={0,2}\b/                : '****'
    ]
    patterns.each { pattern, replacement ->
        message = message.replaceAll(pattern, replacement)
    }
    return message
}
```

### 7.3 Branch Protection

```groovy
def checkBranchAllowed(String environment) {
    def branch = env.BRANCH_NAME ?: ''

    def rules = [
        prod: [~/^main$/, ~/^release\//, ~/^hotfix\//],
        staging: [~/^main$/, ~/^develop$/, ~/^release\//],
        dev: [~/.*/]
    ]

    def allowed = rules[environment]?.any { branch =~ it }
    if (!allowed) {
        error("Branch ${branch} non autorizzato per ${environment}")
    }
}
```

### 7.4 Input Validation

```groovy
@NonCPS
def sanitizeInput(String input) {
    if (!input) return ''
    // Rimuovi caratteri pericolosi per shell injection
    return input.replaceAll(/[;&|`$(){}\n\r]/, '_').trim()
}
```

---

## 8. Performance Optimization

### 8.1 Minimizzare CPS Transformation

Ogni espressione Groovy in CPS ha overhead:

```groovy
// ❌ LENTO — ogni iterazione è CPS-transformata
list.each { item ->
    sh "echo ${item}"
}

// ✅ VELOCE — @NonCPS fuori dal loop CPS
@NonCPS
def getItems() { return (1..100).collect { "item-${it}" } }

def process() {
    for (item in getItems()) {
        sh "echo ${item}"  // Solo questo è CPS
    }
}
```

### 8.2 Caching delle Sessione STS

```groovy
class AWSHelper implements Serializable {
    @NonCPS
    private final Map sessionCache = [:]

    String assumeRole(String env, String region) {
        String cacheKey = "${env}:${region}"
        if (sessionCache[cacheKey]) {
            echo "[AWS] Cache hit: ${cacheKey}"
            return sessionCache[cacheKey]
        }
        // Assume role...
        sessionCache[cacheKey] = sessionName
        return sessionName
    }
}
```

### 8.3 Minimizzare I/O CPS

Ogni chiamata `sh`, `readJSON`, `fileExists` genera un punto di checkpoint:

```groovy
// ❌ 3 checkpoint
def v1 = sh(script: 'cat version.txt', returnStdout: true)
def v2 = sh(script: 'echo ${v1}', returnStdout: true)
def v3 = sh(script: 'echo ${v2}', returnStdout: true)

// ✅ 1 checkpoint (raggruppa comandi)
def version = sh(script: 'cat version.txt | xargs echo', returnStdout: true)
```

---

## 9. Design Pattern Specifici

### 9.1 Template Method (Base → Helper)

```groovy
abstract class BaseCloudHelper implements Serializable {
    protected final Script script

    abstract String login()
    abstract String deploy(String artifact, String env)

    def runPipeline(String artifact, String env) {
        login()
        return deploy(artifact, env)
    }
}

class AWSHelper extends BaseCloudHelper {
    String login() { /* assume role STS */ }
    String deploy(String artifact, String env) { /* ECS deploy */ }
}
```

### 9.2 Strategy Pattern (Per Tool Differing)

```groovy
class MigrationHelper implements Serializable {
    private final Script script
    private final String type

    void migrate(String url, String locations) {
        switch (type) {
            case 'flyway':
                sh "${flyway} -url=${url} migrate"
                break
            case 'liquibase':
                sh "${liquibase} --url=${url} update"
                break
        }
    }
}
```

### 9.3 Builder Pattern per Configurazione

```groovy
class DockerBuildConfig implements Serializable {
    String imageName
    List<String> tags = []
    Map buildArgs = [:]
    String dockerfile = './Dockerfile'
    List<String> platforms = []
    boolean push = false

    DockerBuildConfig image(String name) { this.imageName = name; this }
    DockerBuildConfig tag(String t) { this.tags << t; this }
    DockerBuildConfig platform(String p) { this.platforms << p; this }
}
```

### 9.4 Proxy Pattern per Secreti

```groovy
class SecureProxy implements Serializable {
    private final Script script
    private final Map credentialMap = [:]

    def withCredential(String id, Closure closure) {
        script.withCredentials([script.string(credentialsId: id, variable: "SEC_${id}")]) {
            credentialMap[id] = script.env["SEC_${id}"]
            try {
                closure.call(credentialMap[id])
            } finally {
                credentialMap.remove(id)
            }
        }
    }
}
```

---

## 10. Anti-Pattern da Evitare

### 10.1 ❌ Closure come Campo di Classe

```groovy
// ❌ ANTI-PATTERN
class BadHelper implements Serializable {
    def callback  // closure non serializzabile
    BadHelper(cb) { this.callback = cb }
}

// ✅ CORRETTO
class GoodHelper implements Serializable {
    GoodHelper() { }
    def execute(Closure callback) {
        callback.call() // passata come parametro, non campo
    }
}
```

### 10.2 ❌ Classi Anonime

```groovy
// ❌ ANTI-PATTERN
def helper = new Object() {
    def doSomething() { sh "echo hello" }
} as Serializable

// ✅ CORRETTO
class MyHelper implements Serializable {
    def doSomething() { sh "echo hello" }
}
```

### 10.3 ❌ Threading

```groovy
// ❌ ANTI-PATTERN
Thread.start { sh "echo async" }

// ✅ CORRETTO (usa parallel di Jenkins)
parallel(
    task1: { sh "echo task1" },
    task2: { sh "echo task2" }
)
```

### 10.4 ❌ Stato Globale

```groovy
// ❌ ANTI-PATTERN
static Map globalState = [:]

// ✅ CORRETTO
class StatefulHelper implements Serializable {
    private Map internalState = [:]
    // istanza per execution context
}
```

### 10.5 ❌ Try-Catch Intorno a `error()`

```groovy
// ❌ ANTI-PATTERN
try {
    error("qualcosa")
} catch (Exception e) {
    // error() lancia FlowInterruptedException/hijack
    // Non catchare se non per trasformazione controllata
}

// ✅ CORRETTO
def result = safeStage("nome", {
    sh "comando"
}, false) // false = non bloccare la pipeline
```

---

## Riferimenti

- [Jenkins Pipeline CPS Documentation](https://www.jenkins.io/doc/book/pipeline/cps-method-executions/)
- [Groovy Style Guide](https://groovy-lang.org/style-guide.html)
- [JenkinsPipelineUnit](https://github.com/jenkinsci/JenkinsPipelineUnit)
- [CodeNarc - Groovy Linter](https://codenarc.github.io/CodeNarc/)

---

*Documento generato dalla Jenkins Shared Library Enterprise — aggiornamento: 2026*