package com.sshtool.background

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class KeepAliveManifestTest {
    @Test fun serviceIsPrivateTypedAndReceivesTaskRemovalCallbacks() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(File(root, "app/src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttribute("android:name") == ".background.KeepAliveService" }
        assertEquals("false", service.getAttribute("android:exported"))
        // stopWithTask=true skips onTaskRemoved and would leave the SSH owner unnotified.
        assertEquals("false", service.getAttribute("android:stopWithTask"))
        assertEquals("specialUse", service.getAttribute("android:foregroundServiceType"))
        val property = service.getElementsByTagName("property").item(0) as Element
        assertEquals("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", property.getAttribute("android:name"))
        assertTrue(property.getAttribute("android:value").isNotBlank())
        val permissionNodes = doc.getElementsByTagName("uses-permission")
        val permissions = (0 until permissionNodes.length).map {
            (permissionNodes.item(it) as Element).getAttribute("android:name")
        }.toSet()
        assertTrue(permissions.containsAll(listOf(
            "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.WAKE_LOCK", "android.permission.POST_NOTIFICATIONS"
        )))
        assertFalse(permissions.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    }
}
