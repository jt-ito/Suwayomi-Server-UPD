package suwayomi.tachidesk.manga.impl.backup.proto.handlers

import kotlinx.coroutines.runBlocking
import okio.Sink
import okio.buffer
import okio.gzip
import okio.source
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupExport
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupImport
import suwayomi.tachidesk.manga.impl.backup.proto.ServerBackup
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserChapterTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.server.user.UserManager
import suwayomi.tachidesk.server.user.model.UserTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga

class BackupPerAccountTest : ApplicationTest() {
    private val flags = BackupFlags.DEFAULT.copy(includeServerSettings = false, includeExtensions = false)
    private val users = mutableListOf<Int>()

    // other test classes leave their own in-memory database as the default one
    @OptIn(ExperimentalKeywordApi::class)
    @BeforeEach
    fun useAppDatabase() {
        TransactionManager.defaultDatabase =
            Database.connect(
                "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;",
                "org.h2.Driver",
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        preserveKeywordCasing = false
                        defaultSchema = null
                    },
            )
    }

    @AfterEach
    fun tearDown() {
        clearTables(CategoryMangaTable, CategoryTable, UserChapterTable, UserMangaTable, ChapterTable, MangaTable)
        users.forEach { UserManager.deleteUser(it) }
        users.clear()
    }

    private fun newUser(): Int = UserManager.createUser("backup-${System.nanoTime()}", "password-123").id.also { users.add(it) }

    private fun category(
        name: String,
        owner: Int,
        position: Int = 1,
    ): Int =
        transaction {
            CategoryTable
                .insertAndGetId {
                    it[CategoryTable.name] = name
                    it[order] = position
                    it[user] = EntityID(owner, UserTable)
                }.value
        }

    private fun link(
        mangaId: Int,
        categoryId: Int,
    ) = transaction {
        CategoryMangaTable.insert {
            it[manga] = EntityID(mangaId, MangaTable)
            it[category] = EntityID(categoryId, CategoryTable)
        }
    }

    private fun backupOf(userId: Int): Backup {
        val bytes = ProtoBackupExport.createBackup(flags, userId, false).readAllBytes()
        val decoded =
            bytes
                .inputStream()
                .source()
                .gzip()
                .buffer()
                .readByteArray()
        return ProtoBackupImport.parser.decodeFromByteArray(Backup.serializer(), decoded)
    }

    private fun restore(
        userId: Int,
        backup: Backup,
    ) = runBlocking {
        val bytes = ProtoBackupImport.parser.encodeToByteArray(Backup.serializer(), backup)
        val gzipped = okio.Buffer()
        (gzipped as Sink).gzip().buffer().use { it.write(bytes) }
        ProtoBackupImport.restoreLegacy(gzipped.inputStream(), flags = flags, userId = userId, canManageServer = false)
    }

    @Test
    fun `a backup holds one account and a restore only writes into one account`() {
        val mangaId = createLibraryManga("shared")
        createChapters(mangaId, 2, read = false)
        transaction { ChapterTable.update({ ChapterTable.url eq "1" }) { it[isRead] = true } }
        val firstReading = category("Reading", 1)
        category("OnlyFirst", 1, position = 2)
        link(mangaId, firstReading)

        val other = newUser()
        val otherReading = category("Reading", other)

        // the first account's backup, restored into the other account
        val backupOfFirst = backupOf(1)
        assertEquals(setOf("Reading", "OnlyFirst"), backupOfFirst.backupCategories.map { it.name }.toSet() - "Default")
        restore(other, backupOfFirst)

        // the other account got the library entry, the read chapter and the category link ...
        val own = backupOf(other)
        assertEquals(listOf("shared"), own.backupManga.map { it.title })
        val ownManga = own.backupManga.single()
        assertEquals(listOf(true, false), ownManga.chapters.sortedBy { it.url }.map { it.read })
        assertEquals(1, ownManga.categories.size)
        // ... in its own category with the same name, and the first account's categories stay out
        assertEquals(setOf("Reading", "OnlyFirst"), own.backupCategories.map { it.name }.toSet() - "Default")
        val readingCategories = transaction { CategoryTable.selectAll().where { CategoryTable.name eq "Reading" }.count() }
        assertEquals(2L, readingCategories)
        assertEquals(
            setOf(otherReading),
            transaction {
                CategoryMangaTable
                    .selectAll()
                    .where { CategoryMangaTable.category eq otherReading }
                    .map { it[CategoryMangaTable.category].value }
                    .toSet()
            },
        )

        // the first account is exactly as it was
        val firstManga = transaction { MangaTable.selectAll().single() }
        assertTrue(firstManga[MangaTable.inLibrary])
        val readChapters = transaction { ChapterTable.selectAll().count { it[ChapterTable.isRead] } }
        assertEquals(1, readChapters)
        val firstBackup = backupOf(1).backupManga.single()
        assertEquals(1, firstBackup.categories.size)
        val firstLinks = transaction { CategoryMangaTable.selectAll().where { CategoryMangaTable.category eq firstReading }.count() }
        assertEquals(1L, firstLinks)
    }

    @Test
    fun `an account without a library gets an empty backup and does not see other libraries`() {
        createLibraryManga("only first")
        val fresh = newUser()

        val backup = backupOf(fresh)

        assertTrue(backup.backupManga.isEmpty())
        assertFalse(backup.backupCategories.any { it.name == "OnlyFirst" })
    }

    @Test
    fun `a backup of the whole server brings every account back, with its login`() {
        val mangaId = createLibraryManga("shared")
        createChapters(mangaId, 2, read = false)
        transaction { ChapterTable.update({ ChapterTable.url eq "1" }) { it[isRead] = true } }
        val other = newUser()
        restore(other, backupOf(1))
        val storedRow = transaction { UserTable.selectAll().where { UserTable.id eq other }.single() }
        val storedLogin = storedRow[UserTable.username] to storedRow[UserTable.passwordHash]

        val serverFlags = flags.copy(includeServerSettings = false, includeExtensions = false)
        val serverBackup = ProtoBackupExport.createServerBackup(serverFlags).readAllBytes()
        assertTrue(ServerBackup.isServerBackup(serverBackup.inputStream().buffered()))

        // the account is gone, as on a new server
        UserManager.deleteUser(other)
        users.clear()
        assertEquals(0L, transaction { UserTable.selectAll().where { UserTable.username eq storedLogin.first }.count() })

        val restoreId = ProtoBackupImport.restoreServer(serverBackup.inputStream(), serverFlags)
        val deadline = System.currentTimeMillis() + 30_000
        val success = ProtoBackupImport.BackupRestoreState.Success
        while (ProtoBackupImport.getRestoreState(restoreId) != success && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertEquals(ProtoBackupImport.BackupRestoreState.Success, ProtoBackupImport.getRestoreState(restoreId))

        val restored =
            transaction { UserTable.selectAll().where { UserTable.username eq storedLogin.first }.single() }
        users.add(restored[UserTable.id].value)
        assertEquals(storedLogin.second, restored[UserTable.passwordHash])

        val own = backupOf(restored[UserTable.id].value)
        assertEquals(listOf("shared"), own.backupManga.map { it.title })
        val restoredManga = own.backupManga.single()
        assertEquals(listOf(true, false), restoredManga.chapters.sortedBy { it.url }.map { it.read })
        // the first account was restored into itself and is unchanged
        assertEquals(1, backupOf(1).backupManga.size)
    }
}
