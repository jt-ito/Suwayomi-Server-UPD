package suwayomi.tachidesk.manga.impl.backup.proto.handlers

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.BatchUpdateStatement
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.Chapter.modifyChaptersMetas
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.Manga.clearThumbnail
import suwayomi.tachidesk.manga.impl.Manga.modifyMangasMetas
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.SyncRestoreMode
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupChapter
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupHistory
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupTracking
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.impl.track.tracker.model.toTrack
import suwayomi.tachidesk.manga.impl.track.tracker.model.toTrackRecordDataClass
import suwayomi.tachidesk.manga.model.dataclass.TrackRecordDataClass
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserChapterTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.manga.model.table.ownedBy
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.user.model.UserTable
import java.util.Date
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import suwayomi.tachidesk.manga.impl.track.Track as Tracker

object BackupMangaHandler {
    private enum class RestoreMode {
        NEW,
        EXISTING,
    }

    fun backup(
        flags: BackupFlags,
        since: Long? = null,
        userId: Int = 1,
    ): List<BackupManga> =
        dbTransaction {
            if (!flags.includeManga) {
                return@dbTransaction emptyList()
            }

            val changed =
                since?.let {
                    val chapterChanged =
                        ChapterTable
                            .select(ChapterTable.manga)
                            .where { ChapterTable.lastModifiedAt greaterEq it }
                    (MangaTable.lastModifiedAt greaterEq it) or (MangaTable.id inSubQuery chapterChanged)
                } ?: Op.TRUE
            // a library is kept per account; the first account's is also mirrored into the manga table itself
            val ownLibrary =
                MangaTable
                    .innerJoin(UserMangaTable)
                    .selectAll()
                    .where { (UserMangaTable.user eq userId) and (UserMangaTable.inLibrary eq true) and changed }
                    .toList()
            val mirroredLibrary =
                if (userId == 1) {
                    val hasOwnState = UserMangaTable.select(UserMangaTable.manga).where { UserMangaTable.user eq userId }
                    MangaTable
                        .selectAll()
                        .where { (MangaTable.inLibrary eq true) and changed and (MangaTable.id notInSubQuery hasOwnState) }
                        .toList()
                } else {
                    emptyList()
                }
            val manga = ownLibrary + mirroredLibrary
            val ownedCategoryIds =
                CategoryTable
                    .select(CategoryTable.id)
                    .where { CategoryTable.ownedBy(userId) }
                    .map { it[CategoryTable.id].value }
                    .toSet()

            manga.map { mangaRow ->
                val backupManga =
                    BackupManga(
                        source = mangaRow[MangaTable.sourceReference],
                        url = mangaRow[MangaTable.url],
                        title = mangaRow[MangaTable.title],
                        artist = mangaRow[MangaTable.artist],
                        author = mangaRow[MangaTable.author],
                        description = mangaRow[MangaTable.description],
                        genre = mangaRow[MangaTable.genre]?.split(", ") ?: emptyList(),
                        status = MangaStatus.valueOf(mangaRow[MangaTable.status]).value,
                        thumbnailUrl = mangaRow[MangaTable.thumbnail_url],
                        dateAdded = (if (userId == 1) mangaRow[MangaTable.inLibraryAt] else mangaRow[UserMangaTable.inLibraryAt]).seconds.inWholeMilliseconds,
                        viewer = if (userId == 1) mangaRow[MangaTable.viewer] else mangaRow[UserMangaTable.viewer],
                        viewer_flags = if (userId == 1) mangaRow[MangaTable.viewerFlags] else mangaRow[UserMangaTable.viewerFlags],
                        chapterFlags = if (userId == 1) mangaRow[MangaTable.chapterFlags] else mangaRow[UserMangaTable.chapterFlags],
                        updateStrategy = UpdateStrategy.valueOf(mangaRow[MangaTable.updateStrategy]),
                        lastModifiedAt = mangaRow[MangaTable.lastModifiedAt],
                        version = mangaRow[MangaTable.version],
                        initialized = mangaRow[MangaTable.initialized],
                        memo = Json.encodeToString(mangaRow[MangaTable.memo]).encodeToByteArray(),
                    )

                val mangaId = mangaRow[MangaTable.id].value

                if (flags.includeClientData) {
                    backupManga.meta = Manga.getMangaMetaMap(mangaId, userId)
                }

                if (flags.includeChapters || flags.includeHistory) {
                    val chapters =
                        transaction {
                            ChapterTable
                                .selectAll()
                                .where { ChapterTable.manga eq mangaId }
                                .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                                .toList()
                        }

                    // read state is kept per account; the first account's is also mirrored into the chapter itself
                    val ownState =
                        UserChapterTable
                            .selectAll()
                            .where {
                                (UserChapterTable.user eq userId) and
                                    (UserChapterTable.chapter inList chapters.map { it[ChapterTable.id].value })
                            }.associateBy { it[UserChapterTable.chapter].value }

                    fun isRead(row: ResultRow) = ownState[row[ChapterTable.id].value]?.get(UserChapterTable.isRead) ?: (userId == 1 && row[ChapterTable.isRead])

                    fun isBookmarked(row: ResultRow) =
                        ownState[row[ChapterTable.id].value]?.get(UserChapterTable.isBookmarked) ?: (userId == 1 && row[ChapterTable.isBookmarked])

                    fun lastPageRead(row: ResultRow) =
                        ownState[row[ChapterTable.id].value]?.get(UserChapterTable.lastPageRead)
                            ?: if (userId == 1) row[ChapterTable.lastPageRead] else 0

                    fun lastReadAt(row: ResultRow) =
                        ownState[row[ChapterTable.id].value]?.get(UserChapterTable.lastReadAt)
                            ?: if (userId == 1) row[ChapterTable.lastReadAt] else 0L

                    if (flags.includeChapters) {
                        val chapterToMeta =
                            Chapter.getChaptersMetaMaps(chapters.map { it[ChapterTable.id].value }, userId)

                        backupManga.chapters =
                            chapters.map {
                                BackupChapter(
                                    url = it[ChapterTable.url],
                                    name = it[ChapterTable.name],
                                    scanlator = it[ChapterTable.scanlator],
                                    read = isRead(it),
                                    bookmark = isBookmarked(it),
                                    lastPageRead = lastPageRead(it),
                                    dateFetch = it[ChapterTable.fetchedAt].seconds.inWholeMilliseconds,
                                    dateUpload = it[ChapterTable.date_upload],
                                    chapterNumber = it[ChapterTable.chapter_number],
                                    sourceOrder = chapters.size - it[ChapterTable.sourceOrder],
                                    lastModifiedAt = it[ChapterTable.lastModifiedAt],
                                    version = it[ChapterTable.version],
                                    memo = Json.encodeToString(it[ChapterTable.memo]).encodeToByteArray(),
                                ).apply {
                                    if (flags.includeClientData) {
                                        this.meta = chapterToMeta[it[ChapterTable.id].value] ?: emptyMap()
                                    }
                                }
                            }
                    }
                    if (flags.includeHistory) {
                        backupManga.history =
                            chapters.mapNotNull {
                                if (lastReadAt(it) > 0) {
                                    BackupHistory(
                                        url = it[ChapterTable.url],
                                        lastRead = lastReadAt(it).seconds.inWholeMilliseconds,
                                    )
                                } else {
                                    null
                                }
                            }
                    }
                }

                if (flags.includeCategories) {
                    backupManga.categories =
                        CategoryManga
                            .getMangaCategories(mangaId)
                            .filter { it.id in ownedCategoryIds }
                            .map { it.order }
                }

                if (flags.includeTracking) {
                    val tracks =
                        Tracker.getTrackRecordsByMangaId(mangaRow[MangaTable.id].value, userId).mapNotNull {
                            if (it.record == null) {
                                null
                            } else {
                                BackupTracking(
                                    syncId = it.record.trackerId,
                                    // forced not null so its compatible with 1.x backup system
                                    libraryId = it.record.libraryId ?: 0,
                                    mediaId = it.record.remoteId,
                                    title = it.record.title,
                                    lastChapterRead = it.record.lastChapterRead.toFloat(),
                                    totalChapters = it.record.totalChapters,
                                    score = it.record.score.toFloat(),
                                    status = it.record.status,
                                    startedReadingDate = it.record.startDate,
                                    finishedReadingDate = it.record.finishDate,
                                    trackingUrl = it.record.remoteUrl,
                                    private = it.record.private,
                                )
                            }
                        }
                    if (tracks.isNotEmpty()) {
                        backupManga.tracking = tracks
                    }
                }

                backupManga
            }
        }

