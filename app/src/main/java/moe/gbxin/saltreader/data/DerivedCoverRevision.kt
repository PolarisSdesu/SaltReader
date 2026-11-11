package moe.gbxin.saltreader.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bumped whenever a derived cover file is (re)written.
 *
 * Covers are derived files under `coversDir/<bookId>.jpg`; the shelf decodes them through an
 * in-memory LRU keyed by path + file stat. When a cover appears while the reader is open (e.g. a
 * reflowable EPUB whose OPF cover is extracted on first open), the previously composed shelf item
 * may still remember the "no cover" decode result, so the UI subscribes to this revision and
 * re-reads the cover on the next composition. Purely an in-process notification; nothing persisted.
 */
object DerivedCoverRevision {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun notifyCoverChanged() {
        _revision.value = _revision.value + 1L
    }
}
