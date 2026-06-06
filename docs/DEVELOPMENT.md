# Guida allo Sviluppo della Shared Library

## Workflow

1. **Crea branch feature** da `main`
2. **Implementa classe** in `src/io/awesome/jenkins/`
3. **Crea facciata** in `vars/`
4. **Aggiungi test** in `test/`
5. **Documenta** in `README.md`
6. **PR** → review → merge su `main`

## Standard di Codice

### Convenzioni

- **CamelCase** per classi (`AWSHelper.groovy`)
- **camelCase** per metodi (`s3Upload()`)
- **snake_case** per env var (`AWS_DEFAULT_REGION`)
- **kebab-case** per file statici (`slack-message-template.json`)

### Template per Nuova Classe

```groovy
package io.awesome.jenkins

/**
 * [NomeHelper] — [Descrizione enterprise].
 * Supporta: [feature1], [feature2], [feature3].
 */
class NomeHelper implements Serializable {
    private static final long serialVersionUID = 1L
    private static final int DEFAULT_RETRIES = 3

    private final Script script
    private final Map config

    NomeHelper(Script script, Map config = [:]) {
        this.script = script
        this.config = config
    }

    def metodoPrincipale(Map params = [:]) {
        script.echo "[Nome] ▶ Operazione: ${params}"

        script.retry(params.retries ?: DEFAULT_RETRIES) {
            // Implementazione
            script.sh "..."
        }

        script.echo "[Nome] ✓ Operazione completata"
    }

    @NonCPS
    private void validateParams(Map params) {
        params.each { k, v ->
            if (v == null || (v instanceof String && v.trim().isEmpty())) {
                script.error("[Nome] Parametro obbligatorio: ${k}")
            }
        }
    }
}
```

### Template per Nuovo Step vars/

```groovy
/**
 * newStep.groovy — [Descrizione].
 *
 * Utilizzo:
 *   newStep.operation(param1: 'value1', param2: 'value2')
 *
 * Configurazione via env:
 *   NEW_STEP_CONFIG = '{"key": "value"}'  (Jenkins JSON config)
 */
import io.awesome.jenkins.NomeHelper

def operation(Map params = [:]) {
    def helper = createHelper()
    helper.metodoPrincipale(params)
}

def anotherOperation(String value) {
    def helper = createHelper()
    helper.altroMetodo(value)
}

@NonCPS
private NomeHelper createHelper() {
    def configStr = env.NEW_STEP_CONFIG ?: '{}'
    Map config = [:]
    try { config = readJSON(text: configStr) } catch (Exception) {}
    return new NomeHelper(this, config)
}

return this
```

## Testing

```bash
# Lint Groovy
docker run --rm -v $(pwd):/workspace code-narc/code-narc /workspace

# Test unitari
gradle test

# Verifica serializzazione
gradle serializationTest
```