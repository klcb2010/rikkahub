package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * 直接拉取 GitHub Releases API 的最近发布，替代原作者的 updates.rikka-ai.com
 */
private const val API_URL = "https://api.github.com/repos/klcb2010/rikkahub/releases/latest"

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    val response = client.newCall(
                        Request.Builder()
                            .url(API_URL)
                            .get()
                            .addHeader("Accept", "application/vnd.github+json")
                            .addHeader(
                                "User-Agent",
                                "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                            )
                            .build()
                    ).await()
                    if (response.isSuccessful) {
                        parseGithubRelease(response.body.string())
                    } else {
                        throw Exception("Failed to fetch update info (HTTP ${response.code})")
                    }
                } catch (e: Exception) {
                    throw Exception("Failed to fetch update info", e)
                }
            )
        )
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    /**
     * 解析 GitHub Releases API 响应为 UpdateInfo
     * GitHub 响应结构: https://docs.github.com/rest/releases/releases#get-the-latest-release
     */
    private fun parseGithubRelease(body: String): UpdateInfo {
        val root = json.parseToJsonElement(body).jsonObject
        val allAssets = root["assets"]?.jsonArray ?: emptyList()
        return UpdateInfo(
            version = root["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: "0.0.0",
            publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull ?: "",
            changelog = root["body"]?.jsonPrimitive?.contentOrNull ?: "",
            downloads = filterByAbi(allAssets).mapNotNull { asset ->
                val obj = asset.jsonObject
                val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: url.substringAfterLast('/')
                UpdateDownload(
                    name = name,
                    size = formatSize((obj["size"]?.jsonPrimitive?.contentOrNull ?: "0").toLongOrNull() ?: 0L),
                    url = url
                )
            }
        )
    }

    /**
     * 按设备 ABI 过滤 APK，避免弹窗显示 3 个装不了的包
     * 匹配不到（如老设备）则全部显示兜底
     */
    private fun filterByAbi(assets: List<kotlinx.serialization.json.JsonElement>): List<kotlinx.serialization.json.JsonElement> {
        val supportedAbis = Build.SUPPORTED_ABIS.toSet()
        val matched = assets.filter { asset ->
            val name = asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@filter false
            supportedAbis.any { name.contains(it) }
        }
        return if (matched.isNotEmpty()) matched else assets
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "未知大小"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                // 设置下载时通知栏的标题和描述
                setTitle(download.name)
                setDescription("正在下载更新包...")
                // 下载完成后通知栏可见
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 允许在移动网络和WiFi下下载
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                // 设置文件保存路径
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                // 允许下载的文件类型
                setMimeType("application/vnd.android.package-archive")
            }
            // 获取系统的DownloadManager
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            // 你可以保存返回的downloadId到本地，以便后续查询下载进度或状态
        }.onFailure {
            Toast.makeText(context, "Failed to update", Toast.LENGTH_SHORT).show()
            context.openUrl(download.url) // 跳转到下载页面
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

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
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

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
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
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
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

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))

