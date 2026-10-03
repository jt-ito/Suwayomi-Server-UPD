package suwayomi.tachidesk.manga.impl.track

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.BatchUpdateStatement
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jsoup.Jsoup
import suwayomi.tachidesk.manga.impl.track.tracker.DeletableTracker
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.model.toTrack
import suwayomi.tachidesk.manga.impl.track.tracker.model.toTrackRecordDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaTrackerDataClass
import suwayomi.tachidesk.manga.model.dataclass.TrackSearchDataClass
import suwayomi.tachidesk.manga.model.dataclass.TrackerDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.manga.model.table.ownedBy
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.finishDate
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.lastChapterRead
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.libraryId
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.mangaId
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.private
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.remoteId
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.remoteUrl
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.score
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.startDate
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.status
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.title
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.totalChapters
import suwayomi.tachidesk.manga.model.table.TrackRecordTable.trackerId
import suwayomi.tachidesk.manga.model.table.TrackSearchTable
import suwayomi.tachidesk.manga.model.table.UserChapterTable
import suwayomi.tachidesk.manga.model.table.insertAll
import suwayomi.tachidesk.server.generated.BuildConfig
import suwayomi.tachidesk.server.user.model.UserTable
import java.io.InputStream

object Track {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = KotlinLogging.logger {}

    fun getTrackerList(userId: Int = 1): List<TrackerDataClass> {
        val trackers = TrackerManager.services(userId)
        return trackers.map {
            val isLogin = it.isLoggedIn
            val authUrl = if (isLogin) null else it.authUrl()
            TrackerDataClass(
                id = it.id,
                name = it.name,
                icon = proxyThumbnailUrl(it.id),
                isLogin = isLogin,
                authUrl = authUrl,
            )
        }
    }

    suspend fun login(
        input: LoginInput,
        userId: Int = 1,
    ) {
        val tracker = TrackerManager.getTracker(input.trackerId, userId)!!
        if (input.callbackUrl != null) {
            tracker.authCallback(input.callbackUrl)
        } else {
            tracker.loginImpl(input.username ?: "", input.password ?: "")
        }
    }

    fun logout(
        input: LogoutInput,
        userId: Int = 1,
    ) {
        val tracker = TrackerManager.getTracker(input.trackerId, userId)!!
        tracker.logout()
    }

    fun proxyThumbnailUrl(trackerId: Int): String = "/api/v1/track/$trackerId/thumbnail"

    fun getTrackerThumbnail(trackerId: Int): Pair<InputStream, String> {
        val tracker = TrackerManager.getTracker(trackerId)!!
        val logo = BuildConfig::class.java.getResourceAsStream(tracker.getLogo())!!
        return logo to "image/png"
    }

    fun getTrackRecordsByMangaId(
        mangaId: Int,
        userId: Int = 1,
    ): List<MangaTrackerDataClass> {
        val recordMap =
            transaction {
                TrackRecordTable
                    .selectAll()
                    .where { (TrackRecordTable.mangaId eq mangaId) and TrackRecordTable.ownedBy(userId) }
                    .map { it.toTrackRecordDataClass() }
            }.associateBy { it.trackerId }

        val trackers = TrackerManager.services(userId)
        return trackers.map {
            val record = recordMap[it.id]
            if (record != null) {
                val track =
                    Track.create(it.id).also { t ->
                        t.score = record.score
                    }
                record.scoreString = it.displayScore(track)
            }
            MangaTrackerDataClass(
                id = it.id,
                name = it.name,
                icon = proxyThumbnailUrl(it.id),
                statusList = it.getStatusList(),
                statusTextMap = it.getStatusList().associateWith { k -> it.getStatus(k).orEmpty() },
                scoreList = it.getScoreList(),
                record = record,
            )
        }
    }

