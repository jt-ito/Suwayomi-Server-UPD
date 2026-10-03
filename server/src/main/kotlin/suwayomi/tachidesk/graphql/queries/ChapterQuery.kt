/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.queries

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.queries.filter.BooleanFilter
import suwayomi.tachidesk.graphql.queries.filter.DoubleFilter
import suwayomi.tachidesk.graphql.queries.filter.Filter
import suwayomi.tachidesk.graphql.queries.filter.HasGetOp
import suwayomi.tachidesk.graphql.queries.filter.IntFilter
import suwayomi.tachidesk.graphql.queries.filter.LongFilter
import suwayomi.tachidesk.graphql.queries.filter.OpAnd
import suwayomi.tachidesk.graphql.queries.filter.StringFilter
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompare
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareEntity
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareString
import suwayomi.tachidesk.graphql.queries.filter.applyOps
import suwayomi.tachidesk.graphql.server.currentUserId
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Order
import suwayomi.tachidesk.graphql.server.primitives.OrderBy
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.graphql.server.primitives.QueryResults
import suwayomi.tachidesk.graphql.server.primitives.applyBeforeAfter
import suwayomi.tachidesk.graphql.server.primitives.applySortAndGetPaginationInfo
import suwayomi.tachidesk.graphql.server.primitives.greaterNotUnique
import suwayomi.tachidesk.graphql.server.primitives.lessNotUnique
import suwayomi.tachidesk.graphql.types.ChapterNodeList
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserChapterTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.manga.model.table.libraryMangaIdsOf
import java.util.concurrent.CompletableFuture

/**
 * TODO Queries
 * - Filter in library
 * - Get page list?
 */
class ChapterQuery {
    @RequireAuth
    fun chapter(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): CompletableFuture<ChapterType> = dataFetchingEnvironment.getValueFromDataLoader("ChapterDataLoader", id)

    enum class ChapterOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ChapterType> {
        ID(ChapterTable.id),
        SOURCE_ORDER(ChapterTable.sourceOrder),
        NAME(ChapterTable.name),
        UPLOAD_DATE(ChapterTable.date_upload),
        CHAPTER_NUMBER(ChapterTable.chapter_number),
        LAST_READ_AT(ChapterTable.lastReadAt),
        FETCHED_AT(ChapterTable.fetchedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> ChapterTable.id greater cursor.value.toInt()
                SOURCE_ORDER -> greaterNotUnique(ChapterTable.sourceOrder, ChapterTable.id, cursor, String::toInt)
                NAME -> greaterNotUnique(ChapterTable.name, ChapterTable.id, cursor, String::toString)
                UPLOAD_DATE -> greaterNotUnique(ChapterTable.date_upload, ChapterTable.id, cursor, String::toLong)
                CHAPTER_NUMBER -> greaterNotUnique(ChapterTable.chapter_number, ChapterTable.id, cursor, String::toFloat)
                LAST_READ_AT -> greaterNotUnique(ChapterTable.lastReadAt, ChapterTable.id, cursor, String::toLong)
                FETCHED_AT -> greaterNotUnique(ChapterTable.fetchedAt, ChapterTable.id, cursor, String::toLong)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> ChapterTable.id less cursor.value.toInt()
                SOURCE_ORDER -> lessNotUnique(ChapterTable.sourceOrder, ChapterTable.id, cursor, String::toInt)
                NAME -> lessNotUnique(ChapterTable.name, ChapterTable.id, cursor, String::toString)
                UPLOAD_DATE -> lessNotUnique(ChapterTable.date_upload, ChapterTable.id, cursor, String::toLong)
                CHAPTER_NUMBER -> lessNotUnique(ChapterTable.chapter_number, ChapterTable.id, cursor, String::toFloat)
                LAST_READ_AT -> lessNotUnique(ChapterTable.lastReadAt, ChapterTable.id, cursor, String::toLong)
                FETCHED_AT -> lessNotUnique(ChapterTable.fetchedAt, ChapterTable.id, cursor, String::toLong)
            }

