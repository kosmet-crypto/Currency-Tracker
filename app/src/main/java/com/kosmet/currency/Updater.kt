package com.kosmet.currency

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a newer APK in the latest GitHub release and hands it to the system installer,
 * which asks the user to confirm, like any other app update.
 */
object Updater {
    private const val REPO = "kosmet-crypto/Currency-Tracker"

    data class Release(val version: Int, val apkUrl: String)

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Null when up to date. Call from a background thread. */
    fun findUpdate(): Release? {
        val release = JSONObject(read("https://api.github.com/repos/$REPO/releases/latest"))
        val version = release.getString("tag_name").removePrefix("v").toIntOrNull() ?: return null
        if (version <= BuildConfig.VERSION_CODE) return null
        val assets = release.getJSONArray("assets")
        val apkUrl = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?.getString("browser_download_url") ?: return null
        return Release(version, apkUrl)
    }

    /** Downloads and opens the system install prompt. Call from a background thread. */
    fun install(ctx: Context, release: Release) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                val conn = connect(release.apkUrl)
                try {
                    session.openWrite("update.apk", 0, -1).use { out ->
                        conn.inputStream.use { it.copyTo(out) }
                        session.fsync(out)
                    }
                } finally {
                    conn.disconnect()
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val result = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, UpdateReceiver::class.java), flags)
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    private fun read(url: String): String {
        val conn = connect(url)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun connect(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "CurrencyTracker")
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        return conn
    }
}

/** The installer reports back here; it asks for the user's confirmation through this intent. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        } ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(confirm)
    }
}