    fun restore(
        backupManga: BackupManga,
        categoryMapping: Map<Int, Int>,
        sourceMapping: Map<Long, String>,
        errors: MutableList<Pair<Date, String>>,
        flags: BackupFlags,
        syncMode: SyncRestoreMode = SyncRestoreMode.NONE,
        userId: Int = 1,
    ) {
        val chapters = backupManga.chapters
        val categories = backupManga.categories
        val history = backupManga.history
        val tracking = backupManga.tracking

        val dbCategoryIds = categories.mapNotNull { categoryMapping[it] }

        try {
            restoreMangaData(backupManga, chapters, dbCategoryIds, history, tracking, flags, syncMode, userId)
        } catch (e: Exception) {
            val sourceName = sourceMapping[backupManga.source] ?: backupManga.source.toString()
            errors.add(Date() to "${backupManga.title} [$sourceName]: ${e.message}")
        }
    }

    private fun restoreMangaData(
        manga: BackupManga,
        chapters: List<BackupChapter>,
        categoryIds: List<Int>,
        history: List<BackupHistory>,
        tracks: List<BackupTracking>,
        flags: BackupFlags,
        syncMode: SyncRestoreMode,
        userId: Int,
    ) {
        val dbManga =
            transaction {
                MangaTable
                    .selectAll()
                    .where { (MangaTable.url eq manga.url) and (MangaTable.sourceReference eq manga.source) }
                    .firstOrNull()
            }
        val restoreMode = if (dbManga != null) RestoreMode.EXISTING else RestoreMode.NEW
        // a newer local copy wins the next upload; categories and tracking are part of the manga's version
        val keepLocalManga =
            syncMode == SyncRestoreMode.ADOPT && dbManga != null && manga.version < dbManga[MangaTable.version]

        val mangaId =
            transaction {
                val mangaId =
                    if (dbManga == null) {
                        // insert manga to database
                        MangaTable
                            .insertAndGetId {
                                it[url] = manga.url
                                it[title] = manga.title

                                it[artist] = manga.artist
                                it[author] = manga.author
                                it[description] = manga.description
                                it[genre] = manga.genre.joinToString()
                                it[status] = manga.status
                                it[thumbnail_url] = manga.thumbnailUrl
                                it[updateStrategy] = manga.updateStrategy.name

                                it[sourceReference] = manga.source

                                it[initialized] = manga.description != null

                                // the manga table itself only mirrors the first account, the others are kept in user_manga
                                it[inLibrary] = manga.favorite && userId == 1

                                if (userId == 1) {
                                    it[inLibraryAt] = manga.dateAdded.milliseconds.inWholeSeconds

                                    it[viewer] = manga.viewer
                                    it[viewerFlags] = manga.viewer_flags
                                    it[chapterFlags] = manga.chapterFlags
                                }

                                it[lastModifiedAt] = manga.lastModifiedAt
                                it[version] = manga.version
                                it[isSyncing] = syncMode.isSync
                                it[memo] = Json.decodeFromString<JsonObject>(manga.memo.decodeToString())
                            }.value
                    } else if (keepLocalManga) {
                        dbManga[MangaTable.id].value
                    } else {
                        val dbMangaId = dbManga[MangaTable.id].value

                        // Merge manga data
                        MangaTable.update({ MangaTable.id eq dbMangaId }) {
                            it[artist] = manga.artist ?: dbManga[artist]
                            it[author] = manga.author ?: dbManga[author]
                            it[description] = manga.description ?: dbManga[description]
                            it[genre] = manga.genre.ifEmpty { null }?.joinToString() ?: dbManga[genre]
                            it[status] = manga.status
                            it[thumbnail_url] = manga.thumbnailUrl ?: dbManga[thumbnail_url]
                            it[updateStrategy] = manga.updateStrategy.name

                            it[initialized] = dbManga[initialized] || manga.description != null

                            // the library, viewer settings and sync state of the manga table belong to the first account
                            if (userId == 1) {
                                it[inLibrary] =
                                    if (syncMode == SyncRestoreMode.ADOPT) manga.favorite else manga.favorite || dbManga[inLibrary]

                                it[inLibraryAt] = manga.dateAdded.milliseconds.inWholeSeconds

                                // outside ADOPT a zeroed backup must not wipe stored flags
                                if (syncMode == SyncRestoreMode.ADOPT || manga.viewer != 0) it[viewer] = manga.viewer
                                if (syncMode == SyncRestoreMode.ADOPT || manga.viewer_flags != null) it[viewerFlags] = manga.viewer_flags
                                if (syncMode == SyncRestoreMode.ADOPT || manga.chapterFlags != 0) it[chapterFlags] = manga.chapterFlags

                                if (syncMode == SyncRestoreMode.CONVERGE) {
                                    it[lastModifiedAt] = Clock.System.now().epochSeconds
                                    it[version] = max(manga.version, dbManga[version]) + 1
                                } else {
                                    it[lastModifiedAt] = manga.lastModifiedAt
                                    it[version] = manga.version
                                }
                                it[isSyncing] = syncMode.isSync
                                it[memo] = Json.decodeFromString<JsonObject>(manga.memo.decodeToString())
                            }
                        }

                        dbMangaId
                    }

                // delete thumbnail in case cached data still exists (a new manga has a new id, so it has none, and
                // the lookup lists the whole cache directories, which makes a big restore quadratic)
                if (restoreMode == RestoreMode.EXISTING) {
                    clearThumbnail(mangaId)
                }

                if (flags.includeClientData && manga.meta.isNotEmpty()) {
                    modifyMangasMetas(mapOf(mangaId to manga.meta), userId)
                }

                // the library entry and viewer settings of this account
                if (!keepLocalManga) {
                    restoreUserMangaState(mangaId, manga, syncMode, userId)
                }

                // merge chapter data
                if (flags.includeChapters || flags.includeHistory) {
                    restoreMangaChapterData(mangaId, restoreMode, chapters, history, flags, syncMode, userId)
                }

                // update categories
                if (flags.includeCategories && !keepLocalManga) {
                    restoreMangaCategoryData(mangaId, categoryIds, syncMode, userId)
                }

                mangaId
            }

        if (flags.includeTracking && !keepLocalManga) {
            restoreMangaTrackerData(mangaId, tracks, userId)
        }

        // TODO: insert/merge history
    }

