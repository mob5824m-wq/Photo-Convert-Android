package com.pixelnas.household

import com.pixelnas.household.model.SmbAuthMode
import com.pixelnas.household.model.SmbConnectionConfig
import com.pixelnas.household.model.SmbRemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdModelsTest {

    @Test
    fun `destinationRelativeDir resolves default profile custom folder and member routing`() {
        val saverCfg = SmbConnectionConfig(
            host = "192.168.1.50",
            port = 4445,
            shareName = "PixelNAS",
            baseSubfolder = "Incoming",
            targetProfileFolder = "StorageSaver"
        )
        assertEquals("Incoming\\StorageSaver", saverCfg.destinationRelativeDir())
        assertEquals("\\\\192.168.1.50\\PixelNAS\\Incoming\\StorageSaver", saverCfg.displayUnc)
        assertEquals("smb://192.168.1.50:4445/PixelNAS/Incoming/StorageSaver/", saverCfg.displaySmbUrl)

        val memberCfg = saverCfg.copy(
            port = 445,
            memberName = "Mom",
            routeToMemberSubfolder = true
        )
        assertEquals("Incoming\\StorageSaver\\Mom", memberCfg.destinationRelativeDir())
        assertEquals("smb://192.168.1.50/PixelNAS/Incoming/StorageSaver/Mom/", memberCfg.displaySmbUrl)

        val directShareCfg = SmbConnectionConfig(
            host = "192.168.1.10",
            port = 445,
            shareName = "Incoming",
            baseSubfolder = "Incoming",
            targetProfileFolder = "Family",
            authMode = SmbAuthMode.CREDENTIALS,
            username = "household",
            password = "secret"
        )
        assertEquals("Family", directShareCfg.destinationRelativeDir())
    }

    @Test
    fun `parsePairingUri decodes pixelnas deep links and SmbRemoteEntry detects previewable images`() {
        val parsed = SmbConnectionConfig.parsePairingUri(
            "pixelnas://pair?host=192.168.1.77&port=4445&share=PixelNAS&auth=credentials&user=family&workgroup=HOME"
        )
        assertNotNull(parsed)
        assertEquals("192.168.1.77", parsed!!.host)
        assertEquals(4445, parsed.port)
        assertEquals("PixelNAS", parsed.shareName)
        assertEquals(SmbAuthMode.CREDENTIALS, parsed.authMode)
        assertEquals("family", parsed.username)
        assertEquals("HOME", parsed.workgroup)

        assertNull(SmbConnectionConfig.parsePairingUri("https://example.com/not-a-pair-link"))

        val photoEntry = SmbRemoteEntry("PXL_20261026.jpg", "Output\\PXL_20261026.jpg", false, 1024L, 0L)
        val videoEntry = SmbRemoteEntry("VID_20261026.mp4", "Output\\VID_20261026.mp4", false, 8192L, 0L)
        val dirEntry = SmbRemoteEntry("PublicShare", "Output\\PublicShare", true, 0L, 0L)
        assertTrue(photoEntry.isPreviewableImage)
        assertFalse(videoEntry.isPreviewableImage)
        assertFalse(dirEntry.isPreviewableImage)
    }
}
