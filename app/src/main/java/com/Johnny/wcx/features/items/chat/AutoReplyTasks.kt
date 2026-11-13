package com.Johnny.wcx.features.items.chat

import kotlinx.coroutines.*

/** Bounded, process-local scheduling. No message text, credentials or network operations here. */
internal class AutoReplyTasks(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    class Ticket internal constructor(val talker: String, internal val id: Long) { internal lateinit var job: Job }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = mutableMapOf<String, Ticket>()
    private val manual = mutableMapOf<String, Int>()
    private val seen = LinkedHashSet<Pair<String, Long>>()
    private val lastRequest = mutableMapOf<String, Long>()
    private val requests = ArrayDeque<Long>()
    private var enabled = false

    @Synchronized fun enable() { enabled = true }
    @Synchronized fun disable() {
        enabled = false
        pending.values.forEach { it.job.cancel() }
        pending.clear()
    }
    @Synchronized fun intervene(talker: String) { pending.remove(talker)?.job?.cancel() }
    @Synchronized fun beginManual(talker: String): () -> Unit {
        intervene(talker)
        manual[talker] = (manual[talker] ?: 0) + 1
        var closed = false
        return { synchronized(this) {
            if (!closed) {
                closed = true
                val remaining = (manual[talker] ?: 1) - 1
                if (remaining == 0) manual.remove(talker) else manual[talker] = remaining
            }
        } }
    }
    @Synchronized fun current(ticket: Ticket): Boolean =
        enabled && pending[ticket.talker] === ticket && ticket.job.isActive && ticket.talker !in manual

    @Synchronized fun cooldown(ticket: Ticket): Long =
        ((lastRequest[ticket.talker]?.plus(15_000) ?: now()) - now()).coerceAtLeast(0)

    @Synchronized fun reserveRequest(ticket: Ticket): Boolean {
        if (!current(ticket)) return false
        val time = now()
        while (requests.isNotEmpty() && time - requests.first() >= 60_000) requests.removeFirst()
        if (requests.size >= 10 || cooldown(ticket) > 0) return false
        requests.addLast(time)
        lastRequest[ticket.talker] = time
        lastRequest.entries.removeAll { time - it.value >= 60_000 }
        return true
    }

    @Synchronized fun schedule(talker: String, id: Long, block: suspend (Ticket) -> Unit) {
        if (!enabled || talker in manual || id <= 0 || !seen.add(talker to id)) return
        while (seen.size > 512) seen.remove(seen.first())
        if ((pending[talker]?.id ?: 0) > id) return
        intervene(talker)
        if (pending.size >= 16) return
        val ticket = Ticket(talker, id)
        ticket.job = scope.launch(start = CoroutineStart.LAZY) {
            try { block(ticket) } finally {
                synchronized(this@AutoReplyTasks) {
                    if (pending[talker] === ticket) pending.remove(talker)
                }
            }
        }
        pending[talker] = ticket
        ticket.job.start()
    }
}