    private fun restoreUserMangaState(
        mangaId: Int,
        manga: BackupManga,
        syncMode: SyncRestoreMode,
        userId: Int,
    ) {
        val own =
            UserMangaTable
                .selectAll()
                .where { (UserMangaTable.user eq userId) and (UserMangaTable.manga eq mangaId) }
                .firstOrNull()
        val dateAdded = manga.dateAdded.milliseconds.inWholeSeconds

        if (own == null) {
            UserMangaTable.insert {
                it[user] = EntityID(userId, UserTable)
                it[UserMangaTable.manga] = EntityID(mangaId, MangaTable)
                it[inLibrary] = manga.favorite
                it[inLibraryAt] = dateAdded
                it[viewer] = manga.viewer
                it[viewerFlags] = manga.viewer_flags
                it[chapterFlags] = manga.chapterFlags
            }
            return
        }

        UserMangaTable.update({ (UserMangaTable.user eq userId) and (UserMangaTable.manga eq mangaId) }) {
            it[inLibrary] = if (syncMode == SyncRestoreMode.ADOPT) manga.favorite else manga.favorite || own[inLibrary]
            it[inLibraryAt] = dateAdded
            // outside ADOPT a zeroed backup must not wipe stored flags
            if (syncMode == SyncRestoreMode.ADOPT || manga.viewer != 0) it[viewer] = manga.viewer
            if (syncMode == SyncRestoreMode.ADOPT || manga.viewer_flags != null) it[viewerFlags] = manga.viewer_flags
            if (syncMode == SyncRestoreMode.ADOPT || manga.chapterFlags != 0) it[chapterFlags] = manga.chapterFlags
        }
    }

