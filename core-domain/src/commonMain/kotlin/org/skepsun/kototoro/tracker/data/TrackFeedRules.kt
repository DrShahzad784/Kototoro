package org.skepsun.kototoro.tracker.data

import org.skepsun.kototoro.core.db.MangaDatabase

fun mergeRestoredTrackNewChapters(local: TrackEntity, remote: TrackEntity): Int {
    val newer = if (remote.isNewerThan(local)) remote else local
    return if (newer.newChapters == 0) 0 else maxOf(local.newChapters, remote.newChapters)
}

fun TrackEntity.isNewerThan(other: TrackEntity): Boolean {
    return when {
        lastChapterDate != other.lastChapterDate -> lastChapterDate > other.lastChapterDate
        lastCheckTime != other.lastCheckTime -> lastCheckTime > other.lastCheckTime
        else -> lastChapterId > other.lastChapterId
    }
}

fun TrackEntity.canBeClearedBy(log: TrackLogEntity): Boolean {
    val trackTime = maxOf(lastChapterDate, lastCheckTime)
    return trackTime <= 0L || log.createdAt >= trackTime
}

/** A restored track merged into the local one: the newer check wins, counters and dates keep their maxima. */
fun TrackEntity.mergeWithRestored(remote: TrackEntity): TrackEntity {
    val remoteIsNewer = remote.isNewerThan(this)
    val newer = if (remoteIsNewer) remote else this
    val mergedLastError = when {
        newer.lastResult == TrackEntity.RESULT_FAILED -> newer.lastError
        lastResult == TrackEntity.RESULT_FAILED && remote.lastResult != TrackEntity.RESULT_FAILED -> remote.lastError
        else -> null
    }
    return TrackEntity(
        mangaId = mangaId,
        lastChapterId = newer.lastChapterId,
        newChapters = mergeRestoredTrackNewChapters(this, remote),
        lastCheckTime = maxOf(lastCheckTime, remote.lastCheckTime),
        lastChapterDate = maxOf(lastChapterDate, remote.lastChapterDate),
        lastResult = newer.lastResult,
        lastError = mergedLastError,
    )
}

/** Backup restore of one track row (Android and iOS share the rule). */
suspend fun MangaDatabase.mergeRestoredTrack(remote: TrackEntity) {
    val dao = getTracksDao()
    val local = dao.find(remote.mangaId)
    dao.upsert(local?.mergeWithRestored(remote) ?: remote)
}

/** Backup restore of one feed log row: duplicates are not repeated, and a read state clears the counter. */
suspend fun MangaDatabase.mergeRestoredTrackLog(remote: TrackLogEntity) {
    val dao = getTrackLogsDao()
    val existing = dao.findDuplicate(
        mangaId = remote.mangaId,
        chapters = remote.chapters,
        createdAt = remote.createdAt,
    )
    if (existing == null) {
        dao.insert(remote)
    } else if (existing.isUnread && !remote.isUnread) {
        dao.markAsRead(existing.id)
        getTracksDao().find(existing.mangaId)
            ?.takeIf { it.canBeClearedBy(remote) }
            ?.let { getTracksDao().clearCounter(existing.mangaId) }
    } else if (!existing.isUnread) {
        getTracksDao().find(existing.mangaId)
            ?.takeIf { it.canBeClearedBy(existing) }
            ?.let { getTracksDao().clearCounter(existing.mangaId) }
    }
}
