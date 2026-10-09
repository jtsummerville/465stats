package com.route0465.app

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Inventory Check, ported from Taco-Boys inventory_check.py. Per product, in eaches:
 *
 *     last count − sold + buy backs + delivered  ==  this count
 *
 * Credit pickups are NOT subtracted: they never go back into sellable stock (Taco-Boys validated this on
 * route 0465). Buy backs go back on the truck and get resold, so they're added. Delivered = XSales' reload
 * for each product (the real delivered quantities Taco-Boys never had from the PDFs).
 *
 * Anything that doesn't balance is a finding, in both directions, never netted against each other:
 * product that went missing, and product that appeared. A period where many products rise at once looks
 * like freight that wasn't recorded as reload; then rises can't be told apart from restock, and only a
 * drop bigger than what came in can show. That's reported, never called clean.
 */
object InventoryCheck {
    // Same thresholds as Taco-Boys: lots of products rising at once is a truckload, one is a miscount.
    private const val DELIVERY_MIN_PRODUCTS = 6
    private const val DELIVERY_MIN_PIECES = 150.0

    data class Row(
        val code: String, val name: String,
        val prev: Double, val sold: Double, val buyback: Double, val delivered: Double,
        val expected: Double, val actual: Double, val diff: Double,
        val value: Double?, val credited: Double,
    )

    data class Result(
        val prevDate: LocalDate, val currDate: LocalDate,
        val coveredDays: List<LocalDate>, val missingDays: List<LocalDate>,
        val checked: Int, val drops: List<Row>, val rises: List<Row>,
        val delivery: Boolean, val deliveredTotal: Double,
    ) {
        val balanced get() = drops.isEmpty() && rises.isEmpty()
        val dropPieces get() = drops.sumOf { it.diff }
        val dropValue get() = drops.sumOf { it.value ?: 0.0 }
        val risePieces get() = rises.sumOf { it.diff }
        val riseValue get() = rises.sumOf { it.value ?: 0.0 }
    }

    private fun near(a: Double) = abs(a) < 0.0001

    /** Compares the import on [prev] with the import on [curr], using everything that happened in between. */
    fun check(repo: Db, prev: LocalDate, curr: LocalDate, allImportDates: List<LocalDate>): Result {
        val before = repo.stock(prev).associateBy { it.code.uppercase() }
        val after = repo.stock(curr).associateBy { it.code.uppercase() }

        val sold = HashMap<String, Double>()
        val buyback = HashMap<String, Double>()
        val credit = HashMap<String, Double>()
        repo.linesBetween(prev.plusDays(1), curr).forEach { (_, l) ->
            val u = l.code.uppercase()
            when {
                !l.isReturn -> sold[u] = (sold[u] ?: 0.0) + l.qty
                l.isBuyback -> buyback[u] = (buyback[u] ?: 0.0) + l.qty
                else -> credit[u] = (credit[u] ?: 0.0) + l.qty
            }
        }
        // Delivered: reload on every imported day after [prev] up to and including [curr].
        val covered = allImportDates.filter { it.isAfter(prev) && !it.isAfter(curr) }.sorted()
        val delivered = HashMap<String, Double>()
        covered.forEach { d -> repo.stock(d).forEach { s -> if (s.reload > 0) delivered[s.code.uppercase()] = (delivered[s.code.uppercase()] ?: 0.0) + s.reload } }

        val names = HashMap<String, String>()
        (before.values + after.values).forEach { names[it.code.uppercase()] = it.name }
        val codes = (before.keys + after.keys + sold.keys + buyback.keys + delivered.keys).toSortedSet()

        val drops = ArrayList<Row>()
        val rises = ArrayList<Row>()
        for (u in codes) {
            val p = before[u]?.onHand ?: 0.0
            val q = after[u]?.onHand ?: 0.0
            val s = sold[u] ?: 0.0
            val b = buyback[u] ?: 0.0
            val dl = delivered[u] ?: 0.0
            val expected = p - s + b + dl
            val diff = q - expected
            if (near(diff)) continue
            val rate = repo.rateFor(u, curr.toString())?.marketRate?.takeIf { it > 0 }
            val row = Row(
                u, names[u] ?: repo.productName(u), p, s, b, dl, expected, q, diff,
                rate?.let { ((abs(diff) * it) * 100).roundToLong() / 100.0 }, credit[u] ?: 0.0,
            )
            if (diff < 0) drops += row else rises += row
        }
        val risePieces = rises.sumOf { it.diff }
        val delivery = rises.size >= DELIVERY_MIN_PRODUCTS && risePieces >= DELIVERY_MIN_PIECES

        // Only a day the route actually runs counts as a missing import (weekdays learned from past imports).
        val runDays = allImportDates.map { it.dayOfWeek }.toSet()
        val missing = ArrayList<LocalDate>()
        var d = prev.plusDays(1)
        while (!d.isAfter(curr)) {
            if (d !in covered && d.dayOfWeek in runDays) missing += d
            d = d.plusDays(1)
        }

        return Result(
            prev, curr, covered, missing, codes.size,
            drops.sortedWith(compareBy({ it.value == null }, { -(it.value ?: 0.0) }, { it.diff })),
            rises.sortedByDescending { it.diff },
            delivery, delivered.values.sum(),
        )
    }
}
