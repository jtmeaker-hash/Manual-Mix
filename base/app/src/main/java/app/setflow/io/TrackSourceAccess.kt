/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.setflow.domain.PersistableGrantStatus
import app.setflow.domain.TrackImportSource
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks all open file descriptors and seekable channels to guarantee zero FD leaks
 * across normal imports, decode failures, and user cancellations (Stage 03 Task 6 & Gate 4).
 */
class OpenResourceTracker {
    private val activeCount = AtomicInteger(0)
    private val openedTotal = AtomicLong(0L)
    private val closedTotal = AtomicLong(0L)

    val activeFileDescriptors: Int get() = activeCount.get()
    val totalOpened: Long get() = openedTotal.get()
    val totalClosed: Long get() = closedTotal.get()

    fun registerCloseable(underlying: Closeable): Closeable {
        activeCount.incrementAndGet()
        openedTotal.incrementAndGet()
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                try {
                    underlying.close()
                } finally {
                    activeCount.decrementAndGet()
                    closedTotal.incrementAndGet()
                }
            }
        }
    }
}

/**
 * Enforces Stage 03 Task 2 & Bug Trap rule:
 * Never construct a filesystem path or [File] from a `content://` URI!
 */
object SafUriGuard {
    fun isContentUri(uriString: String): Boolean =
        uriString.startsWith("content://", ignoreCase = true)

    fun isFileUri(uriString: String): Boolean =
        uriString.startsWith("file://", ignoreCase = true)

    fun extractAuthority(uriString: String): String? {
        val schemeIdx = uriString.indexOf("://")
        if (schemeIdx < 0) return null
        val rest = uriString.substring(schemeIdx + 3)
        val slashIdx = rest.indexOf('/')
        val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
        return authority.ifBlank { null }
    }

    /**
     * Strictly forbids converting `content://` URIs to [File].
     * Only explicit `file://` URIs (used in JVM/CLI test harnesses) may resolve to [File].
     */
    fun resolveTestFileOnly(uriString: String): File {
        require(!isContentUri(uriString)) {
            "Forbidden: content:// URI ($uriString) must be read via ContentResolver/TrackSourceProvider, never converted to a filesystem path."
        }
        require(isFileUri(uriString)) {
            "Expected file:// URI for local test file access, got: $uriString"
        }
        val path = java.net.URI(uriString).path
        return File(path)
    }
}

/**
 * Read-only random-access byte channel for inspecting headers, computing fast fingerprints,
 * and decoding audio without ever mutating the underlying source file.
 */
interface SeekableTrackChannel : Closeable {
    val sizeBytes: Long
    fun readAt(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int
}

/**
 * Read-only [SeekableTrackChannel] backed by a NIO [FileChannel] (from Android `ParcelFileDescriptor` or JVM `RandomAccessFile("r")`).
 */
class NioFileSeekableTrackChannel(
    private val fileChannel: FileChannel,
    override val sizeBytes: Long,
    private val trackedCloseable: Closeable
) : SeekableTrackChannel {

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position < 0 || (sizeBytes >= 0 && position >= sizeBytes)) return -1
        val dst = ByteBuffer.wrap(buffer, offset, length)
        var total = 0
        var pos = position
        while (dst.hasRemaining()) {
            val n = fileChannel.read(dst, pos)
            if (n <= 0) break
            total += n
            pos += n
        }
        return if (total == 0) -1 else total
    }

    override fun close() {
        trackedCloseable.close()
    }
}

/**
 * Read-only [SeekableTrackChannel] backed by an in-memory byte array (used for simulated SAF providers or small streams).
 */
class ByteArraySeekableTrackChannel(
    private val data: ByteArray,
    private val trackedCloseable: Closeable
) : SeekableTrackChannel {
    override val sizeBytes: Long get() = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position < 0 || position >= data.size) return -1
        val start = position.toInt()
        val n = minOf(length, data.size - start)
        if (n <= 0) return -1
        System.arraycopy(data, start, buffer, offset, n)
        return n
    }

    override fun close() {
        trackedCloseable.close()
    }
}

/**
 * Metadata and grant status queried from the URI provider (SAF DocumentProvider or MediaStore) before decoding.
 */
data class QueriedSourceMetadata(
    val uriString: String,
    val providerAuthority: String?,
    val displayName: String,
    val declaredMimeType: String?,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val mediaStoreId: Long? = null,
    val isCloudOnlyOrVirtual: Boolean = false,
    val grantStatus: PersistableGrantStatus = PersistableGrantStatus.PERSISTED,
    val importSource: TrackImportSource = TrackImportSource.SAF_DOCUMENT_PICKER
)

