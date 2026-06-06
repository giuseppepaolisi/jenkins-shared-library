/**
 * dbMigrate.groovy — Database migrations enterprise (Flyway/Liquibase).
 *
 * Utilizzo:
 *   // Flyway
 *   dbMigrate.run(url: 'jdbc:postgresql://db:5432/myapp', env: 'staging',
 *       locations: 'filesystem:migrations',
 *       type: 'flyway', schema: 'public')
 *
 *   // Liquibase
 *   dbMigrate.run(url: 'jdbc:mysql://db:3306/myapp', env: 'prod',
 *       locations: 'filesystem:changelogs',
 *       type: 'liquibase')
 *
 *   // Step singoli
 *   dbMigrate.migrate('jdbc:postgresql://db:5432/myapp', 'staging')
 *   dbMigrate.rollback('jdbc:postgresql://db:5432/myapp', 2, 'staging')
 *   dbMigrate.validate('jdbc:postgresql://db:5432/myapp', 'staging')
 *   dbMigrate.repair('jdbc:postgresql://db:5432/myapp', 'staging')
 *   def info = dbMigrate.info('jdbc:postgresql://db:5432/myapp', 'staging')
 */
import io.awesome.jenkins.MigrationHelper

def run(Map params = [:]) {
    def type = params.type ?: 'flyway'
    def helper = new MigrationHelper(this, type)

    def url = params.url ?: error('[dbMigrate] JDBC URL richiesto')
    def locations = params.locations ?: 'filesystem:migrations'
    def env = params.env ?: ''

    helper.validate(url, [credsId: params.credsId ?: "db-${env}", schema: params.schema])
    helper.migrate(url, locations, env,
        [credsId: params.credsId ?: "db-${env}", schema: params.schema,
         baselineOnMigrate: params.baselineOnMigrate ?: false,
         table: params.table ?: 'flyway_schema_history'])

    return helper.info(url, [credsId: params.credsId ?: "db-${env}"])
}

def migrate(String url, String env = '', Map opts = [:]) {
    def helper = new MigrationHelper(this, opts.type ?: 'flyway')
    helper.migrate(url, opts.locations ?: 'filesystem:migrations', env,
        [credsId: opts.credsId ?: "db-${env}", schema: opts.schema])
}

def rollback(String url, int targetVersion, String env = '', Map opts = [:]) {
    def helper = new MigrationHelper(this, opts.type ?: 'flyway')
    helper.rollback(url, targetVersion,
        [credsId: opts.credsId ?: "db-${env}", schema: opts.schema])
}

def validate(String url, String env = '', Map opts = [:]) {
    def helper = new MigrationHelper(this, opts.type ?: 'flyway')
    helper.validate(url, [credsId: opts.credsId ?: "db-${env}"])
}

def repair(String url, String env = '', Map opts = [:]) {
    def helper = new MigrationHelper(this, opts.type ?: 'flyway')
    helper.repair(url, [credsId: opts.credsId ?: "db-${env}"])
}

def info(String url, String env = '') {
    def helper = new MigrationHelper(this, 'flyway')
    return helper.info(url, [credsId: "db-${env}"])
}

def baseline(String url, String version = '1', String desc = 'Baseline', Map opts = [:]) {
    def helper = new MigrationHelper(this, 'flyway')
    helper.baseline(url, version, desc,
        [credsId: opts.credsId ?: "db-${opts.env ?: 'dev'}"])
}

return this