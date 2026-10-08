package com.route0465.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * Suggested order, from your own sales. Taco-Boys leaves its Suggested column blank until a model exists;
 * this is a plain, explainable first version:
 *
 *   weekly  = cases sold per week, averaged over the last 4 full Sat–Fri weeks that have sales
 *   need    = weekly × (days from the last inventory to this delivery + the 7-day cycle) ÷ 7
 *             + cases you ran short by last delivery
 *             − cases on hand at the last import
 *   suggested = need rounded up, never below 0
 *
 * Promotions are shown as tags beside it, not baked in, so you decide the bump.
 */
data class Suggestion(val cases: Int?, val weekly: Double)

object Suggest {
    /** Same as Taco-Boys' order guide: flag an order more than 5 cases off the suggestion. */
    const val FLAG_CASES = 5

    fun compute(
        repo: Db,
        items: List<OrderItem>,
        onHand: Map<String, Double>,
        stockDate: LocalDate?,
        delivery: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): Map<String, Suggestion> {
        val thisWeek = Periods.weekStart(today)
        val sold = repo.unitsSoldBetween(thisWeek.minusDays(28), thisWeek.minusDays(1))
        val weeks = sold.map { Periods.weekStart(it.first) }.distinct().size
        if (weeks == 0) return items.associate { it.code to Suggestion(null, 0.0) }
        val packs = repo.casePacks()
        val unitsByCode = sold.groupBy { it.second.uppercase() }.mapValues { e -> e.value.sumOf { it.third } }
        val from = stockDate ?: today
        val days = max(0L, ChronoUnit.DAYS.between(from, delivery)) + 7
        return items.associate { item ->
            val code = item.code.uppercase()
            val pack = packs[code]?.takeIf { it > 0 } ?: 1.0
            val weekly = (unitsByCode[code] ?: 0.0) / pack / weeks
            val need = weekly * days / 7.0 + (if (item.ranShort) item.shortBy.toDouble() else 0.0) - (onHand[code] ?: 0.0)
            item.code to Suggestion(ceil(max(0.0, need) - 1e-9).toInt(), weekly)
        }
    }
}
