package suwayomi.tachidesk.global.impl.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupCategory
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupExtension
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A backup made by this fork has to stay importable by the official server. This is that server's backup
 * model as far as it matters here: it has `userSettings` at 9002 (a message), which this fork once used for the
 * list of extension repositories - a list of strings that cannot be read as that message.
 */
class BackupOfficialCompatTest {
    @Serializable
    private class OfficialUserSettings(
        @ProtoNumber(1) val someSetting: String? = null,
    )

    @Serializable
    private class OfficialBackup(
        @ProtoNumber(2) val backupCategories: List<BackupCategory> = emptyList(),
        @ProtoNumber(101) val backupSources: List<BackupSource> = emptyList(),
        @ProtoNumber(9000) val meta: Map<String, String> = emptyMap(),
        @ProtoNumber(9002) val userSettings: OfficialUserSettings? = null,
    )

    @Test
    fun `the official model reads a backup that has extension data and ignores it`() {
        val backup =
            Backup(
                backupCategories = listOf(BackupCategory(name = "Reading")),
                backupSources = listOf(BackupSource(name = "Source", sourceId = 1)),
                extensionStores = listOf("https://example.org/repo/index.min.json"),
                backupExtensions = listOf(BackupExtension("eu.kanade.tachiyomi.extension.en.one", "One", "https://example.org/repo/index.min.json")),
            )

        val decoded =
            ProtoBuf.decodeFromByteArray(OfficialBackup.serializer(), ProtoBuf.encodeToByteArray(Backup.serializer(), backup))

        assertEquals("Reading", decoded.backupCategories.single().name)
        assertEquals(1L, decoded.backupSources.single().sourceId)
        assertNull(decoded.userSettings)
    }
}
