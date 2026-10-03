package suwayomi.tachidesk.manga.impl.track.tracker

import suwayomi.tachidesk.manga.impl.track.tracker.anilist.Anilist
import suwayomi.tachidesk.manga.impl.track.tracker.bangumi.Bangumi
import suwayomi.tachidesk.manga.impl.track.tracker.kitsu.Kitsu
import suwayomi.tachidesk.manga.impl.track.tracker.mangaupdates.MangaUpdates
import suwayomi.tachidesk.manga.impl.track.tracker.myanimelist.MyAnimeList
import suwayomi.tachidesk.manga.impl.track.tracker.shikimori.Shikimori
import java.util.concurrent.ConcurrentHashMap

object TrackerManager {
    const val MYANIMELIST = 1
    const val ANILIST = 2
    const val KITSU = 3
    const val SHIKIMORI = 4
    const val BANGUMI = 5
    const val KOMGA = 6
    const val MANGA_UPDATES = 7
    const val KAVITA = 8
    const val SUWAYOMI = 9

    private val servicesByUser = ConcurrentHashMap<Int, List<Tracker>>()

    // every account has its own logins, so every account gets its own tracker instances
    fun services(userId: Int = 1): List<Tracker> =
        servicesByUser.getOrPut(userId) {
            listOf(
                MyAnimeList(MYANIMELIST, userId),
                Anilist(ANILIST, userId),
                Kitsu(KITSU, userId),
                MangaUpdates(MANGA_UPDATES, userId),
                Shikimori(SHIKIMORI, userId),
                Bangumi(BANGUMI, userId),
            )
        }

    val services: List<Tracker>
        get() = services(1)

    fun getTracker(
        id: Int,
        userId: Int = 1,
    ) = services(userId).find { it.id == id }

    fun hasLoggedTracker(userId: Int = 1) = services(userId).any { it.isLoggedIn }

    /** Logs a deleted account out of its trackers, so its tokens don't stay behind. */
    fun forgetUser(userId: Int) {
        servicesByUser.remove(userId)?.forEach { tracker ->
            if (tracker.isLoggedIn) {
                runCatching { tracker.logout() }
            }
        }
    }
}