    suspend fun search(
        input: SearchInput,
        userId: Int = 1,
    ): List<TrackSearchDataClass> {
        val tracker = TrackerManager.getTracker(input.trackerId, userId)!!
        val list = tracker.search(input.title)
        return list.insertAll().map {
            TrackSearchDataClass(
                id = it[TrackSearchTable.id].value,
                trackerId = it[TrackSearchTable.trackerId],
                remoteId = it[TrackSearchTable.remoteId],
                libraryId = it[TrackSearchTable.libraryId],
                title = it[TrackSearchTable.title],
                lastChapterRead = it[TrackSearchTable.lastChapterRead],
                totalChapters = it[TrackSearchTable.totalChapters],
                trackingUrl = it[TrackSearchTable.trackingUrl],
                coverUrl = it[TrackSearchTable.coverUrl],
                summary = it[TrackSearchTable.summary],
                publishingStatus = it[TrackSearchTable.publishingStatus],
                publishingType = it[TrackSearchTable.publishingType],
                startDate = it[TrackSearchTable.startDate],
                status = it[TrackSearchTable.status],
                score = it[TrackSearchTable.score],
                scoreString = null,
                startedReadingDate = it[TrackSearchTable.startedReadingDate],
                finishedReadingDate = it[TrackSearchTable.finishedReadingDate],
                private = it[TrackSearchTable.private],
            )
        }
    }

    private fun ResultRow.toTrackFromSearch(mangaId: Int): Track =
        Track.create(this[TrackSearchTable.trackerId]).also {
            it.manga_id = mangaId
            it.remote_id = this[TrackSearchTable.remoteId]
            it.title = this[TrackSearchTable.title]
            it.total_chapters = this[TrackSearchTable.totalChapters]
            it.tracking_url = this[TrackSearchTable.trackingUrl]
        }

    suspend fun bind(
        mangaId: Int,
        trackerId: Int,
        remoteId: Long,
        private: Boolean,
        userId: Int = 1,
    ) {
        val track =
            transaction {
                TrackSearchTable
                    .selectAll()
                    .where {
                        TrackSearchTable.trackerId eq trackerId and
                            (TrackSearchTable.remoteId eq remoteId)
                    }.firstOrNull()
                    ?.toTrackFromSearch(mangaId)
                    ?: TrackRecordTable
                        .selectAll()
                        .where {
                            (TrackRecordTable.trackerId eq trackerId) and
                                (TrackRecordTable.remoteId eq remoteId) and
                                TrackRecordTable.ownedBy(userId)
                        }.first()
                        .toTrack()
            }.apply {
                this.manga_id = mangaId
                this.private = private
            }

        val tracker = TrackerManager.getTracker(trackerId, userId)!!

        val chapter = queryMaxReadChapter(mangaId, userId)
        val hasReadChapters = chapter != null
        val chapterNumber = chapter?.get(ChapterTable.chapter_number)

        tracker.bind(track, hasReadChapters)
        val recordId = upsertTrackRecord(track, userId)

        var lastChapterRead: Double? = null
        var startDate: Long? = null
        if (chapterNumber != null && chapterNumber > 0 && chapterNumber > track.last_chapter_read) {
            lastChapterRead = chapterNumber.toDouble()
        }
        if (track.started_reading_date <= 0) {
            val oldestChapter =
                transaction {
                    ChapterTable
                        .selectAll()
                        .where {
                            (ChapterTable.manga eq mangaId) and (ChapterTable.isRead eq true)
                        }.orderBy(ChapterTable.lastReadAt to SortOrder.ASC)
                        .limit(1)
                        .firstOrNull()
                }
            if (oldestChapter != null) {
                startDate = oldestChapter[ChapterTable.lastReadAt] * 1000
            }
        }
        if (lastChapterRead != null || startDate != null) {
            val trackUpdate =
                UpdateInput(
                    recordId = recordId,
                    lastChapterRead = lastChapterRead,
                    startDate = startDate,
                )
            update(trackUpdate, userId)
        }
    }

