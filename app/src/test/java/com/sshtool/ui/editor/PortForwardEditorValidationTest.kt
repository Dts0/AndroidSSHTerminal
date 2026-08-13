package com.sshtool.ui.editor

import com.sshtool.data.model.PortForwardDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortForwardEditorValidationTest {

    @Test
    fun loopbackWarningRecognizesIpv4Ipv6AndMappedLoopback() {
        assertTrue("localhost".isLoopbackPortForwardAddress())
        assertTrue("api.localhost.".isLoopbackPortForwardAddress())
        assertTrue("127.9.8.7".isLoopbackPortForwardAddress())
        assertTrue("[::1]".isLoopbackPortForwardAddress())
        assertTrue("0:0:0:0:0:0:0:1".isLoopbackPortForwardAddress())
        assertTrue("::ffff:127.0.0.1".isLoopbackPortForwardAddress())

        assertFalse("0.0.0.0".isLoopbackPortForwardAddress())
        assertFalse("::".isLoopbackPortForwardAddress())
        assertFalse("example.com".isLoopbackPortForwardAddress())
    }

    @Test
    fun localDuplicatesMatchJschAliasesAndWildcardOverlap() {
        assertTrue(
            portForwardBindAddressesConflict(
                PortForwardDirection.LOCAL,
                "localhost",
                "127.0.0.1"
            )
        )
        assertTrue(
            portForwardBindAddressesConflict(
                PortForwardDirection.LOCAL,
                "*",
                "192.0.2.1"
            )
        )
        assertFalse(
            portForwardBindAddressesConflict(
                PortForwardDirection.LOCAL,
                "127.0.0.1",
                "192.0.2.1"
            )
        )
    }

    @Test
    fun remoteDuplicatesDoNotConfuseWildcardWithIpv4Wildcard() {
        assertTrue(
            portForwardBindAddressesConflict(
                PortForwardDirection.REMOTE,
                "Example.COM",
                "example.com"
            )
        )
        assertFalse(
            portForwardBindAddressesConflict(
                PortForwardDirection.REMOTE,
                "*",
                "0.0.0.0"
            )
        )
        assertFalse(
            portForwardBindAddressesConflict(
                PortForwardDirection.REMOTE,
                "localhost",
                "127.0.0.1"
            )
        )
    }
}
