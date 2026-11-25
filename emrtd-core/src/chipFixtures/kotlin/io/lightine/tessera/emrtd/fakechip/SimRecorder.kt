package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadUpdate

/** A value and the virtual time it arrived at. */
internal class Timed<T>(
    val atMillis: Long,
    val value: T,
) {
    override fun toString(): String = "$value@${atMillis}ms"
}

/**
 * Everything a simulated read tells the app, with the virtual time of each call (TES-346): the
 * updates in order and the endings (the read promises exactly one).
 */
internal class SimRecorder(
    private val now: () -> Long,
) : ChipReadObserver {
    val updates: MutableList<Timed<ChipReadUpdate>> = mutableListOf()
    val endings: MutableList<Timed<ChipReadEnding>> = mutableListOf()

    /** Every call in order, updates and endings, to check nothing comes after the ending. */
    val calls: MutableList<Any> = mutableListOf()

    override fun onUpdate(update: ChipReadUpdate) {
        updates += Timed(now(), update)
        calls += update
    }

    override fun onEnding(ending: ChipReadEnding) {
        endings += Timed(now(), ending)
        calls += ending
    }

    val ending: ChipReadEnding get() = endings.single().value

    val states: List<ChipReadState> get() = updates.map { it.value.state }

    /** The state names in order, with repeats of the same name folded into one. */
    val stateNames: List<String>
        get() =
            states.map { it::class.simpleName ?: "?" }.fold(mutableListOf()) { acc, name ->
                if (acc.lastOrNull() != name) acc += name
                acc
            }

    /** When the first update in [state] came, or `null`. */
    inline fun <reified S : ChipReadState> firstAt(): Long? = updates.firstOrNull { it.value.state is S }?.atMillis

    /**
     * The promises every read keeps, whatever happened: exactly one ending and nothing after it;
     * the progress fraction never goes back; below 100 percent until the ending, and 100 only for
     * [ChipReadEnding.Complete].
     */
    fun checkPromises(): String? {
        if (endings.size != 1) return "endings: ${endings.size} ($endings)"
        if (calls.last() !is ChipReadEnding) return "a call came after the ending: ${calls.takeLast(3)}"
        var last = 0.0
        for (u in updates) {
            val p = u.value.progress
            if (p.fraction < last) return "progress went back at $u: ${updates.map { it.value.progress.fraction }}"
            if (p.fraction >= 1.0 || p.percent > 99) return "100 percent before the ending at $u"
            last = p.fraction
        }
        val end = ending
        if (end.progress.fraction < last) return "the ending's progress went back"
        if (end is ChipReadEnding.Complete && end.progress.percent != 100) return "Complete without 100 percent"
        if (end !is ChipReadEnding.Complete && end.progress.percent > 99) return "100 percent without Complete"
        return null
    }
}
