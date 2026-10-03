package suwayomi.tachidesk.manga.impl.track.tracker

import android.app.Application
import android.content.Context
import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.manga.impl.track.tracker.anilist.Anilist
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object TrackerPreferences {
    private val preferenceStore =
        Injekt.get<Application>().getSharedPreferences("tracker", Context.MODE_PRIVATE)
    private val logger = KotlinLogging.logger {}

    fun getTrackUsername(sync: Tracker) = preferenceStore.getString(trackUsername(sync), "")

    fun getTrackPassword(sync: Tracker) = preferenceStore.getString(trackPassword(sync), "")

    fun trackAuthExpired(tracker: Tracker) =
        preferenceStore.getBoolean(
            trackTokenExpired(tracker),
            false,
        )

    fun setTrackCredentials(
        sync: Tracker,
        username: String,
        password: String,
    ) {
        preferenceStore
            .edit()
            .putString(trackUsername(sync), username)
            .putString(trackPassword(sync), password)
            .putBoolean(trackTokenExpired(sync), false)
            .apply()
    }

    fun getTrackToken(sync: Tracker) = preferenceStore.getString(trackToken(sync), "")

    fun setTrackToken(
        sync: Tracker,
        token: String?,
    ) {
        if (token == null) {
            preferenceStore
                .edit()
                .remove(trackToken(sync))
                .putBoolean(trackTokenExpired(sync), false)
                .apply()
        } else {
            preferenceStore
                .edit()
                .putString(trackToken(sync), token)
                .putBoolean(trackTokenExpired(sync), false)
                .apply()
        }
    }

    fun setTrackTokenExpired(sync: Tracker) {
        preferenceStore
            .edit()
            .putBoolean(trackTokenExpired(sync), true)
            .apply()
    }

    fun getScoreType(sync: Tracker) = preferenceStore.getString(scoreType(sync), Anilist.POINT_10)

    fun setScoreType(
        sync: Tracker,
        scoreType: String,
    ) = preferenceStore
        .edit()
        .putString(scoreType(sync), scoreType)
        .apply()

    fun autoUpdateTrack() = preferenceStore.getBoolean("pref_auto_update_manga_sync_key", true)

    // the keys of the first account are the ones from before there were several accounts, so its logins stay as they are
    private fun key(
        name: String,
        tracker: Tracker,
    ) = if (tracker.userId == 1) "${name}_${tracker.id}" else "${name}_${tracker.id}_user${tracker.userId}"

    fun trackUsername(tracker: Tracker) = key("pref_mangasync_username", tracker)

    private fun trackPassword(tracker: Tracker) = key("pref_mangasync_password", tracker)

    private fun trackToken(tracker: Tracker) = key("track_token", tracker)

    private fun trackTokenExpired(tracker: Tracker) = key("track_token_expired", tracker)

    private fun scoreType(tracker: Tracker) = key("score_type", tracker)
}
