/**
 * log.groovy — Sistema di logging enterprise strutturato con JSON output,
 * mascheramento automatico secreti, livelli configurabili e tagging.
 *
 * Utilizzo nel Jenkinsfile:
 *   log.info('Download artefatto completato')
 *   log.warning('Timeout configurazione, valore di default')
 *   log.error('Build fallita: connessione DB')
 *   log.debug('Variabile X = ...')  // solo se config.DEBUG=true
 *
 * I secreti vengono automaticamente mascherati (pattern: ****)
 */
def info(String message) {
    log('INFO', message)
}

def warning(String message) {
    log('WARN', message)
}

def error(String message) {
    log('ERROR', message)
}

def debug(String message) {
    if (env.LOG_DEBUG == 'true') {
        log('DEBUG', message)
    }
}

def trace(String message, Map context = [:]) {
    if (env.LOG_DEBUG == 'true') {
        def ctxStr = context.collect { k, v -> "${k}=${maskSecrets(v)}" }.join(', ')
        log('TRACE', "${message} [${ctxStr}]")
    }
}

def stage(String stageName) {
    def separator = "=" * 70
    echo "${separator}"
    echo "[STAGE] ${stageName}"
    echo "${separator}"
    echo "[JSON] ${toJson('STAGE', stageName, [:])}"
}

/**
 * Log strutturato in formato JSON (machine-readable) + testo leggibile.
 */
private def log(String level, String message) {
    def maskedMsg = maskSecrets(message)
    def timestamp = new Date().format('yyyy-MM-dd HH:mm:ss.SSS')
    def logLine = "[${timestamp}] [${level}] ${maskedMsg}"

    echo logLine

    // Se LOG_JSON=true, emette anche JSON per aggregatori (ELK, Splunk)
    if (env.LOG_JSON == 'true') {
        def jsonLog = toJson(level, maskedMsg, [:])
        echo "[JSON] ${jsonLog}"
    }
}

/**
 * Converte log in formato JSON strutturato.
 */
private def toJson(String level, String message, Map extra) {
    def payload = [
        timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
        level    : level,
        message  : message,
        job      : env.JOB_NAME ?: '',
        build    : env.BUILD_NUMBER ?: '',
        branch   : env.BRANCH_NAME ?: '',
        stage    : env.STAGE_NAME ?: ''
    ] + extra

    return writeJSON(json: payload, returnText: true)
}

/**
 * Maschera pattern di secreti nei log.
 * Lista pattern: token, password, secret, key, credenziale, authorization, bearer, 
 * e variabili d'ambiente Jenkins note.
 */
private def maskSecrets(String text) {
    if (!text) return text

    def masked = text

    // Pattern comuni di secreti
    def patterns = [
        ~/(?i)(password|passwd|pwd)\s*[:=]\s*\S+/           : { it[0][0..it[0].indexOf(':')] + ' ****' },
        ~/(?i)(secret|token|api[_-]?key|apikey)\s*[:=]\s*\S+/ : { it[0][0..it[0].indexOf(':')] + ' ****' },
        ~/(?i)(bearer\s+)[a-zA-Z0-9._-]+/                     : { it[0][0..5] + ' ****' },
        ~/(?i)(authorization:\s*)\S+/                          : { 'Authorization: ****' },
        ~/(?i)(x-amz-security-token:\s*)\S+/                  : { 'x-amz-security-token: ****' },
        ~/\b[A-Za-z0-9+/]{40,}={0,2}\b/                      : { '****' },  // token base64 lunghi
        ~/\b(eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)\b/ : { '****.****.****' } // JWT
    ]

    patterns.each { pattern, replacement ->
        masked = masked.replaceAll(pattern, replacement)
    }

    return masked
}