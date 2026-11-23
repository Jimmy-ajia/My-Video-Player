package com.yourcompany.videoplayer

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

internal object NetworkMountRepository {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val DOWNLOAD_DIRECTORY = "EncryptVideo"
    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/>" +
            "<d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
    private val videoExtensions = setOf(
        "mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm", "ts", "mts",
        "m2ts", "flv", "avi", "wmv", "asf", "mpg", "mpeg", "vob", "oxu"
    )
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val durationCache = ConcurrentHashMap<String, Long>()

    private fun durationKey(mount: NetworkMount, entry: NetworkMountEntry): String =
        "${mount.id}|${entry.relativePath}|${entry.size}|${entry.modifiedAt}"

    /** Previously probed duration in milliseconds, or null when it has not been read yet. */
    fun cachedDurationMillis(mount: NetworkMount, entry: NetworkMountEntry): Long? =
        if (entry.isDirectory) 0L else durationCache[durationKey(mount, entry)]

    /** Reads just enough metadata from the share to determine the video duration. */
    fun probeDurationMillis(mount: NetworkMount, entry: NetworkMountEntry): Long {
        if (entry.isDirectory) return 0L
        val key = durationKey(mount, entry)
        durationCache[key]?.let { return it }
        val source = try {
            openMediaDataSource(mount, entry)
        } catch (_: Exception) {
            return 0L
        }
        var retriever: MediaMetadataRetriever? = null
        val duration = try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {
            }
            try {
                source.close()
            } catch (_: Exception) {
            }
        }
        durationCache[key] = duration
        return duration
    }

    private fun openMediaDataSource(
        mount: NetworkMount,
        entry: NetworkMountEntry
    ): MediaDataSource = when (mount.type) {
        NetworkMountType.SMB -> SmbMediaDataSource(
            openRandomAccessFile(mount, entry.relativePath)
        )
        NetworkMountType.WEBDAV -> WebDavMediaDataSource(
            client = httpClient,
            length = entry.size,
            requestBuilder = {
                webDavRequest(
                    mount,
                    webDavUrl(mount, entry.relativePath, directory = false),
                    "GET",
                    null
                ).newBuilder()
            }
        )
    }

    fun normalizeServerUrl(type: NetworkMountType, value: String): String {
        val trimmed = value.trim()
        // For SMB, accept bare IP/host and wrap with smb:// scheme
        val input = if (type == NetworkMountType.SMB && !trimmed.contains("://")) {
            "smb://$trimmed"
        } else {
            trimmed
        }
        val uri = try {
            URI(input)
        } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException("网络共享地址格式无效。")
        }
        val validScheme = when (type) {
            NetworkMountType.SMB -> uri.scheme.equals("smb", ignoreCase = true)
            NetworkMountType.WEBDAV ->
                uri.scheme.equals("https", ignoreCase = true)
        }
        if (!validScheme || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null ||
            uri.path.orEmpty().split('/').any { it == "." || it == ".." }
        ) {
            throw IllegalArgumentException(
                if (type == NetworkMountType.SMB) {
                    "请输入有效的服务器地址，不要把用户名或密码放在地址中。"
                } else {
                    "请输入有效的 https:// WebDAV 地址，不要把用户名或密码放在地址中。"
                }
            )
        }
        // SMB without share path: bare IP defaults to root, but full URIs and dot-hostnames require explicit path.
        return when (type) {
            NetworkMountType.SMB -> {
                val host = uri.host.orEmpty()
                val isIpv4 = java.util.regex.Pattern.compile("\\A\\d+.\\d+.\\d+.\\d+\\z").matcher(host).matches() || java.util.regex.Pattern.compile("^(\\d+.){3}\\d+$").matcher(host).matches()
                val hasDotHostname = host.contains('.') && !isIpv4
                val path = uri.rawPath.orEmpty()
                val isFullUriWithTrailingSlash = path == "/" && trimmed.contains("://")

                if (isFullUriWithTrailingSlash || hasDotHostname) {
                    throw IllegalArgumentException(
                        "SMB 地址需要包含共享目录名称。"
                    )
                }
                val sharePath = path.trim('/')
                "smb://${host.lowercase()}/" +
                    if (sharePath.isEmpty()) "" else "$sharePath/"
            }
            NetworkMountType.WEBDAV -> trimmed.trimEnd('/') + "/"
        }
    }

    fun list(mount: NetworkMount, relativePath: String): List<NetworkMountEntry> =
        when (mount.type) {
            NetworkMountType.SMB -> listSmb(mount, relativePath)
            NetworkMountType.WEBDAV -> listWebDav(mount, relativePath)
        }

    fun openRandomAccessFile(mount: NetworkMount, relativePath: String): SmbRandomAccessFile {
        if (mount.type != NetworkMountType.SMB) {
            throw IOException("Random-access streaming is only supported for SMB shares.")
        }
        return smbFile(mount, relativePath, smbContext(mount)).openRandomAccess("r")
    }

    fun download(
        context: Context,
        mount: NetworkMount,
        entry: NetworkMountEntry,
        onProgress: (Int) -> Unit = {}
    ): File {
        if (entry.isDirectory) throw IOException("Choose a video file, not a directory.")
        if (entry.name.substringAfterLast('.', "").lowercase() !in videoExtensions) {
            throw IOException("This file type is not supported.")
        }
        val downloadDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOAD_DIRECTORY
        )
        if (!downloadDirectory.isDirectory && !downloadDirectory.mkdirs()) {
            throw IOException("Unable to create the download directory.")
        }

        val cacheKey = listOf(mount.id, entry.relativePath, entry.size, entry.modifiedAt)
            .joinToString("|")
        val filename = sha256(cacheKey) + "." +
            entry.name.substringAfterLast('.', "video").lowercase()
        val target = File(downloadDirectory, filename)
        if (entry.size > 0 && entry.modifiedAt > 0 && target.isFile &&
            target.length() == entry.size
        ) {
            return target
        }

        val temporary = File(downloadDirectory, ".$filename.${UUID.randomUUID()}.tmp")
        try {
            openInputStream(mount, entry).use { input ->
                BufferedOutputStream(temporary.outputStream()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        if (entry.size > 0) {
                            onProgress((total * 100 / entry.size).toInt().coerceIn(0, 100))
                        }
                    }
                    if (entry.size > 0 && total != entry.size) {
                        throw IOException("The remote file changed or the download was incomplete.")
                    }
                }
            }
            moveIntoPlace(temporary, target)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun listSmb(mount: NetworkMount, relativePath: String): List<NetworkMountEntry> {
        val context = smbContext(mount)
        val directory = smbFile(mount, relativePath, context)
        if (!directory.exists() || !directory.isDirectory) {
            throw IOException("The SMB directory is unavailable.")
        }
        return directory.listFiles()
            .mapNotNull { child ->
                val name = child.name.trimEnd('/')
                if (name.isBlank() || name == "." || name == "..") return@mapNotNull null
                val childPath = listOf(relativePath.trim('/'), name)
                    .filter(String::isNotEmpty)
                    .joinToString("/")
                val isDirectory = child.isDirectory
                if (!isDirectory &&
                    name.substringAfterLast('.', "").lowercase() !in videoExtensions
                ) {
                    return@mapNotNull null
                }
                NetworkMountEntry(
                    name = name,
                    relativePath = childPath,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else child.length(),
                    modifiedAt = child.lastModified()
                )
            }
            .sortedWith(compareBy<NetworkMountEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    private fun listWebDav(mount: NetworkMount, relativePath: String): List<NetworkMountEntry> {
        val request = webDavRequest(
            mount,
            webDavUrl(mount, relativePath, directory = true),
            "PROPFIND",
            PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        ).newBuilder().header("Depth", "1").build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("WebDAV listing failed (HTTP ${response.code}).")
                }
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                    setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
                }
                val body = response.body ?: throw IOException("WebDAV returned an empty directory listing.")
                val document = body.byteStream().use { factory.newDocumentBuilder().parse(it) }
                val responses = document.getElementsByTagNameNS("DAV:", "response")
                return (0 until responses.length)
                    .mapNotNull { index ->
                        val response = responses.item(index) as? Element ?: return@mapNotNull null
                        val href = response.getElementsByTagNameNS("DAV:", "href")
                            .item(0)?.textContent?.trim() ?: return@mapNotNull null
                        val childPath = webDavRelativePath(mount, href)
                        if (childPath == relativePath.trimEnd('/')) return@mapNotNull null
                        val type = response.getElementsByTagNameNS("DAV:", "collection")
                        val isDirectory = type.length > 0
                        val name = childPath.substringAfterLast('/')
                        if (name.isBlank() || (!isDirectory &&
                                name.substringAfterLast('.', "").lowercase() !in videoExtensions)
                        ) {
                            return@mapNotNull null
                        }
                        val size = response.getElementsByTagNameNS("DAV:", "getcontentlength")
                            .item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
                        val modifiedAt = response.getElementsByTagNameNS("DAV:", "getlastmodified")
                            .item(0)?.textContent?.trim()
                            ?.let(::parseHttpDate) ?: 0L
                        NetworkMountEntry(name, childPath, isDirectory, size, modifiedAt)
                    }
                    .sortedWith(
                        compareBy<NetworkMountEntry> { !it.isDirectory }
                            .thenBy { it.name.lowercase() }
                    )
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("WebDAV returned an invalid directory listing.", error)
        }
    }

    private fun webDavRelativePath(mount: NetworkMount, href: String): String {
        val root = URI(mount.serverUrl)
        val resolved = root.resolve(href)
        if (!root.scheme.equals(resolved.scheme, ignoreCase = true) ||
            !root.host.equals(resolved.host, ignoreCase = true) ||
            effectivePort(root) != effectivePort(resolved)
        ) {
            throw IOException("WebDAV returned an entry outside the configured server.")
        }
        val rootPath = root.path.trimEnd('/')
        val responsePath = Uri.decode(resolved.rawPath ?: "")
        if (responsePath.split('/').any { it == "." || it == ".." }) {
            throw IOException("WebDAV returned an unsafe entry path.")
        }
        if (responsePath == rootPath) return ""
        val rootPrefix = "$rootPath/"
        if (!responsePath.startsWith(rootPrefix)) {
            throw IOException("WebDAV returned an entry outside the configured directory.")
        }
        return responsePath.removePrefix(rootPrefix).trimEnd('/')
    }

    private fun effectivePort(uri: URI): Int =
        uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80

    private fun parseHttpDate(value: String): Long = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }

    private fun openInputStream(mount: NetworkMount, entry: NetworkMountEntry) =
        when (mount.type) {
            NetworkMountType.SMB -> smbFile(mount, entry.relativePath, smbContext(mount)).inputStream
            NetworkMountType.WEBDAV -> {
                val request = webDavRequest(
                    mount,
                    webDavUrl(mount, entry.relativePath, directory = false),
                    "GET",
                    null
                )
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val status = response.code
                    response.close()
                    throw IOException("WebDAV download failed (HTTP $status).")
                }
                val body = response.body ?: run {
                    response.close()
                    throw IOException("WebDAV returned an empty file.")
                }
                BufferedInputStream(body.byteStream()).let { input ->
                    object : java.io.FilterInputStream(input) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                response.close()
                            }
                        }
                    }
                }
            }
        }

    private fun smbContext(mount: NetworkMount): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", READ_TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.connTimeout", CONNECT_TIMEOUT_MS.toString())
        }
        val base = BaseContext(PropertyConfiguration(properties))
        val authenticator = if (mount.username.isBlank()) {
            NtlmPasswordAuthenticator()
        } else if (mount.domain.isBlank()) {
            NtlmPasswordAuthenticator(mount.username, mount.password)
        } else {
            NtlmPasswordAuthenticator(mount.domain, mount.username, mount.password)
        }
        return base.withCredentials(authenticator)
    }

    private fun smbFile(mount: NetworkMount, relativePath: String, context: CIFSContext): SmbFile {
        val encoded = relativePath.split('/')
            .filter(String::isNotEmpty)
            .joinToString("/") { Uri.encode(it) }
        val url = mount.serverUrl.trimEnd('/') +
            if (encoded.isEmpty()) "/" else "/$encoded"
        return SmbFile(url, context)
    }

    private fun webDavRequest(
        mount: NetworkMount,
        url: String,
        method: String,
        body: okhttp3.RequestBody?
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/xml, */*")
        if (mount.username.isNotEmpty()) {
            val credentials = "${mount.username}:${mount.password}"
                .toByteArray(Charsets.UTF_8)
            builder.header(
                "Authorization",
                "Basic ${android.util.Base64.encodeToString(credentials, android.util.Base64.NO_WRAP)}"
            )
            credentials.fill(0)
        }
        return builder.method(method, body).build()
    }

    private fun webDavUrl(mount: NetworkMount, path: String, directory: Boolean): String {
        if (path.startsWith('/') || path.split('/').any { it == "." || it == ".." }) {
            throw IOException("The WebDAV path is invalid.")
        }
        val encodedPath = path.split('/')
            .filter(String::isNotEmpty)
            .joinToString("/") { Uri.encode(it) }
        val suffix = if (directory) {
            if (encodedPath.isEmpty()) "" else "$encodedPath/"
        } else {
            encodedPath
        }
        return URI(mount.serverUrl).resolve(suffix).toString()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private class SmbMediaDataSource(
    private val file: SmbRandomAccessFile
) : MediaDataSource() {
    private val length = file.length()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (length <= 0L || position >= length) return -1
        return synchronized(this) {
            file.seek(position)
            file.read(buffer, offset, minOf(size.toLong(), length - position).toInt())
        }
    }

    override fun getSize(): Long = length

    override fun close() {
        try {
            file.close()
        } catch (_: IOException) {
        }
    }
}

private class WebDavMediaDataSource(
    private val client: OkHttpClient,
    private val length: Long,
    private val requestBuilder: () -> Request.Builder
) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (length > 0L && position >= length) return -1

        val end = if (length > 0L) {
            minOf(length - 1, position + size - 1)
        } else {
            position + size - 1
        }
        val request = requestBuilder()
            .header("Range", "bytes=$position-$end")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WebDAV range read failed (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("WebDAV returned an empty body.")
            body.byteStream().use { input ->
                // Servers that ignore Range reply with the whole file; skip to the offset.
                if (response.code == 200 && position > 0L) skipFully(input, position)
                var total = 0
                while (total < size) {
                    val read = input.read(buffer, offset + total, size - total)
                    if (read < 0) break
                    total += read
                }
                return if (total == 0) -1 else total
            }
        }
    }

    override fun getSize(): Long = if (length > 0L) length else -1L

    override fun close() = Unit

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val discard = ByteArray(8 * 1024)
        while (remaining > 0L) {
            val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("The WebDAV stream ended before the requested offset.")
            remaining -= read
        }
    }
}