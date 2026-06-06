package io.awesome.jenkins

import org.junit.*
import static org.junit.Assert.*

/**
 * Test unitari per VersionHelper.
 * Richiede JenkinsPipelineUnit per esecuzione reale.
 */
class VersionHelperTest {

    VersionHelper helper

    @Before
    void setUp() {
        // Mock Script
        def mockScript = [
            sh: { Map cmd -> "" },
            echo: { String msg -> println msg },
            env: [:],
            readJSON: { Map p -> [version: '1.2.3'] },
            fileExists: { String f -> true },
            writeFile: { Map p -> },
            readFile: { Map p -> '# Changelog\n\n## [0.0.1] - 2024-01-01\n\n' }
        ] as Script

        helper = new VersionHelper(mockScript, 'node')
    }

    @Test
    void testCalculateNewVersion_Patch() {
        def result = helper.calculateNewVersion('1.2.3', 'patch')
        assertEquals('1.2.4', result)
    }

    @Test
    void testCalculateNewVersion_Minor() {
        def result = helper.calculateNewVersion('1.2.3', 'minor')
        assertEquals('1.3.0', result)
    }

    @Test
    void testCalculateNewVersion_Major() {
        def result = helper.calculateNewVersion('1.2.3', 'major')
        assertEquals('2.0.0', result)
    }

    @Test
    void testCalculateNewVersion_MajorFromZero() {
        def result = helper.calculateNewVersion('0.9.99', 'major')
        assertEquals('1.0.0', result)
    }

    @Test(expected = Exception)
    void testCalculateNewVersion_InvalidVersion() {
        helper.calculateNewVersion('not.semantic', 'patch')
    }

    @Test(expected = Exception)
    void testCalculateNewVersion_InvalidBumpType() {
        helper.calculateNewVersion('1.0.0', 'invalid')
    }
}

class UtilsTest {

    @Test
    void testFormatDuration() {
        assertEquals('0ms', Utils.formatDuration(0))
        assertEquals('500ms', Utils.formatDuration(500))
        assertEquals('1s 0ms', Utils.formatDuration(1000))
        assertEquals('1s 234ms', Utils.formatDuration(1234))
        assertEquals('1m 0s 0ms', Utils.formatDuration(60000))
        assertEquals('2m 34s 567ms', Utils.formatDuration(154567))
    }

    @Test
    void testSanitizeInput() {
        assertEquals('hello', Utils.sanitizeInput('hello'))
        assertEquals('ls _ la', Utils.sanitizeInput('ls; la'))
        assertEquals('safe_input_123', Utils.sanitizeInput('safe_input_123'))
        assertEquals('rm_rf__', Utils.sanitizeInput('rm -rf /'))
        assertEquals('', Utils.sanitizeInput(null))
    }
}

class VersionBumpDetectionTest {

    @Test
    void testDetectBumpType_BreakingChange() {
        def mockScript = [
            sh: { Map cmd -> return "feat: new API\n\nBREAKING CHANGE: old API removed" },
            echo: { String msg -> }
        ] as Script

        def helper = new VersionHelper(mockScript, 'node')
        // Nota: detectBumpTypeFromCommit fa log --format=%s che prende solo prima riga
        // Per test completo serve mock più sofisticato
    }
}