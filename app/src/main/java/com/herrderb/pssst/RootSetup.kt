package com.herrderb.pssst

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional setup for rooted Pixels: a Magisk module installs Pssst as a privileged system app,
 * which lets it use the shush nanoapp on the sensor hub ([ShushHub]) instead of its own sensors.
 *
 * Every function blocks (su waits for the Magisk prompt), so call them off the main thread.
 */
object RootSetup {

    data class Status(
        /** su works (Magisk granted Pssst root). */
        val rooted: Boolean,
        val magisk: Boolean,
        /** Shush nanoapp loaded on the sensor hub; null when unknown (no root, no privileges). */
        val nanoapp: Boolean?,
        /** Module files are in place (active after the next reboot if not [privileged] yet). */
        val moduleInstalled: Boolean,
        /** Pssst runs as a privileged app and may talk to the sensor hub. */
        val privileged: Boolean,
        /** Module marked for removal; Magisk deletes it on the next boot. */
        val removalPending: Boolean,
    ) {
        /** Worth showing the sensor hub section at all. */
        val relevant get() = rooted || magisk || privileged || moduleInstalled
        val usingHub get() = privileged && nanoapp == true
        val rebootNeeded get() = (moduleInstalled && !privileged) || (removalPending && privileged)
    }

    private const val TAG = "Pssst"
    private const val MODULE_DIR = "/data/adb/modules/pssst"
    private const val MAGISK_PACKAGE = "com.topjohnwu.magisk"

    fun check(context: Context): Status {
        val privileged = ShushHub.hasPermission(context)
        val rooted = hasSuBinary() && su("id -u")?.trim() == "0"
        val magisk = isInstalled(context, MAGISK_PACKAGE) ||
            (rooted && su("magisk -V")?.trim()?.toIntOrNull() != null)
        val nanoapp = when {
            privileged -> ShushHub.isAvailable(context)
            rooted -> su("dumpsys contexthub")?.let { NANOAPP_HEX in it.lowercase() }
            else -> null
        }
        val module = if (rooted) {
            su("test -f $MODULE_DIR/module.prop && { test -f $MODULE_DIR/remove && echo removing || echo installed; } || echo none")?.trim()
        } else {
            null
        }
        return Status(rooted, magisk, nanoapp, module == "installed", privileged, module == "removing").also { Log.i(TAG, "Root setup: $it") }
    }

    /** Writes the module: this APK as a privileged app plus the permission allowlist it needs. */
    fun installModule(context: Context): Boolean {
        val apk = context.applicationInfo.sourceDir
        val pkg = context.packageName
        val version = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "0"
        val script = """
            set -e
            M=$MODULE_DIR
            rm -rf "${'$'}M"
            mkdir -p "${'$'}M/system/priv-app/Pssst" "${'$'}M/system/etc/permissions"
            cp "$apk" "${'$'}M/system/priv-app/Pssst/Pssst.apk"
            cat > "${'$'}M/module.prop" <<'EOF'
            id=pssst
            name=Pssst sensor hub access
            version=$version
            versionCode=1
            author=Pssst
            description=Installs Pssst as a privileged app so it can use the Pixel shush nanoapp on the sensor hub.
            EOF
            cat > "${'$'}M/system/etc/permissions/privapp-permissions-$pkg.xml" <<'EOF'
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <privapp-permissions package="$pkg">
                    <permission name="android.permission.ACCESS_CONTEXT_HUB"/>
                    <permission name="android.permission.LOCATION_HARDWARE"/>
                </privapp-permissions>
            </permissions>
            EOF
            chown -R 0:0 "${'$'}M"
            find "${'$'}M" -type d -exec chmod 755 {} +
            find "${'$'}M" -type f -exec chmod 644 {} +
            chcon -R u:object_r:system_file:s0 "${'$'}M/system"
            echo ok
        """.trimIndent()
        return su(script)?.trim()?.endsWith("ok") == true
    }

    /** Magisk deletes a module marked for removal on the next boot. */
    fun removeModule(): Boolean = su("touch $MODULE_DIR/remove && echo ok")?.trim() == "ok"

    fun reboot() {
        su("svc power reboot || reboot")
    }

    private val NANOAPP_HEX = "%x".format(ShushProtocol.NANOAPP_ID)

    /** Looks for su without running it, so phones without root never see a prompt. */
    private fun hasSuBinary(): Boolean {
        val path = System.getenv("PATH")?.split(':').orEmpty()
        return (path + listOf("/system/bin", "/system/xbin", "/sbin", "/debug_ramdisk"))
            .any { runCatching { File(it, "su").exists() }.getOrDefault(false) }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Runs [script] as root and returns its output, or null when su fails or is denied. */
    private fun su(script: String): String? = runCatching {
        val process = ProcessBuilder("su").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(script + "\nexit\n") }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        if (process.exitValue() != 0) {
            Log.w(TAG, "su failed (${process.exitValue()}): $output")
            return null
        }
        output
    }.onFailure { Log.d(TAG, "su unavailable: $it") }.getOrNull()
}
