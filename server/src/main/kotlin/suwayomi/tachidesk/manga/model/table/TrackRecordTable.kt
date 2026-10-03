package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar
import suwayomi.tachidesk.server.user.model.UserTable

object TrackRecordTable : IntIdTable() {
    val mangaId = reference("manga_id", MangaTable, ReferenceOption.CASCADE)
    val trackerId = integer("sync_id")
    val remoteId = long("remote_id")
    val libraryId = long("library_id").nullable()
    val title = truncatingVarchar("title", 512)
    val lastChapterRead = double("last_chapter_read")
    val totalChapters = integer("total_chapters")
    val status = integer("status")
    val score = double("score")
    val remoteUrl = varchar("remote_url", 512)
    val startDate = long("start_date")
    val finishDate = long("finish_date")
    val private = bool("private").default(false)
    val user = reference("user_id", suwayomi.tachidesk.server.user.model.UserTable, ReferenceOption.CASCADE).nullable()
}

/** The records of one account. Records from before there were accounts have no user yet and belong to the first one. */
fun TrackRecordTable.ownedBy(userId: Int): Op<Boolean> =
    if (userId == 1) {
        (user eq EntityID(1, UserTable)) or user.isNull()
    } else {
        user eq EntityID(userId, UserTable)
    }
