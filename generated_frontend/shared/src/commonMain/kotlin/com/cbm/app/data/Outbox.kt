package com.cbm.app.data

import androidx.compose.runtime.mutableStateListOf
import com.cbm.app.domain.TechReport

/** Durable outbox (PRD 1.0 §6.3 + v2 §10): items are scoped per account and
 *  are never delivered under another account (acceptance test 8). This
 *  in-memory version models the semantics; the Room table gains `kind` and
 *  `account_id` exactly as specified. */
enum class OutboxKind { CAPTURE, TECH_REPORT }

data class OutboxItem(
    val id: String,
    val accountEmail: String,
    val kind: OutboxKind,
    val label: String,
    val reportId: String? = null,
    val description: String? = null,
    val replacementOf: String? = null,
    val techReport: TechReport? = null,
)

class Outbox {
    val items = mutableStateListOf<OutboxItem>()
    fun enqueue(item: OutboxItem) { items.add(item) }
    fun remove(id: String) { items.removeAll { it.id == id } }
    fun forAccount(email: String): List<OutboxItem> = items.filter { it.accountEmail == email }
}