    private fun getMangaChapterToRestoreInfo(
        mangaId: Int,
        restoreMode: RestoreMode,
        chapters: List<BackupChapter>,
    ): Pair<List<BackupChapter>, List<Pair<BackupChapter, ResultRow>>> {
        val uniqueChapters = chapters.distinctBy { it.url }

        if (restoreMode == RestoreMode.NEW) {
            return Pair(uniqueChapters, emptyList())
        }

        val dbChaptersByUrl = ChapterTable.selectAll().where { ChapterTable.manga eq mangaId }.associateBy { it[ChapterTable.url] }

        val (chaptersToUpdate, chaptersToInsert) = uniqueChapters.partition { dbChaptersByUrl.contains(it.url) }
        val chaptersToUpdateToDbChapter = chaptersToUpdate.map { it to dbChaptersByUrl[it.url]!! }

        return chaptersToInsert to chaptersToUpdateToDbChapter
    }

    private fun restoreMangaChapterData(
        mangaId: Int,
        restoreMode: RestoreMode,
        chapters: List<BackupChapter>,
        history: List<BackupHistory>,
        flags: BackupFlags,
        syncMode: SyncRestoreMode,
        userId: Int,
    ) = dbTransaction {
        val (chaptersToInsert, allChaptersToUpdate) = getMangaChapterToRestoreInfo(mangaId, restoreMode, chapters)
        val historyByChapter = history.groupBy({ it.url }, { it.lastRead })
        val chaptersToUpdateToDbChapter =
            if (syncMode == SyncRestoreMode.ADOPT) {
                allChaptersToUpdate.filter { (backupChapter, dbChapter) -> backupChapter.version >= dbChapter[ChapterTable.version] }
            } else {
                allChaptersToUpdate
            }

        val insertedChapterIds =
            if (flags.includeChapters) {
                ChapterTable
                    .batchInsert(chaptersToInsert) { chapter ->
                        this[ChapterTable.url] = chapter.url
                        this[ChapterTable.name] = chapter.name
                        if (chapter.dateUpload == 0L) {
                            this[ChapterTable.date_upload] = chapter.dateFetch
                        } else {
                            this[ChapterTable.date_upload] = chapter.dateUpload
                        }
                        this[ChapterTable.chapter_number] = chapter.chapterNumber
                        this[ChapterTable.scanlator] = chapter.scanlator

                        this[ChapterTable.sourceOrder] = chaptersToInsert.size - chapter.sourceOrder
                        this[ChapterTable.manga] = mangaId

                        // the progress of the chapter table itself belongs to the first account, the others are kept in user_chapter
                        if (userId == 1) {
                            this[ChapterTable.isRead] = chapter.read
                            this[ChapterTable.lastPageRead] = chapter.lastPageRead.coerceAtLeast(0)
                            this[ChapterTable.isBookmarked] = chapter.bookmark
                        }

                        this[ChapterTable.fetchedAt] = chapter.dateFetch.milliseconds.inWholeSeconds

                        if (flags.includeHistory && userId == 1) {
                            this[ChapterTable.lastReadAt] =
                                historyByChapter[chapter.url]?.maxOrNull()?.milliseconds?.inWholeSeconds ?: 0
                        }

                        this[ChapterTable.lastModifiedAt] = chapter.lastModifiedAt
                        this[ChapterTable.version] = chapter.version
                        this[ChapterTable.isSyncing] = syncMode.isSync
                        this[ChapterTable.memo] = Json.decodeFromString<JsonObject>(chapter.memo.decodeToString())
                    }.map { it[ChapterTable.id].value }
            } else {
                emptyList()
            }

        if (userId == 1 && chaptersToUpdateToDbChapter.isNotEmpty()) {
            BatchUpdateStatement(ChapterTable)
                .apply {
                    chaptersToUpdateToDbChapter.forEach { (backupChapter, dbChapter) ->
                        addBatch(EntityID(dbChapter[ChapterTable.id].value, ChapterTable))
                        if (flags.includeChapters) {
                            if (syncMode == SyncRestoreMode.ADOPT) {
                                this[ChapterTable.isRead] = backupChapter.read
                                this[ChapterTable.lastPageRead] = backupChapter.lastPageRead.coerceAtLeast(0)
                                this[ChapterTable.isBookmarked] = backupChapter.bookmark
                            } else {
                                this[ChapterTable.isRead] = backupChapter.read || dbChapter[ChapterTable.isRead]
                                this[ChapterTable.lastPageRead] =
                                    max(backupChapter.lastPageRead, dbChapter[ChapterTable.lastPageRead]).coerceAtLeast(0)
                                this[ChapterTable.isBookmarked] = backupChapter.bookmark || dbChapter[ChapterTable.isBookmarked]
                            }
                        }

                        if (flags.includeHistory) {
                            this[ChapterTable.lastReadAt] =
                                (historyByChapter[backupChapter.url]?.maxOrNull()?.milliseconds?.inWholeSeconds ?: 0)
                                    .coerceAtLeast(dbChapter[ChapterTable.lastReadAt])
                        }

                        when (syncMode) {
                            SyncRestoreMode.ADOPT -> {
                                this[ChapterTable.lastModifiedAt] = backupChapter.lastModifiedAt
                                this[ChapterTable.version] = backupChapter.version
                                this[ChapterTable.isSyncing] = true
                            }

                            SyncRestoreMode.CONVERGE -> {
                                this[ChapterTable.lastModifiedAt] = Clock.System.now().epochSeconds
                                this[ChapterTable.version] = max(backupChapter.version, dbChapter[ChapterTable.version]) + 1
                                this[ChapterTable.isSyncing] = true
                            }

                            SyncRestoreMode.NONE -> {}
                        }
                    }
                }.toExecutable()
                .execute(this@dbTransaction)
        }

        if (flags.includeChapters || flags.includeHistory) {
            restoreUserChapterState(
                insertedChapterIds.zip(chaptersToInsert).map { (chapterId, chapter) -> Triple(chapterId, chapter, null) } +
                    chaptersToUpdateToDbChapter.map { (chapter, dbChapter) -> Triple(dbChapter[ChapterTable.id].value, chapter, dbChapter) },
                historyByChapter,
                flags,
                syncMode,
                userId,
            )
        }

        if (flags.includeClientData) {
            val chaptersToInsertByChapterId = insertedChapterIds.zip(chaptersToInsert)
            val chapterToUpdateByChapterId =
                chaptersToUpdateToDbChapter.map { (backupChapter, dbChapter) ->
                    dbChapter[ChapterTable.id].value to
                        backupChapter
                }
            val metaEntryByChapterId =
                (chaptersToInsertByChapterId + chapterToUpdateByChapterId)
                    .filter { (_, backupChapter) -> backupChapter.meta.isNotEmpty() }
                    .associate { (chapterId, backupChapter) ->
                        chapterId to backupChapter.meta
                    }

            if (metaEntryByChapterId.isNotEmpty()) {
                modifyChaptersMetas(metaEntryByChapterId, userId)
            }
        }
    }

