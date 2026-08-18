package com.utsav.ffdownloader.core

import androidx.lifecycle.MutableLiveData
import java.util.UUID

/**
 * Single in-memory source of truth for the queue, shared between MainActivity
 * (UI + resolve flow) and DownloadService (background download loop). Both
 * run in the same process, so a plain LiveData-backed singleton is enough --
 * no cross-process IPC needed.
 *
 * IMPORTANT: reads/writes go through [master] under [lock], not through
 * LiveData.value. LiveData.postValue() from a background thread is
 * fire-and-forget -- .value isn't updated until the main thread processes
 * it -- so a naive "read items.value, map, postValue" pattern race-loses
 * updates when called rapidly from a download thread (e.g. a status change
 * to DOWNLOADING gets silently clobbered by the very next progress tick
 * because that tick's map() was computed from a stale .value read before
 * the status change had been applied). Keeping our own synchronized master
 * list sidesteps that entirely.
 */
object QueueRepository {

    private val lock = Any()
    private var master: List<QueueItem> = emptyList()

    val items = MutableLiveData<List<QueueItem>>(emptyList())

    /**
     * Category is auto-detected per link from its extension (see
     * [CategoryDetector]) -- there's no manual picker anymore. Items
     * already in-flight keep whatever category they were queued under,
     * even if a re-resolve would now detect differently -- their
     * destination folder shouldn't move mid-download.
     *
     * IMPORTANT: this is additive, not a replace. It used to rebuild [master]
     * from just [rawLinks] (the current paste-box contents), which silently
     * dropped every previously-queued item -- including ones actively
     * downloading -- the moment a second batch was pasted, since they weren't
     * present in the new rawLinks. Now we keep every existing item and only
     * add/replace entries for the links just passed in, so an in-flight
     * download from a prior call is never removed from [master].
     */
    fun setLinks(rawLinks: List<String>) {
        synchronized(lock) {
            val current = master.associateBy { it.sourceUrl }
            val updatedOrNew = rawLinks.map { link ->
                val existing = current[link]
                when {
                    existing == null ->
                        QueueItem(id = UUID.randomUUID().toString(), sourceUrl = link, category = CategoryDetector.detect(link))
                    // Finished or failed/cancelled items get a clean retry instead of being
                    // stuck reusing their old terminal status (which Prepare would then skip).
                    existing.status == ItemStatus.DONE || existing.status == ItemStatus.FAILED ->
                        QueueItem(id = UUID.randomUUID().toString(), sourceUrl = link, category = CategoryDetector.detect(link))
                    else -> existing // leave anything still in-flight alone
                }
            }
            val untouched = master.filter { it.sourceUrl !in rawLinks.toSet() }
            master = untouched + updatedOrNew
            items.postValue(master)
        }
    }

    fun update(id: String, mutate: (QueueItem) -> QueueItem) {
        synchronized(lock) {
            master = master.map { if (it.id == id) mutate(it) else it }
            items.postValue(master)
        }
    }

    /**
     * Atomically finds the first READY item and marks it DOWNLOADING in one
     * step, so multiple concurrent download workers can't both grab the
     * same item.
     */
    fun claimNextReady(): QueueItem? {
        synchronized(lock) {
            val idx = master.indexOfFirst { it.status == ItemStatus.READY }
            if (idx == -1) return null
            val claimed = master[idx].copy(
                status = ItemStatus.DOWNLOADING,
                downloadStartedAtMs = System.currentTimeMillis()
            )
            master = master.toMutableList().also { it[idx] = claimed }
            items.postValue(master)
            return claimed
        }
    }

    fun clearFinishedAndFailed() {
        synchronized(lock) {
            master = master.filter { it.status != ItemStatus.DONE && it.status != ItemStatus.FAILED }
            items.postValue(master)
        }
    }

    fun current(): List<QueueItem> = synchronized(lock) { master }
}
