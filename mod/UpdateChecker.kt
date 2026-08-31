package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val REPO = "klcb2010/rikkahub"

private val LATEST_PAGE_URLS = listOf(
    "https://github.com/$REPO/releases/latest",
    "https://ghfast.top/https://github.com/$REPO/releases/latest",
    "https://gh-proxy.com/https://github.com/$REPO/releases/latest",
)

private val APK_NAMES = listOf(
    "app-arm64-v8a-release.apk",
    "app-universal-release.apk",
    "app-x86_64-release.apk",
)

class UpdateChecker(
    client: OkHttpClient,
    appScope: AppScope,
) {
    private val http: OkHttpClient = client.newBuilder().apply {
        interceptors().removeAll { it.javaClass.simpleName.contains("AIRequest") }
        followRedirects(false)
        followSslRedirects(false)
        connectTimeout(15, TimeUnit.SECONDS)
        readTimeout(20, TimeUnit.SECONDS)
        writeTimeout(20, TimeUnit.SECONDS)
        callTimeout(25, TimeUnit.SECONDS)
    }.build()

    val updateState: StateFlow<UiState<UpdateInfo>> = checkUpdate().stateIn(
        scope = appScope,
        started = SharingStarted.Lazily,
        initialValue = UiState.Loading,
    )

    private fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(UiState.Success(data = fetchLatest()))
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchLatest(): UpdateInfo {
        val errors = mutableListOf<String>()
        for (url in LATEST_PAGE_URLS) {
            try {
                val response = http.newCall(
                    Request.Builder()
                        .url(url)
                        .get()
                        .addHeader(
                            "User-Agent",
                            "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                        )
                        .build()
                ).await()
                val location = response.header("Location").orEmpty()
                val tag = extractTag(location) ?: extractTag(response.body?.string().orEmpty())
                if (tag.isNullOrBlank()) {
                    errors += "${response.code} no-tag ${url.substringAfter("://").substringBefore("?")}"
                    continue
                }
                val version = tag.trim().removePrefix("v").removePrefix("V")
                val downloads = filterByAbi(
                    APK_NAMES.map { name ->
                        UpdateDownload(
                            name = name,
                            url = "https://github.com/$REPO/releases/download/$tag/$name",
                            size = "",
                        )
                    }
                )
                return UpdateInfo(
                    version = version,
                    publishedAt = "",
                    changelog = "RikkaHub $version",
                    downloads = downloads,
                )
            } catch (e: Exception) {
                errors += "${e.javaClass.simpleName}:${e.message}"
            }
        }
        throw Exception("Failed to fetch update info: ${errors.joinToString(" | ")}")
    }

    private fun extractTag(text: String): String? {
        val regex = Regex("""/releases/tag/(v?[\w.\-]+)""")
        return regex.find(text)?.groupValues?.get(1)
    }

    private fun filterByAbi(downloads: List<UpdateDownload>): List<UpdateDownload> {
        val abis = Build.SUPPORTED_ABIS.toSet()
        val matched = downloads.filter { d -> abis.any { abi -> d.name.contains(abi) } }
        return if (matched.isNotEmpty()) matched else downloads
    }

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                setTitle(download.name)
                setDescription("正在下载更新包...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                setMimeType("application/vnd.android.package-archive")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }.onFailure {
            Toast.makeText(context, "Failed to update", Toast.LENGTH_SHORT).show()
            context.openUrl(download.url)
        }
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        val withoutBuild = value.split("+").first()
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                if (i >= a.size) return -1
                if (i >= b.size) return 1
                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()
                val cmp = when {
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    aNum != null -> -1
                    bNum != null -> 1
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