    /** The read state, bookmarks and history of one account, merged with what it already has. */
    private fun restoreUserChapterState(
        chapters: List<Triple<Int, BackupChapter, ResultRow?>>,
        historyByChapter: Map<String, List<Long>>,
        flags: BackupFlags,
        syncMode: SyncRestoreMode,
        userId: Int,
    ) {
        if (chapters.isEmpty()) {
            return
        }

        val own =
            chapters
                .map { it.first }
                .chunked(1000)
                .flatMap { ids ->
                    UserChapterTable
                        .selectAll()
                        .where { (UserChapterTable.user eq userId) and (UserChapterTable.chapter inList ids) }
                        .toList()
                }.associateBy { it[UserChapterTable.chapter].value }

        val toInsert = mutableListOf<Triple<Int, Boolean, Triple<Boolean, Int, Long>>>()
        val toUpdate = mutableListOf<Pair<Int, Triple<Boolean, Boolean, Pair<Int, Long>>>>()

        chapters.forEach { (chapterId, chapter, dbChapter) ->
            val ownRow = own[chapterId]
            // what the account already has: its own row, or for the first account the mirrored columns of the chapter
            val hadRead = ownRow?.get(UserChapterTable.isRead) ?: (userId == 1 && dbChapter?.get(ChapterTable.isRead) == true)
            val hadBookmark = ownRow?.get(UserChapterTable.isBookmarked) ?: (userId == 1 && dbChapter?.get(ChapterTable.isBookmarked) == true)
            val hadPage = ownRow?.get(UserChapterTable.lastPageRead) ?: if (userId == 1) dbChapter?.get(ChapterTable.lastPageRead) ?: 0 else 0
            val hadReadAt = ownRow?.get(UserChapterTable.lastReadAt) ?: if (userId == 1) dbChapter?.get(ChapterTable.lastReadAt) ?: 0L else 0L

            val backupReadAt = historyByChapter[chapter.url]?.maxOrNull()?.milliseconds?.inWholeSeconds ?: 0L
            val adopt = syncMode == SyncRestoreMode.ADOPT

            val read = if (!flags.includeChapters) hadRead else if (adopt) chapter.read else chapter.read || hadRead
            val bookmark = if (!flags.includeChapters) hadBookmark else if (adopt) chapter.bookmark else chapter.bookmark || hadBookmark
            val page =
                if (!flags.includeChapters) hadPage else if (adopt) chapter.lastPageRead.coerceAtLeast(0) else max(chapter.lastPageRead, hadPage).coerceAtLeast(0)
            val readAt = if (!flags.includeHistory) hadReadAt else max(backupReadAt, hadReadAt)

            if (ownRow != null) {
                toUpdate.add(chapterId to Triple(read, bookmark, page to readAt))
            } else if (read || bookmark || page > 0 || readAt > 0) {
                toInsert.add(Triple(chapterId, read, Triple(bookmark, page, readAt)))
            }
        }

        if (toInsert.isNotEmpty()) {
            UserChapterTable.batchInsert(toInsert, shouldReturnGeneratedValues = false) { (chapterId, read, rest) ->
                this[UserChapterTable.user] = EntityID(userId, UserTable)
                this[UserChapterTable.chapter] = EntityID(chapterId, ChapterTable)
                this[UserChapterTable.isRead] = read
                this[UserChapterTable.isBookmarked] = rest.first
                this[UserChapterTable.lastPageRead] = rest.second
                this[UserChapterTable.lastReadAt] = rest.third
            }
        }

        if (toUpdate.isNotEmpty()) {
            BatchUpdateStatement(UserChapterTable)
                .apply {
                    toUpdate.forEach { (chapterId, state) ->
                        addBatch(EntityID(own.getValue(chapterId)[UserChapterTable.id].value, UserChapterTable))
                        this[UserChapterTable.isRead] = state.first
                        this[UserChapterTable.isBookmarked] = state.second
                        this[UserChapterTable.lastPageRead] = state.third.first
                        this[UserChapterTable.lastReadAt] = state.third.second
                    }
                }.toExecutable()
                .execute(TransactionManager.current())
        }
    }

