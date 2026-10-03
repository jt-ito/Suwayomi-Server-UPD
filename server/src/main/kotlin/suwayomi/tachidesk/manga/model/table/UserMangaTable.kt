package suwayomi.tachidesk.manga.model.table

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.select
import suwayomi.tachidesk.server.user.model.UserTable

object UserMangaTable : IntIdTable("user_manga") {
    val user = reference("user_id", UserTable, ReferenceOption.CASCADE)
    val manga = reference("manga_id", MangaTable, ReferenceOption.CASCADE)
    val inLibrary = bool("in_library").default(false)
    val inLibraryAt = long("in_library_at").default(0)
    val viewer = integer("viewer").default(0)
    val viewerFlags = integer("viewer_flags").nullable()
    val chapterFlags = integer("chapter_flags").default(0)

    init {
        uniqueIndex("uq_user_manga", user, manga)
    }
}

data class UserMangaDataClass(
    val id: Int,
    val userId: Int,
    val mangaId: Int,
    val inLibrary: Boolean,
    val inLibraryAt: Long,
    val viewer: Int,
    val viewerFlags: Int?,
    val chapterFlags: Int,
)

/**
 * The manga in the library of one account. Every account has its own library, the `inLibrary` column of the manga
 * table only mirrors the first one.
 */
fun UserMangaTable.libraryMangaIdsOf(userId: Int) =
    UserMangaTable
        .select(UserMangaTable.manga)
        .where { (UserMangaTable.user eq userId) and (UserMangaTable.inLibrary eq true) }

/** The manga that are in the library of any account. */
fun UserMangaTable.allLibraryMangaIds() = UserMangaTable.select(UserMangaTable.manga).where { UserMangaTable.inLibrary eq true }

fun UserMangaTable.libraryOf(userId: Int): Op<Boolean> = MangaTable.id inSubQuery libraryMangaIdsOf(userId)

fun UserMangaTable.toDataClass(row: ResultRow) =
    UserMangaDataClass(
        id = row[UserMangaTable.id].value,
        userId = row[user].value,
        mangaId = row[manga].value,
        inLibrary = row[inLibrary],
        inLibraryAt = row[inLibraryAt],
        viewer = row[viewer],
        viewerFlags = row[viewerFlags],
        chapterFlags = row[chapterFlags],
    )
