package com.cleartune.data.download

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFilePolicyTest {
    @Test
    fun `paths stay inside root and use a partial suffix`() {
        val root = Files.createTempDirectory("downloads").toFile()
        val paths = DownloadFilePolicy(root).paths("../source", "track/../../id", "../My Song.flac")

        assertTrue(paths.finalFile.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator))
        assertTrue(paths.partialFile.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator))
        assertEquals(paths.finalFile.name + ".part", paths.partialFile.name)
        assertFalse(paths.finalFile.path.contains(".."))
    }

    @Test
    fun `unsafe and reserved names become short deterministic file names`() {
        val root = Files.createTempDirectory("downloads").toFile()
        val policy = DownloadFilePolicy(root)

        val first = policy.paths("source", "track", "CON<>:\\/*?\u0000.flac")
        val second = policy.paths("source", "track", "CON<>:\\/*?\u0000.flac")

        assertEquals(first.finalFile, second.finalFile)
        assertTrue(first.finalFile.name.endsWith(".flac"))
        assertTrue(first.finalFile.name.length <= 120)
        assertFalse(first.finalFile.nameWithoutExtension.equals("CON", ignoreCase = true))
    }
}
