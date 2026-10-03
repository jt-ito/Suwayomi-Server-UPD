package suwayomi.tachidesk.graphql.queries

import graphql.GraphQLContext
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.queries.filter.BooleanFilter
import suwayomi.tachidesk.graphql.queries.filter.LongFilter
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserChapterTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.UserManager
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.model.UserTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga

class AccountLibraryQueryTest : ApplicationTest() {
    private var other = 0

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
        other = UserManager.createUser("library-${System.nanoTime()}", "password-123").id
    }

    @AfterEach
    fun tearDown() {
        clearTables(UserChapterTable, UserMangaTable, ChapterTable, MangaTable)
        UserManager.deleteUser(other)
    }

    private fun environmentOf(userId: Int): DataFetchingEnvironment =
        DataFetchingEnvironmentImpl
            .newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.of(mapOf<Any, Any>(Attribute.TachideskUser to UserType.Member(userId))))
            .build()

    private fun updates(userId: Int) =
        ChapterQuery()
            .chapters(
                environmentOf(userId),
                filter = ChapterQuery.ChapterFilter(inLibrary = BooleanFilter(equalTo = true)),
            ).nodes
            .map { it.name }

    private fun history(userId: Int) =
        ChapterQuery()
            .chapters(
                environmentOf(userId),
                filter = ChapterQuery.ChapterFilter(lastReadAt = LongFilter(notEqualToAll = listOf(0L))),
                order = listOf(ChapterQuery.ChapterOrder(ChapterQuery.ChapterOrderBy.LAST_READ_AT, SortOrder.DESC)),
            ).nodes
            .map { it.name }

    @Test
    fun `updates and history only show the library and reading of the account`() {
        // the first account's library and reading, kept in the manga and chapter tables
        val mangaId = createLibraryManga("first account's")
        createChapters(mangaId, 2, read = false)
        transaction { ChapterTable.update({ ChapterTable.url eq "1" }) { it[lastReadAt] = 500 } }

        assertEquals(listOf("1", "2"), updates(1).sorted())
        assertEquals(listOf("1"), history(1))
        assertEquals(emptyList<String>(), updates(other))
        assertEquals(emptyList<String>(), history(other))

        // the other account adds the manga to its own library and reads a chapter
        val secondChapter = transaction { ChapterTable.selectAll().first { it[ChapterTable.url] == "2" } }
        val chapterId = secondChapter[ChapterTable.id].value
        transaction {
            UserMangaTable.insert {
                it[user] = EntityID(other, UserTable)
                it[manga] = EntityID(mangaId, MangaTable)
                it[inLibrary] = true
            }
            UserChapterTable.insert {
                it[user] = EntityID(other, UserTable)
                it[chapter] = EntityID(chapterId, ChapterTable)
                it[lastReadAt] = 900
            }
        }

        assertEquals(listOf("1", "2"), updates(other).sorted())
        assertEquals(listOf("2"), history(other))
        assertEquals(listOf("1"), history(1))
    }
}