    private fun restoreMangaCategoryData(
        mangaId: Int,
        categoryIds: List<Int>,
        syncMode: SyncRestoreMode,
        userId: Int,
    ) {
        // CONVERGE keeps the union so a local-only link survives and wins the next upload
        if (syncMode != SyncRestoreMode.CONVERGE) {
            // only the links to the categories of this account, the other accounts keep theirs
            val ownedCategoryIds =
                CategoryTable
                    .select(CategoryTable.id)
                    .where { CategoryTable.ownedBy(userId) }
                    .map { it[CategoryTable.id].value }
            CategoryMangaTable.deleteWhere {
                (CategoryMangaTable.manga eq mangaId) and (CategoryMangaTable.category inList ownedCategoryIds)
            }
        }
        CategoryManga.addMangaToCategories(mangaId, categoryIds)
    }

    private fun restoreMangaTrackerData(
        mangaId: Int,
        tracks: List<BackupTracking>,
        userId: Int,
    ) {
        val dbTrackRecordsByTrackerId =
            Tracker
                .getTrackRecordsByMangaId(mangaId, userId)
                .mapNotNull { it.record?.toTrack() }
                .associateBy { it.tracker_id }

        val (existingTracks, newTracks) =
            tracks
                .mapNotNull { backupTrack ->
                    val track = backupTrack.toTrack(mangaId)

                    val isUnsupportedTracker = TrackerManager.getTracker(track.tracker_id, userId) == null
                    if (isUnsupportedTracker) {
                        return@mapNotNull null
                    }

                    val dbTrack =
                        dbTrackRecordsByTrackerId[backupTrack.syncId]
                            ?: // new track
                            return@mapNotNull track

                    if (track.toTrackRecordDataClass().forComparison() == dbTrack.toTrackRecordDataClass().forComparison()) {
                        return@mapNotNull null
                    }

                    dbTrack.also {
                        it.remote_id = track.remote_id
                        it.library_id = track.library_id
                        it.last_chapter_read = max(dbTrack.last_chapter_read, track.last_chapter_read)
                    }
                }.partition { (it.id ?: -1) > 0 }

        Tracker.updateTrackRecords(existingTracks)
        Tracker.insertTrackRecords(newTracks, userId)
    }

    private fun TrackRecordDataClass.forComparison() = this.copy(id = 0, mangaId = 0)
}