    fun bindTrackRecord(
        mangaId: Int,
        trackRecordId: Int,
        userId: Int = 1,
    ): Int {
        val (trackRecord, existingTrackRecord) =
            transaction {
                val trackRecord =
                    TrackRecordTable
                        .selectAll()
                        .where {
                            (TrackRecordTable.id eq trackRecordId) and TrackRecordTable.ownedBy(userId)
                        }.first()
                        .toTrackRecordDataClass()

                val existingTrackRecord =
                    TrackRecordTable
                        .selectAll()
                        .where {
                            (TrackRecordTable.mangaId eq mangaId) and
                                (TrackRecordTable.trackerId eq trackRecord.trackerId) and
                                TrackRecordTable.ownedBy(userId)
                        }.firstOrNull()
                        ?.toTrackRecordDataClass()

                trackRecord to existingTrackRecord
            }

        val isAlreadyBoundToManga = trackRecord.mangaId == mangaId
        if (isAlreadyBoundToManga) {
            return trackRecordId
        }

        val hasRecordForTracker = existingTrackRecord != null
        if (hasRecordForTracker) {
            val updatedTrack = trackRecord.copy(id = existingTrackRecord.id, mangaId = mangaId).toTrack()

            return updateTrackRecord(updatedTrack)
        }

        val newTrack = trackRecord.copy(mangaId = mangaId).toTrack()

        return insertTrackRecord(newTrack, userId)
    }

    suspend fun refresh(
        recordId: Int,
        userId: Int = 1,
    ) {
        val recordDb =
            transaction {
                TrackRecordTable.selectAll().where { (TrackRecordTable.id eq recordId) and TrackRecordTable.ownedBy(userId) }.first()
            }

        val tracker = TrackerManager.getTracker(recordDb[TrackRecordTable.trackerId], userId)!!

        val track = recordDb.toTrack()
        tracker.refresh(track)
        upsertTrackRecord(track, userId)
    }

    suspend fun unbind(
        recordId: Int,
        deleteRemoteTrack: Boolean? = false,
        userId: Int = 1,
    ) {
        val recordDb =
            transaction {
                TrackRecordTable.selectAll().where { (TrackRecordTable.id eq recordId) and TrackRecordTable.ownedBy(userId) }.first()
            }

        val tracker = TrackerManager.getTracker(recordDb[TrackRecordTable.trackerId], userId)

        if (deleteRemoteTrack == true && tracker is DeletableTracker) {
            tracker.delete(recordDb.toTrack())
        }

        transaction {
            TrackRecordTable.deleteWhere { TrackRecordTable.id eq recordId }
        }
    }

    suspend fun update(
        input: UpdateInput,
        userId: Int = 1,
    ) {
        if (input.unbind == true) {
            unbind(input.recordId, userId = userId)
            return
        }
        val recordDb =
            transaction {
                TrackRecordTable.selectAll().where { (TrackRecordTable.id eq input.recordId) and TrackRecordTable.ownedBy(userId) }.first()
            }

        val tracker = TrackerManager.getTracker(recordDb[TrackRecordTable.trackerId], userId)!!

        if (input.status != null) {
            recordDb[TrackRecordTable.status] = input.status
            if (input.status == tracker.getCompletionStatus() && recordDb[TrackRecordTable.totalChapters] != 0) {
                recordDb[TrackRecordTable.lastChapterRead] = recordDb[TrackRecordTable.totalChapters]
            }
        }
        if (input.lastChapterRead != null) {
            if (recordDb[TrackRecordTable.lastChapterRead] == 0.0 &&
                recordDb[TrackRecordTable.lastChapterRead] < input.lastChapterRead &&
                recordDb[TrackRecordTable.status] != tracker.getRereadingStatus()
            ) {
                recordDb[TrackRecordTable.status] = tracker.getReadingStatus()
            }
            recordDb[TrackRecordTable.lastChapterRead] = input.lastChapterRead
            if (recordDb[TrackRecordTable.totalChapters] != 0 &&
                input.lastChapterRead.toInt() == recordDb[TrackRecordTable.totalChapters]
            ) {
                recordDb[TrackRecordTable.status] = tracker.getCompletionStatus()
                recordDb[TrackRecordTable.finishDate] = System.currentTimeMillis()
            }
        }
        if (input.scoreString != null) {
            val score = tracker.indexToScore(tracker.getScoreList().indexOf(input.scoreString))
            recordDb[TrackRecordTable.score] = score
        }
        if (input.startDate != null) {
            recordDb[TrackRecordTable.startDate] = input.startDate
        }
        if (input.finishDate != null) {
            recordDb[TrackRecordTable.finishDate] = input.finishDate
        }
        if (input.private != null) {
            recordDb[TrackRecordTable.private] = input.private
        }

        val track = recordDb.toTrack()
        tracker.update(track)

        upsertTrackRecord(track, userId)
    }

