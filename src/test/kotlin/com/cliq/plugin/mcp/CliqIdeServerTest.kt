package com.cliq.plugin.mcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.components.service
import java.net.HttpURLConnection
import java.net.URL

class CliqIdeServerTest : BasePlatformTestCase() {

    private fun waitForServer(server: CliqIdeServer): Int {
        server.start()
        for (i in 1..50) {
            val port = server.port()
            if (port != null) return port
            Thread.sleep(100)
        }
        error("Server did not start in time")
    }

    fun testServerStartsAndRejectsUnauthorized() {
        val server = project.service<CliqIdeServer>()
        val port = waitForServer(server)
        
        val url = URL("http://127.0.0.1:$port/mcp")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        
        assertEquals(401, connection.responseCode)
    }

    fun testServerAcceptsWithCorrectToken() {
        val server = project.service<CliqIdeServer>()
        val port = waitForServer(server)
        val token = server.authToken()
        
        val url = URL("http://127.0.0.1:$port/mcp")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $token")
        
        val code = connection.responseCode
        assertTrue(code != 401)
    }
}
