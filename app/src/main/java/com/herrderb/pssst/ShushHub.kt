package com.herrderb.pssst

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.location.ContextHubClient
import android.hardware.location.ContextHubClientCallback
import android.hardware.location.ContextHubInfo
import android.hardware.location.ContextHubManager
import android.hardware.location.ContextHubTransaction
import android.hardware.location.NanoAppMessage
import android.os.Handler
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Face-down detection by the Pixel "shush" nanoapp on the sensor hub, like Flip to Shhh itself.
 * The hub watches accelerometer and proximity at low power and wakes the phone only for an event,
 * so Pssst needs no sensors of its own. Talking to the hub needs ACCESS_CONTEXT_HUB, which only
 * privileged apps get: Pssst must be installed as a system app through the Magisk module.
 *
 * All calls and [onFaceDown] run on [handler]'s thread.
 */
class ShushHub(
    private val manager: ContextHubManager,
    private val hub: ContextHubInfo,
    private val handler: Handler,
    private val executor: Executor,
    private val onFaceDown: (Boolean) -> Unit,
) {
    private var client: ContextHubClient? = null
    private var screenOn = true
    private val sendScreenOff = Runnable { send(ShushProtocol.MSG_SCREEN, ShushProtocol.screen(false)) }

    inner class Callback : ContextHubClientCallback() {
        override fun onMessageFromNanoApp(client: ContextHubClient, message: NanoAppMessage) {
            if (message.nanoAppId != ShushProtocol.NANOAPP_ID) return
            when (message.messageType) {
                ShushProtocol.MSG_FACE_DOWN -> onFaceDown(true)
                ShushProtocol.MSG_FACE_UP -> onFaceDown(false)
                else -> Log.d(TAG, "Nanoapp message type ${message.messageType}")
            }
        }

        // The nanoapp lost its config: start it again, like GMS does.
        override fun onHubReset(client: ContextHubClient) = restart("hub reset")

        override fun onNanoAppLoaded(client: ContextHubClient, nanoAppId: Long) {
            if (nanoAppId == ShushProtocol.NANOAPP_ID) restart("nanoapp loaded")
        }
    }

    fun start(screenOn: Boolean): Boolean {
        this.screenOn = screenOn
        client = runCatching { manager.createClient(hub, Callback(), executor) }
            .onFailure { Log.w(TAG, "Creating sensor hub client failed", it) }
            .getOrNull() ?: return false
        return send(ShushProtocol.MSG_START, ShushProtocol.start(screenOn))
    }

    /** GMS reports screen on at once and screen off a little later. */
    fun setScreenOn(on: Boolean) {
        screenOn = on
        handler.removeCallbacks(sendScreenOff)
        if (on) {
            send(ShushProtocol.MSG_SCREEN, ShushProtocol.screen(true))
        } else {
            handler.postDelayed(sendScreenOff, ShushProtocol.SCREEN_OFF_DELAY_MS)
        }
    }

    fun stop() {
        handler.removeCallbacks(sendScreenOff)
        send(ShushProtocol.MSG_STOP, ByteArray(0))
        runCatching { client?.close() }
        client = null
    }

    private fun restart(reason: String) {
        if (client == null) return
        Log.i(TAG, "Restarting nanoapp ($reason)")
        send(ShushProtocol.MSG_START, ShushProtocol.start(screenOn))
    }

    private fun send(type: Int, body: ByteArray): Boolean {
        val c = client ?: return false
        val message = NanoAppMessage.createMessageToNanoApp(ShushProtocol.NANOAPP_ID, type, body)
        val result = runCatching { c.sendMessageToNanoApp(message) }.getOrElse { -1 }
        if (result != ContextHubTransaction.RESULT_SUCCESS) Log.w(TAG, "Sending message $type to nanoapp failed: $result")
        return result == ContextHubTransaction.RESULT_SUCCESS
    }

    companion object {
        private const val TAG = "Pssst"
        private const val PERMISSION = "android.permission.ACCESS_CONTEXT_HUB"

        /** True when Pssst runs as a privileged app with access to the sensor hub. */
        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        /**
         * Connects when the shush nanoapp is loaded, enabled and new enough, else null.
         * Blocks for up to 2 s, so call it off the main thread.
         */
        fun connect(
            context: Context,
            handler: Handler,
            executor: Executor,
            screenOn: Boolean,
            onFaceDown: (Boolean) -> Unit,
        ): ShushHub? {
            val (manager, hub) = findNanoapp(context) ?: return null
            val shush = ShushHub(manager, hub, handler, executor, onFaceDown)
            return if (shush.start(screenOn)) shush else null.also { shush.stop() }
        }

        /** Blocking check for the UI. */
        fun isAvailable(context: Context): Boolean = findNanoapp(context) != null

        private fun findNanoapp(context: Context): Pair<ContextHubManager, ContextHubInfo>? {
            if (!hasPermission(context)) return null
            return runCatching {
                val manager = context.getSystemService("contexthub") as? ContextHubManager ?: return null
                val hub = manager.contextHubs.firstOrNull() ?: return null
                val response = manager.queryNanoApps(hub).waitForResponse(2, TimeUnit.SECONDS)
                if (response.result != ContextHubTransaction.RESULT_SUCCESS) return null
                val app = response.contents.firstOrNull { it.nanoAppId == ShushProtocol.NANOAPP_ID }
                Log.i(TAG, "Shush nanoapp: ${app?.let { "version 0x%x, enabled %b".format(it.nanoAppVersion, it.isEnabled) } ?: "not loaded"}")
                if (app != null && app.isEnabled && app.nanoAppVersion >= ShushProtocol.MIN_VERSION) manager to hub else null
            }.onFailure { Log.w(TAG, "Querying sensor hub failed", it) }.getOrNull()
        }
    }
}
