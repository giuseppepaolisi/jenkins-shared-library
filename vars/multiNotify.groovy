/**
 * multiNotify.groovy — Notifiche multi-canale enterprise.
 * Supporta: Slack, Microsoft Teams, Email, PagerDuty con routing per severità/ambiente.
 *
 * Utilizzo:
 *   // Notifica su tutti i canali configurati
 *   multiNotify.notifyStart('MyApp CI', 'staging')
 *   multiNotify.notifySuccess(currentBuild.durationString, 'prod', 'Fix: login bug')
 *   multiNotify.notifyFailure('NullPointerException', 'prod', 'Deploy')
 *   multiNotify.notifyDeploy('prod', '1.0.0', 'success', '5m 12s')
 *
 *   // Solo su canali specifici
 *   multiNotify.send('info', 'Custom title', 'Custom message',
 *       channels: ['slack', 'teams'])
 *
 * Configurazione via env (JSON):
 *   NOTIFIER_CONFIG = '{
 *     "slack": {"webhookUrl": "https://hooks.slack.com/..."},
 *     "teams": {"webhookUrl": "https://teams.webhook/..."},
 *     "email": {"to": "team@azienda.local"},
 *     "pagerduty": {"routingKey": "..."}
 *   }'
 */
import io.awesome.jenkins.NotifierHelper

def notifyStart(String jobName = '', String environment = '') {
    def helper = createHelper()
    helper.notifyStart(jobName, environment)
}

def notifySuccess(String duration = '', String environment = '', String changelog = '') {
    def helper = createHelper()
    helper.notifySuccess(env.JOB_NAME ?: 'Pipeline', duration, environment,
        [changelog: changelog])
}

def notifyFailure(String errorDetails = '', String environment = '', String failedStage = '') {
    def helper = createHelper()
    helper.notifyFailure(env.JOB_NAME ?: 'Pipeline', errorDetails,
        environment, failedStage)
}

def notifyDeploy(String environment, String version, String status = 'started',
                  String duration = '') {
    def helper = createHelper()
    helper.notifyDeploy(environment, version, status, duration)
}

def send(String severity, String title, String message, Map opts = [:]) {
    def helper = createHelper()
    helper.notify(severity, title, message, opts)
}

@NonCPS
private NotifierHelper createHelper() {
    def configStr = env.NOTIFIER_CONFIG ?: '{}'
    Map channels = [:]

    try {
        channels = readJSON(text: configStr)
    } catch (Exception e) {
        // Fallback: usa solo Slack se configurato
        def slackConfig = [:]
        if (env.SLACK_WEBHOOK_URL) slackConfig.webhookUrl = env.SLACK_WEBHOOK_URL
        if (env.SLACK_CHANNEL) slackConfig.channel = env.SLACK_CHANNEL
        if (slackConfig) channels.slack = slackConfig
    }

    return new NotifierHelper(this, channels)
}

return this