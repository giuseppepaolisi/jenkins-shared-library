package io.awesome.jenkins

/**
 * Helper per database migrations enterprise: Flyway e Liquibase.
 * Gestisce migrazioni, rollback, repair, clean, validazione, e multi-database.
 */
class MigrationHelper implements Serializable {
    private static final long serialVersionUID = 1L

    private final Script script
    private final String type // 'flyway' | 'liquibase'
    private final String binary

    MigrationHelper(Script script, String type = 'flyway', String binary = '') {
        this.script = script
        this.type = type
        this.binary = binary ?: (type == 'flyway' ? 'flyway' : 'liquibase')
    }

    /**
     * Esegue migrazioni su database.
     * @param url JDBC URL del database
     * @param locations percorso script migration (classpath:db/migration, filesystem:migrations/)
     * @param env ambiente per selezione configurazione
     * @param extraParams parametri extra
     */
    def migrate(String url, String locations = 'filesystem:migrations',
                String env = '', Map extraParams = [:]) {
        script.echo "[Migration] ▶ ${type} migrate: ${sanitizeUrl(url)}"

        script.withCredentials([
            script.string(credentialsId: extraParams.credsId ?: "db-${env}-user",
                variable: 'DB_USER'),
            script.string(credentialsId: extraParams.credsId ?: "db-${env}-pass",
                variable: 'DB_PASS')
        ]) {
            switch (type) {
                case 'flyway':
                    script.retry(3) {
                        script.sh """
                            ${binary} -url='${url}' \
                                -user='${script.env.DB_USER}' \
                                -password='${script.env.DB_PASS}' \
                                -locations='${locations}' \
                                -connectRetries=5 \
                                ${extraParams.schema ? "-schemas='${extraParams.schema}'" : ''} \
                                ${extraParams.table ? "-table='${extraParams.table}'" : ''} \
                                ${extraParams.baselineOnMigrate ? '-baselineOnMigrate=true' : ''} \
                                migrate
                        """
                    }
                    break
                case 'liquibase':
                    script.retry(3) {
                        script.sh """
                            ${binary} --url='${url}' \
                                --username='${script.env.DB_USER}' \
                                --password='${script.env.DB_PASS}' \
                                --changeLogFile='${locations}/changelog-root.xml' \
                                --defaultSchemaName='${extraParams.schema ?: 'public'}' \
                                update
                        """
                    }
                    break
            }
        }

        script.echo "[Migration] ✓ Migrazioni completate"
    }

    /**
     * Flyway repair: repara schema history table.
     */
    def repair(String url, Map extraParams = [:]) {
        script.echo "[Migration] ▶ Repair: ${sanitizeUrl(url)}"
        script.withCredentials([
            script.string(credentialsId: extraParams.credsId ?: 'db-user',
                variable: 'DB_USER'),
            script.string(credentialsId: extraParams.credsId ?: 'db-pass',
                variable: 'DB_PASS')
        ]) {
            script.sh """
                ${binary} -url='${url}' \
                    -user='${script.env.DB_USER}' \
                    -password='${script.env.DB_PASS}' \
                    repair
            """
        }
    }

    /**
     * Flyway info: mostra stato migrazioni.
     */
    def info(String url, Map extraParams = [:]) {
        script.withCredentials([
            script.string(credentialsId: extraParams.credsId ?: 'db-user',
                variable: 'DB_USER'),
            script.string(credentialsId: extraParams.credsId ?: 'db-pass',
                variable: 'DB_PASS')
        ]) {
            def result = script.sh(
                script: """
                    ${binary} -url='${url}' \
                        -user='${script.env.DB_USER}' \
                        -password='${script.env.DB_PASS}' \
                        info -outputType=json 2>/dev/null || echo '{}'
                """,
                returnStdout: true
            )
            return script.readJSON(text: result)
        }
    }

    /**
     * Rollback migrazioni (Flyway undo, Liquibase rollback).
     */
    def rollback(String url, int targetVersion, Map extraParams = [:]) {
        script.echo "[Migration] ▶ Rollback a versione ${targetVersion}: ${sanitizeUrl(url)}"

        script.withCredentials([
            script.string(credentialsId: extraParams.credsId ?: 'db-user',
                variable: 'DB_USER'),
            script.string(credentialsId: extraParams.credsId ?: 'db-pass',
                variable: 'DB_PASS')
        ]) {
            switch (type) {
                case 'flyway':
                    script.sh """
                        ${binary} -url='${url}' \
                            -user='${script.env.DB_USER}' \
                            -password='${script.env.DB_PASS}' \
                            undo -target=${targetVersion}
                    """
                    break
                case 'liquibase':
                    script.sh """
                        ${binary} --url='${url}' \
                            --username='${script.env.DB_USER}' \
                            --password='${script.env.DB_PASS}' \
                            rollbackCount=${targetVersion}
                    """
                    break
            }
        }
    }

    /**
     * Flyway validate: controlla integrità migrazioni.
     */
    def validate(String url, Map extraParams = [:]) {
        script.echo "[Migration] ▶ Validate: ${sanitizeUrl(url)}"
        script.withCredentials([
            script.string(credentialsId: extraParams.credsId ?: 'db-user',
                variable: 'DB_USER'),
            script.string(credentialsId: extraParams.credsId ?: 'db-pass',
                variable: 'DB_PASS')
        ]) {
            script.sh """
                ${binary} -url='${url}' \
                    -user='${script.env.DB_USER}' \
                    -password='${script.env.DB_PASS}' \
                    validate
            """
        }
    }

    /**
     * Baseline: marca tutte le migrazioni esistenti come applicate.
     */
    def baseline(String url, String baselineVersion = '1', String baselineDesc = 'Baseline',
                 Map extraParams = [:]) {
        script.echo "[Migration] ▶ Baseline ${baselineVersion}: ${sanitizeUrl(url)}"
        script.withCredentials([
            script.string(credentialsId: extraParams.credsId ?: 'db-user',
                variable: 'DB_USER'),
            script.string(credentialsId: extraParams.credsId ?: 'db-pass',
                variable: 'DB_PASS')
        ]) {
            script.sh """
                ${binary} -url='${url}' \
                    -user='${script.env.DB_USER}' \
                    -password='${script.env.DB_PASS}' \
                    -baselineVersion=${baselineVersion} \
                    -baselineDescription='${baselineDesc}' \
                    baseline
            """
        }
    }

    /**
     * Esecuzione completa: validate → migrate → info.
     */
    def runPipeline(String url, String locations = 'filesystem:migrations',
                    String env = '', Map extraParams = [:]) {
        validate(url, extraParams)
        migrate(url, locations, env, extraParams)
        return info(url, extraParams)
    }

    @NonCPS
    private String sanitizeUrl(String url) {
        return url.replaceAll(/(password|passwd)=[^&]+/, 'password=****')
    }
}