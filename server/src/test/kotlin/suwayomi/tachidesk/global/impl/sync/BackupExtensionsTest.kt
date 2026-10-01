package suwayomi.tachidesk.global.impl.sync

import kotlinx.serialization.protobuf.ProtoBuf
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The extension stores and installed extensions have to survive a backup, and older backups without them still load. */
class BackupExtensionsTest {
    private val proto = ProtoBuf

    @Test
    fun `extension stores and installed extensions round trip`() {
        val backup =
            Backup(
                extensionStores = listOf("https://example.org/repo/index.min.json", "https://other.example/index.json"),
                backupExtensions =
                    listOf(
                        BackupExtension("eu.kanade.tachiyomi.extension.en.one", "Tachiyomi: One", "https://example.org/repo/index.min.json"),
                        BackupExtension("eu.kanade.tachiyomi.extension.en.two", "Tachiyomi: Two", "https://other.example/index.json"),
                    ),
            )

        val decoded = proto.decodeFromByteArray(Backup.serializer(), proto.encodeToByteArray(Backup.serializer(), backup))

        assertEquals(backup.extensionStores, decoded.extensionStores)
        assertEquals(backup.backupExtensions, decoded.backupExtensions)
    }

    @Test
    fun `a backup without the extension fields still decodes`() {
        val decoded = proto.decodeFromByteArray(Backup.serializer(), proto.encodeToByteArray(Backup.serializer(), Backup()))

        assertTrue(decoded.extensionStores.isEmpty())
        assertTrue(decoded.backupExtensions.isEmpty())
    }
}