/**
 * Exception thrown when a DocumentProvider indicates a file is virtual or cloud-backed and not downloaded for offline access.
 */
class CloudDocumentNotOfflineException(message: String) : IOException(message)

/**
 * Abstraction over Android [ContentResolver] + SAF/MediaStore and JVM test providers.
 * Guarantees all I/O is read-only and tracked by [OpenResourceTracker].
 */
interface TrackSourceProvider {
    val resourceTracker: OpenResourceTracker

    /**
     * Attempts `takePersistableUriPermission` only when [intentFlags] offers `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`
     * and `FLAG_GRANT_READ_URI_PERMISSION`. Catches [SecurityException] gracefully.
     */
    fun takePersistableReadPermissionIfOffered(uriString: String, intentFlags: Int): PersistableGrantStatus

    /**
     * Queries provider metadata (`DISPLAY_NAME`, `SIZE`, `LAST_MODIFIED`, `MIME_TYPE`, `FLAGS`) without constructing filesystem paths.
     */
    @Throws(IOException::class, SecurityException::class)
    fun queryMetadata(
        uriString: String,
        grantStatus: PersistableGrantStatus = PersistableGrantStatus.PERSISTED,
        importSource: TrackImportSource = TrackImportSource.SAF_DOCUMENT_PICKER
    ): QueriedSourceMetadata

    /**
     * Opens a read-only [SeekableTrackChannel] registered with [resourceTracker].
     * Caller MUST close the returned channel via `use { ... }`.
     */
    @Throws(IOException::class, SecurityException::class)
    fun openReadOnlyChannel(uriString: String): SeekableTrackChannel
}

/**
 * Production Android [TrackSourceProvider] reading `content://` URIs exclusively via [ContentResolver]
 * (`openFileDescriptor(uri, "r")` / `query`), and `file://` URIs via read-only `RandomAccessFile("r")`.
 */
class AndroidSafTrackSourceProvider(
    context: Context,
    override val resourceTracker: OpenResourceTracker = OpenResourceTracker()
) : TrackSourceProvider {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    override fun takePersistableReadPermissionIfOffered(uriString: String, intentFlags: Int): PersistableGrantStatus {
        if (!SafUriGuard.isContentUri(uriString)) {
            return PersistableGrantStatus.SESSION_ONLY
        }
        val offersPersistable = (intentFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
        val offersRead = (intentFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        if (!offersPersistable || !offersRead) {
            return PersistableGrantStatus.SESSION_ONLY
        }
        return try {
            val uri = Uri.parse(uriString)
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            PersistableGrantStatus.PERSISTED
        } catch (_: SecurityException) {
            PersistableGrantStatus.REVOKED_OR_DENIED
        } catch (_: UnsupportedOperationException) {
            PersistableGrantStatus.SESSION_ONLY
        }
    }

    override fun queryMetadata(
        uriString: String,
        grantStatus: PersistableGrantStatus,
        importSource: TrackImportSource
    ): QueriedSourceMetadata {
        if (SafUriGuard.isFileUri(uriString)) {
            val file = SafUriGuard.resolveTestFileOnly(uriString)
            if (!file.exists()) throw FileNotFoundException("File does not exist: ${file.name}")
            return QueriedSourceMetadata(
                uriString = uriString,
                providerAuthority = "local.file",
                displayName = file.name,
                declaredMimeType = null,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified(),
                mediaStoreId = null,
                isCloudOnlyOrVirtual = false,
                grantStatus = grantStatus,
                importSource = importSource
            )
        }

        val uri = Uri.parse(uriString)
        val authority = uri.authority ?: SafUriGuard.extractAuthority(uriString)
        var displayName = uri.lastPathSegment ?: "Untitled"
        var sizeBytes = -1L
        var lastModifiedMs = 0L
        var docFlags = 0
        val declaredMime = runCatching { resolver.getType(uri) }.getOrNull()

        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    displayName = cursor.getString(nameIdx) ?: displayName
                }
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    sizeBytes = cursor.getLong(sizeIdx)
                }
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (modIdx >= 0 && !cursor.isNull(modIdx)) {
                    lastModifiedMs = cursor.getLong(modIdx)
                }
                val flagsIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (flagsIdx >= 0 && !cursor.isNull(flagsIdx)) {
                    docFlags = cursor.getInt(flagsIdx)
                }
            }
        }

        val isVirtual = (docFlags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0
        val mediaStoreId = if (importSource == TrackImportSource.MEDIA_STORE_BROWSE || authority == MediaStore.AUTHORITY) {
            uri.lastPathSegment?.toLongOrNull()
        } else {
            null
        }

        if (sizeBytes < 0 && !isVirtual) {
            openReadOnlyChannel(uriString).use { ch ->
                sizeBytes = ch.sizeBytes
            }
        }

        return QueriedSourceMetadata(
            uriString = uriString,
            providerAuthority = authority,
            displayName = displayName,
            declaredMimeType = declaredMime,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            mediaStoreId = mediaStoreId,
            isCloudOnlyOrVirtual = isVirtual,
            grantStatus = grantStatus,
            importSource = importSource
        )
    }

    override fun openReadOnlyChannel(uriString: String): SeekableTrackChannel {
        if (SafUriGuard.isFileUri(uriString)) {
            val file = SafUriGuard.resolveTestFileOnly(uriString)
            if (!file.exists()) throw FileNotFoundException("File not found: ${file.name}")
            val raf = RandomAccessFile(file, "r")
            val tracked = resourceTracker.registerCloseable(raf)
            return NioFileSeekableTrackChannel(raf.channel, raf.length(), tracked)
        }

        val uri = Uri.parse(uriString)
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("ContentResolver returned null ParcelFileDescriptor for $uriString")
        val fis = FileInputStream(pfd.fileDescriptor)
        val compositeCloseable = Closeable {
            try {
                fis.close()
            } finally {
                pfd.close()
            }
        }
        val tracked = resourceTracker.registerCloseable(compositeCloseable)
        val statSize = pfd.statSize.takeIf { it >= 0 } ?: runCatching { fis.channel.size() }.getOrDefault(-1L)
        return NioFileSeekableTrackChannel(fis.channel, statSize, tracked)
    }
}

