package com.herrderb.pssst

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("pssst", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit { putBoolean("enabled", value) }

    var ruleId: String?
        get() = prefs.getString("rule_id", null)
        set(value) = prefs.edit { putString("rule_id", value) }

    var firstFlipShown: Boolean
        get() = prefs.getBoolean("first_flip_shown", false)
        set(value) = prefs.edit { putBoolean("first_flip_shown", value) }
}
