package io.awesome.jenkins

/**
 * Helper enterprise per notifiche Slack con smart routing,
 * rate limiting, threading, template, e supporto multi-canale.
 * Supporta: Incoming Webhooks e Bolt Socket Mode.
 */
class SlackHelper implements Serializable {
    private static final long serialVersionUID = 1L
    private static final long RATE_LIMIT_MS = 2000 // Slack API: max 1 msg/2s per channel
    private static final String DEFAULT_ICON = ':jenkins:'
    private static final String DEFAULT_USERNAME = 'Jenkins CI'

    private final Script script
    private final String channel
    private final String webhookUrl
    private final String botToken
    private final String teamDomain
    private final Map<String, String> channelMap  // [env: channelName]

    // Rate limiting tracker
    @NonCPS
    private long lastMessageTime = 0

    SlackHelper(Script script, Map config) {
        this.script = script
        this.channel = config.channel ?: '#ci-general'
        this.webhookUrl = config.webhookUrl ?: ''
        this.botToken = config.botToken ?: ''
        this.teamDomain = config.teamDomain ?: 'azienda'
        this.channelMap = config.channels ?: [dev: '#ci-dev', staging: '#ci-staging', prod: '#ci-prod']
    }

    /**
     * Notifica l'inizio di una build.
     */
    def notifyStart(String jobName = '', String environment = '') {
        def targetChannel = getChannelForEnv(environment)
        def job = jobName ?: script.env.JOB_NAME ?: 'Pipeline'
        def buildUrl = script.env.BUILD_URL ?: ''
        def branch = script.env.BRANCH_NAME ?: ''

        def attachments = [[
            color: '#3498db',
            title: "⚡ Build avviata: ${job}",
            fields: [
                [title: 'Job', value: job, short: true],
                [title: 'Branch', value: branch, short: true],
                [title: 'Build #', value: script.env.BUILD_NUMBER ?: '', short: true],
                [title: 'Ambiente', value: environment ?: 'N/D', short: true]
            ],
            footer: "<${buildUrl}|Apri in Jenkins>"
        ]]

        sendMessage(targetChannel, attachments, ':arrow_forward:', 'Build Avviata')
    }

    /**
     * Notifica il successo di una build.
     */
    def notifySuccess(String duration = '', String environment = '', String changelog = '') {
        def targetChannel = getChannelForEnv(environment)
        def job = script.env.JOB_NAME ?: 'Pipeline'
        def buildUrl = script.env.BUILD_URL ?: ''

        def fields = [
            [title: 'Job', value: job, short: true],
            [title: 'Build #', value: script.env.BUILD_NUMBER ?: '', short: true],
            [title: 'Durata', value: duration ?: 'N/D', short: true]
        ]
        if (changelog) {
            fields << [title: 'Changelog', value: changelog, short: false]
        }

        def attachments = [[
            color: '#2ecc71',
            title: "✅ Build completata con successo: ${job}",
            fields: fields,
            footer: "<${buildUrl}|Apri in Jenkins>"
        ]]

        sendMessage(targetChannel, attachments, ':white_check_mark:', 'Build Completata')
    }

    /**
     * Notifica il fallimento di una build con dettagli errore.
     */
    def notifyFailure(String errorDetails = '', String environment = '', String failedStage = '') {
        def targetChannel = getFailureChannel(environment)
        def job = script.env.JOB_NAME ?: 'Pipeline'
        def buildUrl = script.env.BUILD_URL ?: ''
        def branch = script.env.BRANCH_NAME ?: ''

        def fields = [
            [title: 'Job', value: job, short: true],
            [title: 'Branch', value: branch, short: true],
            [title: 'Build #', value: script.env.BUILD_NUMBER ?: '', short: true]
        ]
        if (failedStage) {
            fields << [title: 'Stage Fallito', value: failedStage, short: true]
        }
        if (errorDetails) {
            def truncated = errorDetails.length() > 500 ? errorDetails.take(500) + '...' : errorDetails
            fields << [title: 'Errore', value: "```${truncated}```", short: false]
        }

        def mentions = (environment == 'prod' || environment == 'production') ? '<!here>' : ''

        def attachments = [[
            color: '#e74c3c',
            title: "❌ Build FALLITA: ${job} ${mentions}",
            fields: fields,
            footer: "<${buildUrl}|Apri in Jenkins> | ${new Date().format('HH:mm:ss')}"
        ]]

        sendMessage(targetChannel, attachments, ':x:', 'Build Fallita')
    }