/**
 * Fast, bounded content fingerprinting (Stage 03 Task 3 & Task 8).
 * Avoids hashing 2,600 full-length FLACs on import while distinguishing same-named files with different recording bytes.
 */
object TrackFingerprinter {
    const val WINDOW_BYTES = 64 * 1024 // 64 KiB per sample window (max 192 KiB read per file)
    const val PREFIX_V1 = "sf-fp-v1"

    /**
     * Computes `sf-fp-v1:<sizeBytes>:<sha256>` from file size + up to 3 x 64 KiB windows (head, midpoint, tail).
     * Reads at most 192 KiB regardless of whether the file is 5 MB or 500 MB.
     */
    fun computeFastContentFingerprint(
        channel: SeekableTrackChannel,
        cancelled: () -> Boolean = { false }
    ): String {
        if (cancelled()) throw InterruptedException("Fingerprinting cancelled")
        val size = channel.sizeBytes
        if (size <= 0L) throw IOException("Cannot fingerprint empty or unknown-size stream (size=$size)")

        val digest = MessageDigest.getInstance("SHA-256")
        val sizeHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(size).array()
        digest.update(sizeHeader)

        val windowOffsets = buildList {
            add(0L)
            if (size > WINDOW_BYTES) {
                val mid = (size / 2L).coerceAtLeast(0L)
                if (mid > 0L) add(mid)
            }
            if (size > WINDOW_BYTES * 2L) {
                val tail = (size - WINDOW_BYTES).coerceAtLeast(0L)
                if (tail > 0L) add(tail)
            }
        }.distinct()

        val buf = ByteArray(WINDOW_BYTES)
        for (offset in windowOffsets) {
            if (cancelled()) throw InterruptedException("Fingerprinting cancelled")
            val toRead = minOf(WINDOW_BYTES.toLong(), size - offset).toInt()
            if (toRead <= 0) continue
            val read = channel.readAt(offset, buf, 0, toRead)
            if (read <= 0) throw IOException("Unexpected EOF while reading fingerprint window at offset=$offset")
            digest.update(buf, 0, read)
        }

        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return "$PREFIX_V1:$size:$hex"
    }

    /**
     * Computes full-file SHA-256 checksum (used in Stage 03 Task 8 tests to prove non-destructive access).
     */
    fun computeFullSha256(
        channel: SeekableTrackChannel,
        cancelled: () -> Boolean = { false }
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        while (true) {
            if (cancelled()) throw InterruptedException("SHA-256 cancelled")
            val read = channel.readAt(pos, buf, 0, buf.size)
            if (read <= 0) break
            digest.update(buf, 0, read)
            pos += read
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeFullSha256(file: File): String {
        RandomAccessFile(file, "r").use { raf ->
            val ch = NioFileSeekableTrackChannel(raf.channel, raf.length(), Closeable {})
            return computeFullSha256(ch)
        }
    }
}
