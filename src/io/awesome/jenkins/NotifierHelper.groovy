package io.awesome.jenkins

/**
 * Notifier multi-canale enterprise: Slack, Microsoft Teams, Email, PagerDuty.
 * Gestisce routing per severità e ambiente, rate limiting, templating.
 */
class NotifierHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final Map<String, Map> channels // [slack: [...], teams: [...], email: [...], pagerduty: [...]]

    NotifierHelper(Script script, Map<String, Map> channels = [:]) {
        this.script = script
        this.channels = channels
    }

    /**
     * Notifica su tutti i canali configurati.
     */
    def notify(String severity, String title, String message, Map opts = [:]) {
        script.echo "[Notifier] ▶ ${severity}: ${title}"

        def results = [:]
        channels.each { channel, config ->
            try {
                switch (channel) {
                    case 'slack':
                        results.slack = notifySlack(severity, title, message, opts)
                        break
                    case 'teams':
                        results.teams = notifyTeams(severity, title, message, opts)
                        break
                    case 'email':
                        results.email = notifyEmail(severity, title, message, opts)
                        break
                    case 'pagerduty':
                        results.pagerduty = notifyPagerDuty(severity, title, message, opts)
                        break
                }
            } catch (Exception e) {
                script.echo "[Notifier] ⚠ Fallito ${channel}: ${e.message}"
                results[channel] = "ERROR: ${e.message}"
            }
        }
        return results
    }

    /**
     * Notifica inizio pipeline.
     */
    def notifyStart(String jobName, String environment = '', Map extra = [:]) {
        notify('info', "⚡ Build avviata: ${jobName}",
               "Branch: ${script.env.BRANCH_NAME}, Build: ${script.env.BUILD_NUMBER}" +
               (environment ? ", Ambiente: ${environment}" : "") +
               (extra.commit ? ", Commit: ${extra.commit}" : "") +
               (extra.version ? ", Versione: ${extra.version}" : ""),
               [environment: environment, job: jobName] + extra)
    }

    /**
     * Notifica successo.
     */
    def notifySuccess(String jobName, String duration = '', String environment = '',
                      Map extra = [:]) {
        notify('success', "✅ Build completata: ${jobName}",
               "Durata: ${duration}" +
               (environment ? ", Ambiente: ${environment}" : "") +
               (extra.changelog ? "\nChangelog: ${extra.changelog}" : ""),
               [environment: environment, job: jobName] + extra)
    }

    /**
     * Notifica fallimento con escalation.
     */
    def notifyFailure(String jobName, String errorDetails, String environment = '',
                      String failedStage = '', Map extra = [:]) {
        def severity = (environment == 'prod' || environment == 'production') ? 'critical' : 'error'
        def title = "❌ Build FALLITA: ${jobName}"
        def msg = "Stage: ${failedStage ?: 'N/D'}\nErrore: ${errorDetails?.take(1000)}"

        notify(severity, title, msg, [environment: environment, job: jobName] + extra)
    }

    /**
     * Notifica deploy.
     */
    def notifyDeploy(String environment, String version, String status = 'started',
                     String duration = '', Map extra = [:]) {
        def (emoji, severity) = [status: 'started' ? ['🚀', 'info'] :
                                 status: 'success' ? ['✅', 'success'] :
                                 status: 'rollback' ? ['⏪', 'warning'] :
                                 ['❌', 'critical']]

        notify(severity, "${emoji} Deploy ${status}: ${environment}",
               "Versione: ${version}, Durata: ${duration}" +
               (extra.releaseNotes ? "\nNote: ${extra.releaseNotes}" : ""),
               [environment: environment] + extra)
    }

    // --- Implementazioni specifiche per canale ---

    @NonCPS
    private def notifySlack(String severity, String title, String message, Map opts) {
        def config = channels.slack ?: [:]
        def webhookUrl = config.webhookUrl ?: script.env.SLACK_WEBHOOK_URL ?: ''

        def color = severity == 'critical' ? '#e74c3c' :
                    severity == 'error'    ? '#e67e22' :
                    severity == 'warning'  ? '#f1c40f' :
                    severity == 'success'  ? '#2ecc71' : '#3498db'

        def attachments = [[
            color: color,
            title: title,
            text: message,
            fields: [
                [title: 'Job', value: opts.job ?: script.env.JOB_NAME ?: '', short: true],
                [title: 'Build', value: script.env.BUILD_NUMBER ?: '', short: true],
                [title: 'Ambiente', value: opts.environment ?: 'N/D', short: true]
            ],
            footer: "<${script.env.BUILD_URL ?: ''}|Apri in Jenkins>"
        ]]

        // Mention su criticità
        if (severity == 'critical' || severity == 'error') {
            attachments[0].text = "${opts.environment == 'prod' ? '<!here> ' : ''}${message}"
        }

        def payload = [
            channel: opts.channel ?: config.channel ?: '#ci-general',
            username: 'Jenkins CI',
            icon_emoji: ':jenkins:',
            attachments: attachments
        ]

        def jsonPayload = script.writeJSON(json: payload, returnText: true)
        script.sh """
            curl -s -X POST -H 'Content-Type: application/json' \
                -d '${jsonPayload.replace("'", "'\\''")}' \
                '${webhookUrl}' 2>/dev/null || echo 'Slack notification failed'
        """
    }

    @NonCPS
    private def notifyTeams(String severity, String title, String message, Map opts) {
        def config = channels.teams ?: [:]
        def webhookUrl = config.webhookUrl ?: script.env.TEAMS_WEBHOOK_URL ?: ''
        if (!webhookUrl) {
            script.echo "[Notifier] Teams webhook non configurato"
            return
        }

        def color = severity == 'critical' ? 'ff0000' :
                    severity == 'error'    ? 'ff6600' :
                    severity == 'warning'  ? 'ffcc00' :
                    severity == 'success'  ? '00cc66' : '0066cc'

        def payload = [
            '@type': 'MessageCard',
            '@context': 'http://schema.org/extensions',
            themeColor: color,
            title: title,
            text: message,
            sections: [[
                facts: [
                    [name: 'Job', value: opts.job ?: script.env.JOB_NAME ?: ''],
                    [name: 'Build', value: script.env.BUILD_NUMBER ?: ''],
                    [name: 'Environment', value: opts.environment ?: 'N/D']
                ],
                markdown: true
            ]],
            potentialAction: [[
                '@type': 'OpenUri',
                name: 'Apri in Jenkins',
                targets: [[os: 'default', uri: script.env.BUILD_URL ?: '']]
            ]]
        ]

        def jsonPayload = script.writeJSON(json: payload, returnText: true)
        script.sh """
            curl -s -X POST -H 'Content-Type: application/json' \
                -d '${jsonPayload.replace("'", "'\\''")}' \
                '${webhookUrl}' 2>/dev/null || echo 'Teams notification failed'
        """
    }

    @NonCPS
    private def notifyEmail(String severity, String title, String message, Map opts) {
        def config = channels.email ?: [:]
        def to = opts.to ?: config.to ?: ''
        def from = config.from ?: 'jenkins@azienda.local'
        def subject = "[${severity.toUpperCase()}] ${title}"

        if (!to) {
            script.echo "[Notifier] Email destinatario non configurato"
            return
        }

        def body = """
<html><body style="font-family: Arial, sans-serif;">
<h2 style="color: ${severity == 'critical' ? 'red' : severity == 'success' ? 'green' : 'blue'}">${title}</h2>
<p>${message.replace('\n', '<br>')}</p>
<table border="1" cellpadding="5" style="border-collapse:collapse; margin-top:15px;">
<tr><td><b>Job</b></td><td>${opts.job ?: script.env.JOB_NAME ?: ''}</td></tr>
<tr><td><b>Build #</b></td><td>${script.env.BUILD_NUMBER ?: ''}</td></tr>
<tr><td><b>Branch</b></td><td>${script.env.BRANCH_NAME ?: ''}</td></tr>
<tr><td><b>Environment</b></td><td>${opts.environment ?: 'N/D'}</td></tr>
</table>
<p><a href="${script.env.BUILD_URL ?: '#'}">Apri in Jenkins</a></p>
</body></html>
"""

        script.emailext(
            to: to,
            from: from,
            subject: subject,
            mimeType: 'text/html',
            body: body
        )
    }

    @NonCPS
    private def notifyPagerDuty(String severity, String title, String message, Map opts) {
        if (severity != 'critical' && severity != 'error') {
            script.echo "[Notifier] PagerDuty: skip per severità ${severity}"
            return
        }

        def config = channels.pagerduty ?: [:]
        def routingKey = config.routingKey ?: script.env.PAGERDUTY_ROUTING_KEY ?: ''
        if (!routingKey) {
            script.echo "[Notifier] PagerDuty routing key non configurata"
            return
        }

        def pdSeverity = severity == 'critical' ? 'critical' : 'error'
        def payload = [
            routing_key: routingKey,
            event_action: 'trigger',
            dedup_key: "${script.env.JOB_NAME}-${script.env.BUILD_NUMBER}",
            payload: [
                summary: title.take(1024),
                source: script.env.JOB_NAME ?: 'Jenkins',
                severity: pdSeverity,
                timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC')),
                component: opts.component ?: opts.job ?: script.env.JOB_NAME ?: 'unknown',
                group: opts.environment ?: 'N/D',
                class: opts.failedStage ?: 'deploy',
                custom_details: [
                    build_number: script.env.BUILD_NUMBER ?: '',
                    branch: script.env.BRANCH_NAME ?: '',
                    build_url: script.env.BUILD_URL ?: '',
                    message: message.take(1000)
                ]
            ]
        ]

        def jsonPayload = script.writeJSON(json: payload, returnText: true)
        script.sh """
            curl -s -X POST -H 'Content-Type: application/json' \
                -d '${jsonPayload.replace("'", "'\\''")}' \
                'https://events.pagerduty.com/v2/enqueue' 2>/dev/null || echo 'PagerDuty notification failed'
        """
    }

    /**
     * Resolve un incidente PagerDuty (per notifiche di successo dopo un errore).
     */
    @NonCPS
    def resolvePagerDuty(String dedupKey) {
        def routingKey = channels.pagerduty?.routingKey ?: script.env.PAGERDUTY_ROUTING_KEY ?: ''
        if (!routingKey) return

        def payload = [
            routing_key: routingKey,
            event_action: 'resolve',
            dedup_key: dedupKey
        ]

        def jsonPayload = script.writeJSON(json: payload, returnText: true)
        script.sh """
            curl -s -X POST -H 'Content-Type: application/json' \
                -d '${jsonPayload.replace("'", "'\\''")}' \
                'https://events.pagerduty.com/v2/enqueue' 2>/dev/null || true
        """
    }
}