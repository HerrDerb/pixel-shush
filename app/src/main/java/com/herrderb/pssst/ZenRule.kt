package com.herrderb.pssst

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.util.Log

/**
 * Our own Do Not Disturb rule, like Digital Wellbeing's "Flip to Shhh" rule.
 * Switching only this rule leaves DND that the user turned on by hand alone.
 */
class ZenRule(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)
    private val prefs = Prefs(context)
    private val conditionId: Uri = Uri.parse("condition://${context.packageName}/facedown")

    fun hasAccess(): Boolean = nm.isNotificationPolicyAccessGranted

    fun setActive(active: Boolean) {
        val id = ruleId() ?: run {
            Log.w(TAG, "No Do Not Disturb access; cannot switch rule")
            return
        }
        val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
        val summary = context.getString(R.string.zen_condition_summary)
        val condition = if (Build.VERSION.SDK_INT >= 35) {
            Condition(conditionId, summary, state, Condition.SOURCE_CONTEXT)
        } else {
            Condition(conditionId, summary, state)
        }
        runCatching { nm.setAutomaticZenRuleState(id, condition) }
            .onFailure { Log.w(TAG, "Switching DND rule failed", it) }
    }

    fun remove() {
        val id = prefs.ruleId ?: return
        if (hasAccess()) runCatching { nm.removeAutomaticZenRule(id) }
        prefs.ruleId = null
    }

    private fun ruleId(): String? {
        if (!hasAccess()) return null
        prefs.ruleId?.let { id -> if (nm.getAutomaticZenRule(id) != null) return id }

        val name = context.getString(R.string.zen_rule_name)
        val settings = ComponentName(context, MainActivity::class.java)
        val rule = if (Build.VERSION.SDK_INT >= 35) {
            AutomaticZenRule.Builder(name, conditionId)
                .setConfigurationActivity(settings)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setIconResId(R.drawable.ic_pssst)
                .setTriggerDescription(context.getString(R.string.zen_rule_trigger))
                .build()
        } else {
            AutomaticZenRule(
                name, null, settings, conditionId, null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true,
            )
        }
        return nm.addAutomaticZenRule(rule).also { prefs.ruleId = it }
    }

    private companion object {
        const val TAG = "Pssst"
    }
}
