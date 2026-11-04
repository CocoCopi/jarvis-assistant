package com.jarvis.assistant.engine

import android.content.Context
import android.util.Log
import com.jarvis.assistant.bridge.DeviceBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object CodeUpdater {
    private const val TAG = "CodeUpdater"
    private const val MAX_SOURCE = 64L * 1024 * 1024
    private const val MAX_APK = 300L * 1024 * 1024

    data class Asset(val name: String, val url: String, val digest: String?)

    data class Release(
        val tag: String,
        val name: String,
        val publishedAt: String,
        val assets: List<Asset>,
    )

    fun checkForCode(context: Context, repo: String): String {
        if (repo.isBlank() || !repo.contains("/")) {
            return "No GitHub repo configured. Open Settings and set owner/repo."
        }
        return try {
            val release = latestRelease(repo, requireApk = false)
                ?: return "No releases found for $repo."
            val zip = release.assets.firstOrNull { it.name == "jarvis-src.zip" }
                ?: return "Release ${release.tag} has no jarvis-src.zip asset."
            if (release.tag == currentVersion(context) && currentDir(context) != null) {
                return "Code is already at ${release.tag}."
            }
            val shaAsset = release.assets.firstOrNull { it.name == "jarvis-src.sha256" }
            val bytes = httpGet(zip.url, "application/octet-stream", MAX_SOURCE)
            if (bytes.size > MAX_SOURCE) return "Source archive is too large."
            if (shaAsset != null) {
                val expected = String(httpGet(shaAsset.url, "text/plain", 64 * 1024)).trim()
                    .substringBefore(" ").lowercase()
                val actual = sha256(bytes)
                if (expected != actual) {
                    return "Checksum mismatch for ${release.tag}; refusing to install."
                }
            }
            val destination = File(context.filesDir, "code/" + safe(release.tag))
            if (destination.exists()) destination.deleteRecursively()
            destination.mkdirs()
            unzip(bytes, destination)
            if (!File(destination, "jarvis/agent.py").isFile ||
                !File(destination, "jarvis_android/engine.py").isFile
            ) {
                destination.deleteRecursively()
                return "Downloaded archive is missing Jarvis source files."
            }
            val codeRoot = File(context.filesDir, "code").apply { mkdirs() }
            File(codeRoot, "pending.json").writeText(
                JSONObject().apply {
                    put("dir", destination.absolutePath)
                    put("version", release.tag)
                    put("name", release.name)
                    put("published_at", release.publishedAt)
                }.toString(),
            )
            "Downloaded code ${release.tag}. Applying now."
        } catch (e: Throwable) {
            Log.e(TAG, "code update failed", e)
            "Code update failed: ${e.message}"
        }
    }

    fun installApkUpdate(context: Context, repo: String): String {
        if (repo.isBlank() || !repo.contains("/")) {
            return "No GitHub repo configured. Open Settings and set owner/repo."
        }
        return try {
            val release = latestRelease(repo, requireApk = true)
                ?: return "No release with an APK was found for $repo."
            val apk = release.assets
                .filter { it.name.endsWith(".apk", ignoreCase = true) }
                .sortedByDescending { it.name.contains("release", ignoreCase = true) }
                .firstOrNull()
                ?: return "Release ${release.tag} has no APK asset yet."
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val destination = File(directory, safe(apk.name))
            if (!destination.exists() || destination.length() == 0L) {
                val bytes = httpGet(apk.url, "application/octet-stream", MAX_APK)
                if (bytes.size > MAX_APK) return "APK is too large."
                if (!apk.digest.isNullOrBlank()) {
                    val expected = apk.digest.substringAfter("sha256:", "").lowercase()
                    if (expected.isNotEmpty() && expected != sha256(bytes)) {
                        return "APK checksum mismatch; refusing to install."
                    }
                }
                destination.writeBytes(bytes)
            }
            DeviceBridge.installApk(destination.absolutePath)
        } catch (e: Throwable) {
            Log.e(TAG, "apk update failed", e)
            "APK update failed: ${e.message}"
        }
    }

    fun reset(context: Context): String {
        val code = File(context.filesDir, "code")
        code.deleteRecursively()
        return "Downloaded code removed. Restart Jarvis to use the bundled snapshot."
    }

    private fun currentDir(context: Context): String? {
        return try {
            val file = File(context.filesDir, "code/current.json")
            if (!file.isFile) return null
            val dir = JSONObject(file.readText()).optString("dir", "")
            if (dir.isNotBlank() && File(dir).isDirectory) dir else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun currentVersion(context: Context): String? {
        return try {
            val file = File(context.filesDir, "code/current.json")
            if (!file.isFile) return null
            JSONObject(file.readText()).optString("version", "")
        } catch (e: Throwable) {
            null
        }
    }

    private fun latestRelease(repo: String, requireApk: Boolean): Release? {
        val bytes = httpGet(
            "https://api.github.com/repos/$repo/releases?per_page=20",
            "application/vnd.github+json",
            4 * 1024 * 1024,
        )
        val array = JSONArray(String(bytes))
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val assets = mutableListOf<Asset>()
            val assetArray = item.optJSONArray("assets") ?: JSONArray()
            for (j in 0 until assetArray.length()) {
                val asset = assetArray.optJSONObject(j) ?: continue
                assets.add(
                    Asset(
                        name = asset.optString("name"),
                        url = asset.optString("browser_download_url"),
                        digest = asset.optString("digest").takeIf { it.isNotBlank() },
                    ),
                )
            }
            val hasSource = assets.any { it.name == "jarvis-src.zip" }
            val hasApk = assets.any { it.name.endsWith(".apk", true) }
            if (hasSource && (!requireApk || hasApk)) {
                return release(item, assets)
            }
        }
        return null
    }

    private fun release(item: JSONObject, assets: List<Asset>): Release {
        return Release(
            tag = item.optString("tag_name", "unknown"),
            name = item.optString("name", ""),
            publishedAt = item.optString("published_at", ""),
            assets = assets,
        )
    }

    private fun httpGet(url: String, accept: String, limit: Long): ByteArray {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 90_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Jarvis-Android")
                setRequestProperty("Accept", accept)
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location")
                    connection.disconnect()
                    current = URL(current, location)
                    redirects += 1
                    if (redirects > 8) throw IOException("Too many redirects")
                    continue
                }
                if (code !in 200..299) {
                    val body = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    throw IOException("HTTP $code ${body.orEmpty().take(300)}")
                }
                return connection.inputStream.use { readLimited(it, limit) }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readLimited(stream: java.io.InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("Download exceeded $limit bytes")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun unzip(bytes: ByteArray, destination: File) {
        val root = destination.canonicalPath + File.separator
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name.startsWith("/") || name.contains("..")) {
                    zip.closeEntry()
                    continue
                }
                val target = File(destination, name)
                if (!target.canonicalPath.startsWith(root)) {
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun safe(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    }
}
