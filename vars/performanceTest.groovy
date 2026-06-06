/**
 * performanceTest.groovy — Performance/Load testing enterprise (k6, Gatling, JMeter).
 *
 * Utilizzo:
 *   // k6 (raccomandato)
 *   performanceTest.k6('tests/load.js',
 *       stages: [[duration: '30s', target: 10], [duration: '1m', target: 50]],
 *       thresholds: ['http_req_duration': ['p(95)<500', 'p(99)<1000']],
 *       vus: 1, duration: '30s')
 *
 *   // Gatling
 *   performanceTest.gatling('simulations.LoadTest')
 *
 *   // JMeter
 *   performanceTest.jmeter('tests/load.jmx',
 *       threads: 10, duration: 60)
 *
 *   // Confronto con baseline
 *   performanceTest.compareWithBaseline('metrics.json')
 *
 * Configurazione via env:
 *   GATLING_HOME = '/opt/gatling'
 *   JMETER_HOME  = '/opt/jmeter'
 */
import io.awesome.jenkins.PerformanceHelper

def k6(String scriptPath, Map opts = [:]) {
    def helper = new PerformanceHelper(this, 'k6')
    helper.runK6(scriptPath,
        opts.stages ?: [],
        opts.thresholds ?: [:],
        opts.envVars ?: [:],
        opts)
}

def gatling(String simulationClass, Map opts = [:]) {
    def helper = new PerformanceHelper(this, 'gatling')
    helper.runGatling(simulationClass, opts.gatlingHome ?: '', opts)
}

def jmeter(String jmxPath, Map opts = [:]) {
    def helper = new PerformanceHelper(this, 'jmeter')
    helper.runJMeter(jmxPath, opts.props ?: [:], opts)
}

def compareWithBaseline(String metricFile) {
    def helper = new PerformanceHelper(this, 'k6')
    return helper.compareWithBaseline(metricFile)
}

return this