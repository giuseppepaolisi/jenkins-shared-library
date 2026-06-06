package io.awesome.jenkins

/**
 * Utility enterprise per pipeline Jenkins resilienti.
 * Fornisce retry con backoff esponenziale, safe stage execution,
 * timing, conditional execution e gestione errori robusta.
 */
class Utils implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final Map config

    Utils(Script script, Map config = [:]) {
        this.script = script
        this.config = config + [defaultRetries: 3, defaultBackoffMs: 1000]
    }

    /**
     * Esegue una closure con retry e backoff esponenziale + jitter.
     * @param closure operazione da eseguire
     * @param retries numero massimo di tentativi (default: config.defaultRetries)
     * @param backoffMs millisecondi base per backoff (default: config.defaultBackoffMs)
     * @param description descrizione per log
     */
    def retryWithBackoff(Closure closure, int retries = -1, long backoffMs = -1, String description = '') {
        int maxRetries = retries > 0 ? retries : config.defaultRetries as int
        long baseBackoff = backoffMs > 0 ? backoffMs : config.defaultBackoffMs as long
        String desc = description ?: 'operazione generica'

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                script.echo "[Utils] Tentativo ${attempt}/${maxRetries} — ${desc}"
                return closure.call()
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    script.error("[Utils] ${desc} — fallito dopo ${maxRetries} tentativi: ${e.message}")
                }
                long jitter = Math.round(Math.random() * baseBackoff * 0.2) // 20% jitter
                long sleepMs = (baseBackoff * Math.pow(2, attempt - 1) as long) + jitter
                script.echo "[Utils] Tentativo ${attempt}/${maxRetries} fallito. Retry tra ${sleepMs}ms: ${e.message}"
                script.sleep(sleepMs)
            }
        }
    }

    /**
     * Safe stage: esegue uno stage Jenkins con gestione errori e timing.
     * Se lo stage fallisce, registra errore ma NON blocca la pipeline (utile per step non critici).
     * @param name nome dello stage
     * @param closure corpo dello stage
     * @param failBuild se true, lo stage fallisce la pipeline (default: true)
     * @return true se successo, false se fallimento (solo se failBuild=false)
     */
    def safeStage(String name, Closure closure, boolean failBuild = true) {
        long startTime = System.currentTimeMillis()
        script.echo "[Stage] ▶ Inizio: ${name}"

        try {
            def result = closure.call()
            long duration = System.currentTimeMillis() - startTime
            script.echo "[Stage] ✓ Completato: ${name} — ${formatDuration(duration)}"
            return result
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            script.echo "[Stage] ✗ Fallito: ${name} — ${formatDuration(duration)}"
            script.echo "[Stage] Errore: ${e.message}"

            if (failBuild) {
                throw e
            } else {
                script.echo "[Stage] ⚠ Errore ignorato (failBuild=false): ${name}"
                return false
            }
        }
    }

    /**
     * Parallel safe: esegue mappe di closure in parallelo, raccogliendo risultati e fallimenti.
     * @param branches mappa [nome: closure]
     * @param failFast se true, fallisce tutto se uno fallisce (default: false)
     * @return mappa [nome: risultato o errore]
     */
    def parallelSafe(Map<String, Closure> branches, boolean failFast = false) {
        def results = [:]
        def failures = []

        script.parallel branches.collectEntries { name, closure ->
            [name, {
                try {
                    results[name] = [status: 'SUCCESS', result: closure.call()]
                } catch (Exception e) {
                    results[name] = [status: 'FAILURE', error: e.message]
                    failures << name
                    if (failFast) {
                        script.error("[Parallel] FailFast attivato da: ${name}")
                    }
                }
            }]
        }

        if (failures && failFast) {
            script.error("[Parallel] Stage falliti: ${failures.join(', ')}")
        }

        return results
    }

    /**
     * Timeout condizionale: esegue una closure con timeout configurabile.
     */
    def withTimeout(Closure closure, int minutes = 10) {
        script.timeout(time: minutes, unit: 'MINUTES') {
            return closure.call()
        }
    }

    /**
     * Check pre-condizioni: verifica che una lista di condizioni sia vera.
     */
    def preconditionCheck(List<Closure<Boolean>> checks, List<String> descriptions) {
        checks.eachWithIndex { check, index ->
            if (!check.call()) {
                script.error("[Utils] Precondizione fallita: ${descriptions[index]}")
            }
            script.echo "[Utils] ✓ Precondizione ok: ${descriptions[index]}"
        }
    }

    /**
     * Formatta millisecondi in durata leggibile (es: 2m 34s 120ms)
     */
    @NonCPS
    static String formatDuration(long ms) {
        long minutes = ms / 60000
        long seconds = (ms % 60000) / 1000
        long millis = ms % 1000

        def parts = []
        if (minutes > 0) parts << "${minutes}m"
        if (seconds > 0) parts << "${seconds}s"
        parts << "${millis}ms"
        return parts.join(' ')
    }

    /**
     * Sanifica input per prevenire injection (utile per parametri Jenkins).
     */
    @NonCPS
    static String sanitizeInput(String input) {
        if (!input) return ''
        return input.replaceAll(/[;&|`$(){}\n\r]/, '_').trim()
    }

    /**
     * Lega variabile d'ambiente con fallback e validazione.
     */
    @NonCPS
    String getEnv(String name, String defaultValue = null) {
        def value = script.env[name] ?: defaultValue
        if (value == null) {
            script.error("[Utils] Variabile d'ambiente obbligatoria non impostata: ${name}")
        }
        return value
    }
}