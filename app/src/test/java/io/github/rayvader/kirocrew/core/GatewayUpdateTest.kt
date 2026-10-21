package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayUpdateTest {

    private fun body(
        status: String = "succeeded",
        available: String = "true",
        latest: String = "\"0.12.0\"",
        display: String = "\"0.12.0\"",
        current: String = "\"0.11.0\"",
    ) = """{"check_status":"$status","update_available":$available,"latest_version":$latest,
        "latest_version_display":$display,"current_version":$current}"""

    @Test
    fun succeededCheckWithUpdate() {
        assertEquals(GatewayUpdate("0.12.0", "0.11.0"), GatewayUpdateParser.parse(body()))
    }

    @Test
    fun prefersTheDisplayVersion() {
        assertEquals("0.12.0", GatewayUpdateParser.parse(body(latest = "\"0.12.0rc2\""))!!.latest)
        assertEquals("0.12.0rc2", GatewayUpdateParser.parse(body(latest = "\"0.12.0rc2\"", display = "\"\""))!!.latest)
    }

    @Test
    fun upToDateIsNoUpdate() {
        assertNull(GatewayUpdateParser.parse(body(available = "false")))
    }

    @Test
    fun anUncheckedOrFailedCheckIsNoAnswer() {
        assertNull(GatewayUpdateParser.parse(body(status = "failed")))
        assertNull(GatewayUpdateParser.parse(body(status = "deferred")))
        assertNull(GatewayUpdateParser.parse(body(status = "unchecked")))
        assertNull(GatewayUpdateParser.parse(body(available = "null")))
    }

    @Test
    fun oddVersionsAreRefused() {
        assertNull(GatewayUpdateParser.parse(body(latest = "\"<b>x</b>\"", display = "null")))
        assertNull(GatewayUpdateParser.parse(body(latest = "\"" + "9".repeat(60) + "\"", display = "null")))
        assertNull(GatewayUpdateParser.parse(body(latest = "null", display = "null")))
    }

    @Test
    fun strayVPrefixIsDropped() {
        assertEquals("0.12.0", GatewayUpdateParser.parse(body(display = "\"v0.12.0\""))!!.latest)
    }

    @Test
    fun notJson() {
        assertNull(GatewayUpdateParser.parse("<html>sign in</html>"))
        assertNull(GatewayUpdateParser.parse(""))
    }
}
