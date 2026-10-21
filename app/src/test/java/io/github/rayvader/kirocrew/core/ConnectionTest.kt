package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTest {

    @Test
    fun anyHttpAnswerIsUp() {
        assertEquals(Connection.UP, Connection.fromHttpCode(200))
        assertEquals(Connection.UP, Connection.fromHttpCode(401)) // signed out, but the server is there
        assertEquals(Connection.UP, Connection.fromHttpCode(503))
    }

    @Test
    fun noAnswerIsDown() {
        assertEquals(Connection.DOWN, Connection.fromHttpCode(null))
        assertEquals(Connection.DOWN, Connection.fromHttpCode(-1)) // HttpURLConnection: not valid HTTP
    }

    @Test
    fun healthUrlIsOnTheServer() {
        assertEquals("http://10.0.2.2:8000/api/health", Connection.healthUrl("http://10.0.2.2:8000"))
        assertEquals("https://kiro.example.com/api/health", Connection.healthUrl("https://kiro.example.com/"))
    }
}