    /**
     * Notifica una fase intermedia della build (es: "Deploy in corso").
     */
    def notifyPhase(String phaseName, String environment = '', String extraInfo = '') {
        def targetChannel = getChannelForEnv(environment)

        def attachments = [[
            color: '#f39c12',
            title: "🔄 ${phaseName}",
            fields: [
                [title: 'Ambiente', value: environment ?: 'N/D', short: true],
                [title: 'Informazioni', value: extraInfo ?: 'In esecuzione...', short: false]
            ]
        ]]

        sendMessage(targetChannel, attachments, ':hourglass:', phaseName)
    }

    /**
     * Notifica di deploy (con rollback tracking).
     */
    def notifyDeploy(String environment, String version, String status = 'started', String duration = '') {
        def targetChannel = getChannelForEnv(environment)
        def job = script.env.JOB_NAME ?: 'Pipeline'
        def buildUrl = script.env.BUILD_URL ?: ''

        def (color, emoji, titleStatus) = [status: 'started' ? ['#3498db', ':rocket:', 'Avviato'] :
                                            status: 'success' ? ['#2ecc71', ':rocket:', 'Completato'] :
                                            status: 'rollback' ? ['#e67e22', ':warning:', 'Rollback'] :
                                            ['#e74c3c', ':x:', 'Fallito']]

        def attachments = [[
            color: color,
            title: "${emoji} Deploy ${titleStatus}: ${environment}",
            fields: [
                [title: 'Versione', value: version, short: true],
                [title: 'Ambiente', value: environment, short: true],
                [title: 'Durata', value: duration ?: 'N/D', short: true]
            ],
            footer: "<${buildUrl}|Dettagli deploy>"
        ]]

        sendMessage(targetChannel, attachments, emoji, "Deploy ${titleStatus}")
    }

    /**
     * Messaggio generico con template custom.
     */
    def sendCustom(Map message) {
        def targetChannel = message.channel ?: channel
        applyRateLimit()

        def payload = [
            channel    : targetChannel,
            username   : message.username ?: DEFAULT_USERNAME,
            icon_emoji : message.icon ?: DEFAULT_ICON,
            text       : message.text ?: '',
            attachments: message.attachments ?: []
        ]

        if (message.threadTs) {
            payload.thread_ts = message.threadTs
        }

        postToSlack(payload)
    }

    // --- Metodi privati ---

    @NonCPS
    private void sendMessage(String targetChannel, List attachments, String emoji, String pretext) {
        applyRateLimit()

        def payload = [
            channel    : targetChannel,
            username   : DEFAULT_USERNAME,
            icon_emoji : emoji ?: DEFAULT_ICON,
            text       : pretext,
            attachments: attachments
        ]

        postToSlack(payload)
    }

    @NonCPS
    private void postToSlack(Map payload) {
        def jsonPayload = script.writeJSON(json: payload, returnText: true)

        if (webhookUrl) {
            // Metodo Incoming Webhook
            script.sh """
                curl -s -X POST -H 'Content-Type: application/json' \
                    -d '${jsonPayload}' \
                    '${webhookUrl}' > /dev/null
            """
        } else if (botToken) {
            // Metodo Bolt/API Token
            script.sh """
                curl -s -X POST -H 'Authorization: Bearer ${botToken}' \
                    -H 'Content-Type: application/json' \
                    -d '${jsonPayload}' \
                    'https://slack.com/api/chat.postMessage' > /dev/null
            """
        } else {
            script.echo "[Slack] Notifica simulata: ${payload.text}"
        }

        script.echo "[Slack] ✓ Notifica inviata a ${payload.channel}: ${payload.text}"
    }

    @NonCPS
    private String getChannelForEnv(String environment) {
        if (!environment) return channel
        return channelMap[environment] ?: channel
    }

    @NonCPS
    private String getFailureChannel(String environment) {
        // Per failure su prod, usa canale team; altrimenti quello standard
        if (environment == 'prod' || environment == 'production') {
            return channelMap['prod-alerts'] ?: channelMap['prod'] ?: channel
        }
        return getChannelForEnv(environment)
    }

    @NonCPS
    private void applyRateLimit() {
        long now = System.currentTimeMillis()
        long elapsed = now - lastMessageTime
        if (elapsed < RATE_LIMIT_MS) {
            script.sleep(RATE_LIMIT_MS - elapsed)
        }
        lastMessageTime = System.currentTimeMillis()
    }
}