    fun asyncTrackChapter(
        mangaIds: Set<Int>,
        userId: Int = 1,
    ) {
        if (!TrackerManager.hasLoggedTracker(userId)) {
            return
        }
        scope.launch {
            mangaIds.forEach {
                trackChapter(it, userId)
            }
        }
    }

    suspend fun trackChapter(
        mangaId: Int,
        userId: Int = 1,
    ) {
        val chapter = queryMaxReadChapter(mangaId, userId)
        val chapterNumber = chapter?.get(ChapterTable.chapter_number)

        logger.info {
            "trackChapter(mangaId= $mangaId): maxReadChapter= #$chapterNumber ${chapter?.get(ChapterTable.name)}"
        }

        if (chapterNumber != null && chapterNumber > 0) {
            trackChapter(mangaId, chapterNumber.toDouble(), userId)
        }
    }

    private fun queryMaxReadChapter(
        mangaId: Int,
        userId: Int = 1,
    ): ResultRow? =
        transaction {
            if (userId == 1) {
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.manga eq mangaId) and (ChapterTable.isRead eq true) }
            } else {
                // the read state of the other accounts is kept apart from the chapter itself
                ChapterTable
                    .innerJoin(UserChapterTable)
                    .selectAll()
                    .where {
                        (ChapterTable.manga eq mangaId) and
                            (UserChapterTable.user eq userId) and
                            (UserChapterTable.isRead eq true)
                    }
            }.orderBy(ChapterTable.chapter_number to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
        }

    private suspend fun trackChapter(
        mangaId: Int,
        chapterNumber: Double,
        userId: Int,
    ) {
        val records =
            transaction {
                TrackRecordTable
                    .selectAll()
                    .where { (TrackRecordTable.mangaId eq mangaId) and TrackRecordTable.ownedBy(userId) }
                    .toList()
            }

        records.forEach {
            try {
                trackChapterForTracker(it, chapterNumber, userId)
            } catch (e: Exception) {
                KotlinLogging
                    .logger("${logger.name}::trackChapter(mangaId= $mangaId, chapterNumber= $chapterNumber)")
                    .error(e) { "failed due to" }
            }
        }
    }

    private suspend fun trackChapterForTracker(
        it: ResultRow,
        chapterNumber: Double,
        userId: Int,
    ) {
        val tracker = TrackerManager.getTracker(it[TrackRecordTable.trackerId], userId) ?: return
        val track = it.toTrack()

        val log =
            KotlinLogging.logger {
                "${logger.name}::trackChapterForTracker(chapterNumber= $chapterNumber, tracker= ${tracker.id}, recordId= ${track.id})"
            }
        log.debug { "called for $tracker, ${track.title} (recordId= ${track.id}, mangaId= ${track.manga_id})" }

        val localLastReadChapter = it[TrackRecordTable.lastChapterRead]

        if (localLastReadChapter == chapterNumber) {
            log.debug { "new chapter is the same as the local last read chapter" }
            return
        }

        if (!tracker.isLoggedIn) {
            upsertTrackRecord(track, userId)
            return
        }

        tracker.refresh(track)
        upsertTrackRecord(track, userId)

        val lastChapterRead = track.last_chapter_read

        log.debug { "remoteLastReadChapter= $lastChapterRead" }

        if (chapterNumber > lastChapterRead) {
            track.last_chapter_read = chapterNumber
            tracker.update(track, true)
            upsertTrackRecord(track, userId)
        }
    }

    fun upsertTrackRecord(
        track: Track,
        userId: Int = 1,
    ): Int =
        transaction {
            val existingRecord =
                TrackRecordTable
                    .selectAll()
                    .where {
                        (TrackRecordTable.mangaId eq track.manga_id) and
                            (TrackRecordTable.trackerId eq track.tracker_id) and
                            TrackRecordTable.ownedBy(userId)
                    }.singleOrNull()

            if (existingRecord != null) {
                track.id = existingRecord[TrackRecordTable.id].value
                updateTrackRecord(track)
                track.id!!
            } else {
                insertTrackRecord(track, userId)
            }
        }

    fun updateTrackRecord(track: Track): Int = updateTrackRecords(listOf(track)).first()

    fun updateTrackRecords(tracks: List<Track>): List<Int> =
        transaction {
            if (tracks.isNotEmpty()) {
                BatchUpdateStatement(TrackRecordTable)
                    .apply {
                        tracks.forEach {
                            addBatch(EntityID(it.id!!, TrackRecordTable))
                            this[remoteId] = it.remote_id
                            this[libraryId] = it.library_id
                            this[title] = it.title
                            this[lastChapterRead] = it.last_chapter_read
                            this[totalChapters] = it.total_chapters
                            this[status] = it.status
                            this[score] = it.score
                            this[remoteUrl] = it.tracking_url
                            this[startDate] = it.started_reading_date
                            this[finishDate] = it.finished_reading_date
                            this[private] = it.private
                        }
                    }.toExecutable()
                    .execute(this@transaction)
            }

            tracks.map { it.id!! }
        }

    fun insertTrackRecord(
        track: Track,
        userId: Int = 1,
    ): Int = insertTrackRecords(listOf(track), userId).first()

    fun insertTrackRecords(
        tracks: List<Track>,
        userId: Int = 1,
    ): List<Int> =
        transaction {
            TrackRecordTable
                .batchInsert(tracks) {
                    this[TrackRecordTable.user] = EntityID(userId, UserTable)
                    this[mangaId] = it.manga_id
                    this[trackerId] = it.tracker_id
                    this[remoteId] = it.remote_id
                    this[libraryId] = it.library_id
                    this[title] = it.title
                    this[lastChapterRead] = it.last_chapter_read
                    this[totalChapters] = it.total_chapters
                    this[status] = it.status
                    this[score] = it.score
                    this[remoteUrl] = it.tracking_url
                    this[startDate] = it.started_reading_date
                    this[finishDate] = it.finished_reading_date
                    this[private] = it.private
                }.map { it[TrackRecordTable.id].value }
        }

    @Serializable
    data class LoginInput(
        val trackerId: Int,
        val callbackUrl: String? = null,
        val username: String? = null,
        val password: String? = null,
    )

    @Serializable
    data class LogoutInput(
        val trackerId: Int,
    )

    @Serializable
    data class SearchInput(
        val trackerId: Int,
        val title: String,
    )

    @Serializable
    data class UpdateInput(
        val recordId: Int,
        val status: Int? = null,
        val lastChapterRead: Double? = null,
        val scoreString: String? = null,
        val startDate: Long? = null,
        val finishDate: Long? = null,
        val unbind: Boolean? = null,
        val private: Boolean? = null,
    )

    fun String.htmlDecode(): String = Jsoup.parse(this).wholeText()
}
