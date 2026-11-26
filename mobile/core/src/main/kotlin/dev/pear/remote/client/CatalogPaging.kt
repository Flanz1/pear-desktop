package dev.pear.remote.client

import dev.pear.remote.protocol.CatalogAppend
import dev.pear.remote.protocol.CatalogItem
import dev.pear.remote.protocol.ServerMessage

/** The continuation rule of SPEC 10.4, as pure functions. */
object CatalogPaging {
    /** Stop loading more after this many items on one page (SPEC 10.4). */
    const val MAX_ITEMS = 2_000

    /** Joins an answer to the page being shown. A first page (append null) replaces it. */
    fun merge(current: ServerMessage.CatalogPage?, next: ServerMessage.CatalogPage): ServerMessage.CatalogPage {
        if (current == null || next.append == null) return next
        val sections = when (next.append) {
            CatalogAppend.SECTIONS -> current.sections + next.sections
            CatalogAppend.ITEMS -> {
                val add = next.sections.single()
                val last = current.sections.lastOrNull()
                if (last == null) listOf(add) else current.sections.dropLast(1) + last.copy(items = last.items + add.items)
            }
        }
        // Keep the first page's header, chips and title.
        return current.copy(sections = sections, continuation = next.continuation)
    }

    fun itemCount(page: ServerMessage.CatalogPage) = page.sections.sumOf { it.items.size }

    fun canLoadMore(page: ServerMessage.CatalogPage) = page.continuation != null && itemCount(page) < MAX_ITEMS

    /** Where an item is on a page, so it can be found again after a reload (refs can expire). */
    data class Locator(val section: Int, val index: Int, val title: String)

    fun locate(page: ServerMessage.CatalogPage, ref: String): Locator? {
        page.sections.forEachIndexed { s, sec ->
            sec.items.forEachIndexed { i, item -> if (item.ref == ref) return Locator(s, i, item.title) }
        }
        return null
    }

    /** The item at [loc] on a reloaded page, if it's still the same one (by title). */
    fun find(page: ServerMessage.CatalogPage, loc: Locator): CatalogItem? =
        page.sections.getOrNull(loc.section)?.items?.getOrNull(loc.index)?.takeIf { it.title == loc.title }
            ?: page.sections.asSequence().flatMap { it.items }.firstOrNull { it.title == loc.title }
}
