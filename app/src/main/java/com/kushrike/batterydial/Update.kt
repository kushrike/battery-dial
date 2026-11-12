package com.kushrike.batterydial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL

/**
 * The app's only network code (CI enforces that): ask GitHub for the latest release, and download its APK when
 * the user taps Update. Android only installs it if it's signed with the same key as the installed app.
 */
data class Release(val version: String, val apk: String)

object Updates {
    private const val REPO = "kushrike/battery-dial"

    /** The latest GitHub release if it's newer than this build. Blocking; throws on network errors. */
    fun check(ctx: Context): Release? {
        val json = Json.parseToJsonElement(URL("https://api.github.com/repos/$REPO/releases/latest").readText()).jsonObject
        val version = json.getValue("tag_name").jsonPrimitive.content.removePrefix("v")
        val apk = json.getValue("assets").jsonArray
            .map { it.jsonObject.getValue("browser_download_url").jsonPrimitive.content }
            .firstOrNull { it.startsWith("https://github.com/$REPO/releases/download/") && it.endsWith(".apk") }
            ?: return null
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        return Release(version, apk).takeIf { isNewer(version, current) }
    }

    /** Streams the APK into a system install session. Android then asks the user to confirm. Blocking. */
    fun install(ctx: Context, r: Release) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(ctx.packageName) }
        val session = installer.openSession(installer.createSession(params))
        try {
            URL(r.apk).openStream().use { input ->
                session.openWrite("update.apk", 0, -1).use { out -> input.copyTo(out); session.fsync(out) }
            }
            // Mutable so the installer can add its status extras; explicit, so nothing else can receive it.
            val status = Intent(ctx, InstallReceiver::class.java)
            session.commit(PendingIntent.getBroadcast(ctx, 0, status, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
            session.close()
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }
}

/** True if version [a] is newer than [b], comparing dotted numbers ("1.0.10" > "1.0.9"). */
fun isNewer(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** Install session status: show Android's confirm screen, or say why the install failed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            // Toasts show two lines, so keep it short.
            PackageInstaller.STATUS_FAILURE_CONFLICT -> toast(ctx, "Update failed: different signing key. Reinstall from GitHub.")
            else -> toast(ctx, "Update failed: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"))
        }
    }

    private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
}
