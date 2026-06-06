/**
 * cacheOps.groovy — Dependency caching enterprise multi-tool.
 *
 * Utilizzo:
 *   // Cache npm con pipeline completa
 *   cacheOps.withCache('npm') {
 *       sh 'npm ci'
 *   }
 *
 *   // Cache solo restore
 *   cacheOps.restore('npm')
 *
 *   // Cache solo save
 *   cacheOps.save('npm')
 *
 *   // Tipi supportati: npm, maven, gradle, pip, go
 *   cacheOps.withCache('maven') {
 *       sh 'mvn clean package'
 *   }
 *
 *   cacheOps.withCache('go') {
 *       sh 'go build ./...'
 *   }
 *
 * Configurazione via env:
 *   CACHE_TYPE    = 's3'        // s3, nexus, jenkins, local
 *   CACHE_S3_BUCKET = 'jenkins-cache'
 *   CACHE_REGISTRY  = 'nexus.azienda.local'  // per Docker layer cache
 */
import io.awesome.jenkins.CacheHelper

def withCache(String tool, Closure buildClosure, Map opts = [:]) {
    def cacheType = env.CACHE_TYPE ?: 's3'
    def config = [s3Bucket: env.CACHE_S3_BUCKET ?: 'jenkins-cache']
    def helper = new CacheHelper(this, cacheType, config)
    return helper.withCache(tool, buildClosure, opts)
}

def restore(String tool, Map opts = [:]) {
    def cacheType = env.CACHE_TYPE ?: 's3'
    def config = [s3Bucket: env.CACHE_S3_BUCKET ?: 'jenkins-cache']
    def helper = new CacheHelper(this, cacheType, config)

    switch (tool) {
        case 'npm': helper.npmCache('restore', opts); break
        case 'maven': helper.mavenCache('restore', opts); break
        case 'gradle': helper.gradleCache('restore', opts); break
        case 'pip': helper.pipCache('restore', opts); break
        case 'go': helper.goCache('restore', opts); break
        default: error("[cacheOps] Tool sconosciuto: ${tool}")
    }
}

def save(String tool, Map opts = [:]) {
    def cacheType = env.CACHE_TYPE ?: 's3'
    def config = [s3Bucket: env.CACHE_S3_BUCKET ?: 'jenkins-cache']
    def helper = new CacheHelper(this, cacheType, config)

    switch (tool) {
        case 'npm': helper.npmCache('save', opts); break
        case 'maven': helper.mavenCache('save', opts); break
        case 'gradle': helper.gradleCache('save', opts); break
        case 'pip': helper.pipCache('save', opts); break
        case 'go': helper.goCache('save', opts); break
        default: error("[cacheOps] Tool sconosciuto: ${tool}")
    }
}

return this