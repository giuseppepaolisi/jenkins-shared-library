/**
 * slackNotify.groovy — Notifiche Slack enterprise con smart routing,
 * rate limiting, threading e supporto multi-canale per ambiente.
 *
 * Utilizzo:
 *   slackNotify.notifyStart('MyApp CI', 'staging')
 *   slackNotify.notifySuccess('12m 34s', 'prod', 'Fix: login bug')
 *   slackNotify.notifyFailure('NullPointerException in Service.java:45', 'prod', 'Deploy')
 *   slackNotify.notifyPhase('Docker Build', 'staging', 'Immagine: myapp:1.0.0')
 *   slackNotify.notifyDeploy('prod', '1.0.0', 'success', '5m 12s')
 *   slackNotify.sendCustom(channel: '#ops', text: 'Messaggio custom')
 *
 * Configurazione via env (JSON):
 *   SLACK_CONFIG = '{"channel":"#ci-general","webhookUrl":"","botToken":"","channels":{"dev":"#ci-dev","staging":"#ci-staging","prod":"#ci-prod"}}'
 *   Oppure singole variabili:
 *   SLACK_CHANNEL, SLACK_WEBHOOK_URL, SLACK_BOT_TOKEN, SLACK_CHANNELS (JSON)
 */
import io.awesome.jenkins.SlackHelper

def notifyStart(String jobName = '', String environment = '') {
    def helper = createHelper()
    helper.notifyStart(jobName, environment)
}

def notifySuccess(String duration = '', String environment = '', String changelog = '') {
    def helper = createHelper()
    helper.notifySuccess(duration, environment, changelog)
}

def notifyFailure(String errorDetails = '', String environment = '', String failedStage = '') {
    def helper = createHelper()
    helper.notifyFailure(errorDetails, environment, failedStage)
}

def notifyPhase(String phaseName, String environment = '', String extraInfo = '') {
    def helper = createHelper()
    helper.notifyPhase(phaseName, environment, extraInfo)
}

def notifyDeploy(String environment, String version, String status = 'started', String duration = '') {
    def helper = createHelper()
    helper.notifyDeploy(environment, version, status, duration)
}

def sendCustom(Map message) {
    def helper = createHelper()
    helper.sendCustom(message)
}

@NonCPS
private SlackHelper createHelper() {
    def configStr = env.SLACK_CONFIG ?: '{}'
    Map config = [:]

    try {
        config = readJSON(text: configStr)
    } catch (Exception e) {
        echo '[slackNotify] ⚠ SLACK_CONFIG non valido, uso variabili individuali'
    }

    // Fallback a variabili individuali
    if (!config.channel && env.SLACK_CHANNEL) config.channel = env.SLACK_CHANNEL
    if (!config.webhookUrl && env.SLACK_WEBHOOK_URL) config.webhookUrl = env.SLACK_WEBHOOK_URL
    if (!config.botToken && env.SLACK_BOT_TOKEN) config.botToken = env.SLACK_BOT_TOKEN

    // Parse SLACK_CHANNELS JSON se presente
    if (!config.channels && env.SLACK_CHANNELS) {
        try {
            config.channels = readJSON(text: env.SLACK_CHANNELS)
        } catch (Exception e) {
            echo '[slackNotify] ⚠ SLACK_CHANNELS JSON non valido'
        }
    }

    return new SlackHelper(this, config)
}

return this