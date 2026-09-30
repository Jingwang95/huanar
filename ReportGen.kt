package com.huanar.le

/** 某个分类的汇总 */
data class CatSum(val cat: Cat, val amount: Double, val count: Int)

/** 一个月的报表 */
data class Report(
    val ym: String,
    val total: Double,
    val count: Int,
    val avgPerDay: Double,
    val maxOne: Double,
    val income: Double,
    val daily: DoubleArray,          // 下标 1..月末
    val cats: List<CatSum>,
    val budget: Double,
    val daysLeft: Int
) {
    val budgetLeft: Double get() = budget - total
    val overBudget: Boolean get() = budget > 0 && total > budget
    val perDayLeft: Double get() = if (daysLeft > 0) budgetLeft / daysLeft else 0.0
}

object ReportGen {

    fun build(store: Store, ym: String): Report {
        val list = store.month(ym)
        val exp = list.filter { it.type == T_EXPENSE }
        val inc = list.filter { it.type == T_INCOME }

        val total = exp.sumOf { it.amount }
        val dim = D.daysInMonth(ym)
        val isThisMonth = ym == D.thisMonth()
        val denom = if (isThisMonth) {
            java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        } else dim

        val daily = DoubleArray(dim + 1)
        exp.forEach {
            val d = it.date.substring(8, 10).toIntOrNull() ?: return@forEach
            if (d in 1..dim) daily[d] += it.amount
        }

        val catList = store.cats()
        val agg = LinkedHashMap<String, Double>()
        val cnt = LinkedHashMap<String, Int>()
        exp.forEach {
            agg[it.catId] = (agg[it.catId] ?: 0.0) + it.amount
            cnt[it.catId] = (cnt[it.catId] ?: 0) + 1
        }
        val cats = agg.entries
            .map { CatSum(Cats.byId(catList, it.key), it.value, cnt[it.key] ?: 0) }
            .sortedByDescending { it.amount }

        val daysLeft = if (isThisMonth) {
            dim - java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        } else 0

        return Report(
            ym = ym,
            total = total,
            count = exp.size,
            avgPerDay = if (denom > 0) total / denom else 0.0,
            maxOne = exp.maxOfOrNull { it.amount } ?: 0.0,
            income = inc.sumOf { it.amount },
            daily = daily,
            cats = cats,
            budget = store.budget(ym),
            daysLeft = daysLeft
        )
    }
}