        override fun asCursor(type: ChapterType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    SOURCE_ORDER -> type.id.toString() + "-" + type.sourceOrder
                    NAME -> type.id.toString() + "-" + type.name
                    UPLOAD_DATE -> type.id.toString() + "-" + type.uploadDate
                    CHAPTER_NUMBER -> type.id.toString() + "-" + type.chapterNumber
                    LAST_READ_AT -> type.id.toString() + "-" + type.lastReadAt
                    FETCHED_AT -> type.id.toString() + "-" + type.fetchedAt
                }
            return Cursor(value)
        }
    }

    data class ChapterOrder(
        override val by: ChapterOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ChapterOrderBy>

    /**
     * [ChapterOrderBy] for one account: the read state of the accounts after the first is kept in user_chapter, so
     * ordering by when something was read has to use that column.
     */
    private class AccountChapterOrderBy(
        private val base: ChapterOrderBy,
        private val ownReadState: Boolean,
    ) : OrderBy<ChapterType> {
        private val useOwn = ownReadState && base == ChapterOrderBy.LAST_READ_AT

        override val column: Column<*> = if (useOwn) UserChapterTable.lastReadAt else base.column

        override fun asCursor(type: ChapterType) = base.asCursor(type)

        override fun greater(cursor: Cursor) =
            if (useOwn) greaterNotUnique(UserChapterTable.lastReadAt, ChapterTable.id, cursor, String::toLong) else base.greater(cursor)

        override fun less(cursor: Cursor) =
            if (useOwn) lessNotUnique(UserChapterTable.lastReadAt, ChapterTable.id, cursor, String::toLong) else base.less(cursor)
    }

    private data class AccountChapterOrder(
        override val by: AccountChapterOrderBy,
        override val byType: SortOrder?,
    ) : Order<AccountChapterOrderBy>

    data class ChapterCondition(
        val id: Int? = null,
        val url: String? = null,
        val name: String? = null,
        val uploadDate: Long? = null,
        val chapterNumber: Float? = null,
        val scanlator: String? = null,
        val mangaId: Int? = null,
        val isRead: Boolean? = null,
        val isBookmarked: Boolean? = null,
        val lastPageRead: Int? = null,
        val lastReadAt: Long? = null,
        val sourceOrder: Int? = null,
        val realUrl: String? = null,
        val fetchedAt: Long? = null,
        val isDownloaded: Boolean? = null,
        val pageCount: Int? = null,
    ) : HasGetOp {
        override fun getOp(): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(id, ChapterTable.id)
            opAnd.eq(url, ChapterTable.url)
            opAnd.eq(name, ChapterTable.name)
            opAnd.eq(uploadDate, ChapterTable.date_upload)
            opAnd.eq(chapterNumber, ChapterTable.chapter_number)
            opAnd.eq(scanlator, ChapterTable.scanlator)
            opAnd.eq(mangaId, ChapterTable.manga)
            opAnd.eq(isRead, ChapterTable.isRead)
            opAnd.eq(isBookmarked, ChapterTable.isBookmarked)
            opAnd.eq(lastPageRead, ChapterTable.lastPageRead)
            opAnd.eq(lastReadAt, ChapterTable.lastReadAt)
            opAnd.eq(sourceOrder, ChapterTable.sourceOrder)
            opAnd.eq(realUrl, ChapterTable.realUrl)
            opAnd.eq(fetchedAt, ChapterTable.fetchedAt)
            opAnd.eq(isDownloaded, ChapterTable.isDownloaded)
            opAnd.eq(pageCount, ChapterTable.pageCount)

            return opAnd.op
        }

        fun withoutReadState() = copy(isRead = null, isBookmarked = null, lastPageRead = null, lastReadAt = null)

        /** The conditions on the read state, for an account that keeps it in user_chapter (no row means not read). */
        fun readStateOps(): List<Op<Boolean>> =
            listOfNotNull(
                isRead?.let { if (it) UserChapterTable.isRead eq true else (UserChapterTable.isRead eq false) or UserChapterTable.isRead.isNull() },
                isBookmarked?.let {
                    if (it) UserChapterTable.isBookmarked eq true else (UserChapterTable.isBookmarked eq false) or UserChapterTable.isBookmarked.isNull()
                },
                lastPageRead?.let { UserChapterTable.lastPageRead eq it },
                lastReadAt?.let { UserChapterTable.lastReadAt eq it },
            )
    }

    data class ChapterFilter(
        val id: IntFilter? = null,
        val url: StringFilter? = null,
        val name: StringFilter? = null,
        val uploadDate: LongFilter? = null,
        val chapterNumber: DoubleFilter? = null,
        val scanlator: StringFilter? = null,
        val mangaId: IntFilter? = null,
        val isRead: BooleanFilter? = null,
        val isBookmarked: BooleanFilter? = null,
        val lastPageRead: IntFilter? = null,
        val lastReadAt: LongFilter? = null,
        val sourceOrder: IntFilter? = null,
        val realUrl: StringFilter? = null,
        val fetchedAt: LongFilter? = null,
        val isDownloaded: BooleanFilter? = null,
        val pageCount: IntFilter? = null,
        val inLibrary: BooleanFilter? = null,
        override val and: List<ChapterFilter>? = null,
        override val or: List<ChapterFilter>? = null,
        override val not: ChapterFilter? = null,
    ) : Filter<ChapterFilter> {
        override fun getOpList(): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareEntity(ChapterTable.id, id),
                andFilterWithCompareString(ChapterTable.url, url),
                andFilterWithCompareString(ChapterTable.name, name),
                andFilterWithCompare(ChapterTable.date_upload, uploadDate),
                andFilterWithCompare(ChapterTable.chapter_number, chapterNumber?.toFloatFilter()),
                andFilterWithCompareString(ChapterTable.scanlator, scanlator),
                andFilterWithCompareEntity(ChapterTable.manga, mangaId),
                andFilterWithCompare(ChapterTable.isRead, isRead),
                andFilterWithCompare(ChapterTable.isBookmarked, isBookmarked),
                andFilterWithCompare(ChapterTable.lastPageRead, lastPageRead),
                andFilterWithCompare(ChapterTable.lastReadAt, lastReadAt),
                andFilterWithCompare(ChapterTable.sourceOrder, sourceOrder),
                andFilterWithCompareString(ChapterTable.realUrl, realUrl),
                andFilterWithCompare(ChapterTable.fetchedAt, fetchedAt),
                andFilterWithCompare(ChapterTable.isDownloaded, isDownloaded),
                andFilterWithCompare(ChapterTable.pageCount, pageCount),
            )

        fun getLibraryOp() = andFilterWithCompare(MangaTable.inLibrary, inLibrary)

        fun withoutLibraryAndReadState() =
            copy(inLibrary = null, isRead = null, isBookmarked = null, lastPageRead = null, lastReadAt = null)

        /** The filters on the read state, for an account that keeps it in user_chapter. */
        fun readStateOps(): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompare(UserChapterTable.isRead, isRead),
                andFilterWithCompare(UserChapterTable.isBookmarked, isBookmarked),
                andFilterWithCompare(UserChapterTable.lastPageRead, lastPageRead),
                andFilterWithCompare(UserChapterTable.lastReadAt, lastReadAt),
            )
    }

    @RequireAuth
    fun chapters(
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: ChapterCondition? = null,
        filter: ChapterFilter? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderBy: ChapterOrderBy? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderByType: SortOrder? = null,
        order: List<ChapterOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterNodeList {
        val userId = dataFetchingEnvironment.currentUserId()
        // the library and the read state of the first account are mirrored into the manga and chapter tables, the
        // other accounts have their own
        val hasOwnState = userId != 1

        val (queryResults, resultsAsType) =
            transaction {
                val res = ChapterTable.selectAll()

                if (hasOwnState) {
                    res.adjustColumnSet {
                        join(
                            UserChapterTable,
                            JoinType.LEFT,
                            onColumn = ChapterTable.id,
                            otherColumn = UserChapterTable.chapter,
                            additionalConstraint = { UserChapterTable.user eq userId },
                        )
                    }
                    filter?.inLibrary?.equalTo?.let { wanted ->
                        res.andWhere {
                            val library = ChapterTable.manga inSubQuery UserMangaTable.libraryMangaIdsOf(userId)
                            if (wanted) library else not(library)
                        }
                    }
                    res.applyOps(condition?.withoutReadState(), filter?.withoutLibraryAndReadState())
                    filter?.readStateOps()?.forEach { op -> res.andWhere { op } }
                    condition?.readStateOps()?.forEach { op -> res.andWhere { op } }
                } else {
                    val libraryOp = filter?.getLibraryOp()
                    if (libraryOp != null) {
                        res.adjustColumnSet {
                            innerJoin(MangaTable)
                        }
                        res.andWhere { libraryOp }
                    }

                    res.applyOps(condition, filter)
                }

                val baseSort = listOf(ChapterOrder(ChapterOrderBy.ID, SortOrder.ASC))
                val deprecatedSort = listOfNotNull(orderBy?.let { ChapterOrder(orderBy, orderByType) })
                val actualSort =
                    (order.orEmpty() + deprecatedSort + baseSort).map {
                        AccountChapterOrder(AccountChapterOrderBy(it.by, hasOwnState), it.byType)
                    }

                val (total, firstResult, lastResult) = res.applySortAndGetPaginationInfo(actualSort, before, last, ChapterTable.id)

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = AccountChapterOrderBy(order?.firstOrNull()?.by ?: ChapterOrderBy.ID, hasOwnState),
                    orderByType = order?.firstOrNull()?.byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                val rows = res.toList()
                // the read state of an account is looked up per chapter, which needs the transaction
                QueryResults(total, firstResult, lastResult, rows) to rows.map { ChapterType(it, userId) }
            }

        val getAsCursor: (ChapterType) -> Cursor = (order?.firstOrNull()?.by ?: ChapterOrderBy.ID)::asCursor


        return ChapterNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        ChapterNodeList.ChapterEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                    resultsAsType.lastOrNull()?.let {
                        ChapterNodeList.ChapterEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                )
            },
            pageInfo =
                PageInfo(
                    hasNextPage = queryResults.lastKey != resultsAsType.lastOrNull()?.id,
                    hasPreviousPage = queryResults.firstKey != resultsAsType.firstOrNull()?.id,
                    startCursor = resultsAsType.firstOrNull()?.let { getAsCursor(it) },
                    endCursor = resultsAsType.lastOrNull()?.let { getAsCursor(it) },
                ),
            totalCount = queryResults.total.toInt(),
        )
    }
}
