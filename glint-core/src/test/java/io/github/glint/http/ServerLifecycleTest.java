package io.github.glint.http;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/** Bind address and the Windows high-resolution timer keeper follow the server lifecycle. */
class ServerLifecycleTest {

    @Test
    void bindsConfiguredAddressAndReleasesTimer() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        VtServer server = new VtServer(0, (req, resp) -> resp.text("ok"),
                ServerConfig.builder().bindAddress(loopback).build());
        try {
            server.start();
            assertTrue(server.getPort() > 0);
            assertEquals(windows, HighResolutionTimer.active());
        } finally {
            server.close();
        }
        assertFalse(HighResolutionTimer.active());
    }

    @Test
    void timerCanBeDisabled() throws Exception {
        VtServer server = new VtServer(0, (req, resp) -> resp.text("ok"),
                ServerConfig.builder().highResolutionTimer(false).build());
        try {
            server.start();
            assertFalse(HighResolutionTimer.active());
        } finally {
            server.close();
        }
    }
}