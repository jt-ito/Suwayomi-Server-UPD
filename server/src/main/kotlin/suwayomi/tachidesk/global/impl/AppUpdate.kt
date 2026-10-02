package suwayomi.tachidesk.global.impl

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import suwayomi.tachidesk.manga.impl.util.network.await
import uy.kohesive.injekt.injectLazy

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

data class UpdateDataClass(
    /** [channel] mirrors [suwayomi.tachidesk.server.BuildConfig.BUILD_TYPE] */
    val channel: String,
    val tag: String,
    val url: String,
)

object AppUpdate {
    private const val LATEST_STABLE_CHANNEL_URL = "https://api.github.com/repos/jt-ito/tsundoku/releases/latest"
    private const val LATEST_PREVIEW_CHANNEL_URL = "https://api.github.com/repos/jt-ito/tsundoku/releases/latest"

    private val json: Json by injectLazy()
    private val network: NetworkHelper by injectLazy()

    suspend fun checkUpdate(): List<UpdateDataClass> =
        listOf("Stable" to LATEST_STABLE_CHANNEL_URL, "Preview" to LATEST_PREVIEW_CHANNEL_URL).mapNotNull { (channel, url) ->
            // GitHub answers 404 for a repo that has no release yet; that means "no update", not a failed check
            val response =
                try {
                    network.client.newCall(GET(url)).await().body.string()
                } catch (e: Exception) {
                    if (e.message == "HTTP error 404") return@mapNotNull null
                    throw e
                }
            val release = json.parseToJsonElement(response).jsonObject
            val tag = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            UpdateDataClass(channel, tag, release["html_url"]!!.jsonPrimitive.content)
        }
}
