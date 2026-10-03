package suwayomi.tachidesk.server.user

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.global.impl.GlobalMeta
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.Source
import suwayomi.tachidesk.manga.impl.track.Track
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.SourceMetaTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track as TrackModel

class PerUserDataTest : ApplicationTest() {
    private var secondUser = 0

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

    private fun newUser(): Int {
        secondUser = UserManager.createUser("second-${System.nanoTime()}", "password-123").id
        return secondUser
    }

    @AfterEach
    fun tearDown() {
        if (secondUser != 0) {
            UserManager.deleteUser(secondUser)
            secondUser = 0
        }
        clearTables(
            TrackRecordTable,
            ChapterMetaTable,
            MangaMetaTable,
            SourceMetaTable,
            GlobalMetaTable,
            ChapterTable,
            MangaTable,
        )
    }

    @Test
    fun `a new account starts without the settings of other accounts`() {
        GlobalMeta.modifyMeta("theme", "dark", 1)
        val user = newUser()

        assertEquals(emptyMap<String, String>(), GlobalMeta.getMetaMap(user))

        GlobalMeta.modifyMeta("theme", "light", user)
        assertEquals(mapOf("theme" to "dark"), GlobalMeta.getMetaMap(1))
        assertEquals(mapOf("theme" to "light"), GlobalMeta.getMetaMap(user))
    }

    @Test
    fun `manga, chapter and source meta are kept apart per account`() {
        val mangaId = createLibraryManga("m")
        createChapters(mangaId, 1, read = false)
        val chapterId = transaction { ChapterTable.selectAll().first()[ChapterTable.id].value }
        val user = newUser()

        Manga.modifyMangaMeta(mangaId, "k", "first", 1)
        Chapter.modifyChapterMeta(chapterId, "k", "first", 1)
        Source.modifyMeta(1L, "k", "first", 1)

        assertEquals(emptyMap<String, String>(), Manga.getMangaMetaMap(mangaId, user))
        assertEquals(emptyMap<String, String>(), Chapter.getChapterMetaMap(chapterId, user))
        assertEquals(emptyMap<String, String>(), Source.getSourcesMetaMaps(listOf(1L), user)[1L].orEmpty())

        Manga.modifyMangaMeta(mangaId, "k", "second", user)
        assertEquals(mapOf("k" to "first"), Manga.getMangaMetaMap(mangaId, 1))
        assertEquals(mapOf("k" to "second"), Manga.getMangaMetaMap(mangaId, user))
    }

    @Test
    fun `tracker logins are kept apart per account`() {
        val user = newUser()
        val first = TrackerManager.getTracker(TrackerManager.ANILIST, 1)!!
        val second = TrackerManager.getTracker(TrackerManager.ANILIST, user)!!
        val firstWasLoggedIn = first.isLoggedIn

        second.saveCredentials("name", "token")

        assertTrue(second.isLoggedIn)
        assertEquals(firstWasLoggedIn, first.isLoggedIn)

        TrackerManager.forgetUser(user)
        assertFalse(TrackerManager.getTracker(TrackerManager.ANILIST, user)!!.isLoggedIn)
    }

    @Test
    fun `track records are kept apart per account`() {
        val mangaId = createLibraryManga("tracked")
        val user = newUser()

        fun record(title: String) =
            TrackModel.create(TrackerManager.ANILIST).also {
                it.manga_id = mangaId
                it.title = title
                it.tracking_url = "url"
            }

        Track.upsertTrackRecord(record("first"), 1)
        Track.upsertTrackRecord(record("second"), user)

        val rows = transaction { TrackRecordTable.selectAll().count() }
        assertEquals(2L, rows)
        fun titleOf(userId: Int) =
            Track
                .getTrackRecordsByMangaId(mangaId, userId)
                .first { it.record != null }
                .record!!
                .title

        assertEquals("first", titleOf(1))
        assertEquals("second", titleOf(user))
    }

    @Test
    fun `deleting an account removes its meta`() {
        val user = newUser()
        GlobalMeta.modifyMeta("k", "v", user)
        UserManager.deleteUser(user)
        secondUser = 0

        assertEquals(0L, transaction { GlobalMetaTable.selectAll().where { GlobalMetaTable.user eq user }.count() })
    }
}
