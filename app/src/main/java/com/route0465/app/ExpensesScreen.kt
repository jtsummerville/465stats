package com.route0465.app

import androidx.compose.runtime.Composable

/** The Expenses tab. Recurring (weekly, taken out of pay) and one-time (a log) expenses will live here. */
@Composable
fun ExpensesScreen() {
    ScreenColumn {
        Panel {
            H2("Expenses")
            Muted("Recurring and one-time expenses will go here.", 15)
        }
    }
}
