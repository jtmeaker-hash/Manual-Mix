/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.io

import android.content.Intent
import app.setflow.domain.PersistableGrantStatus
import app.setflow.domain.TrackImportSource
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory / file-backed SAF [TrackSourceProvider] used when running outside an Android [android.content.Context]
 * and for deterministic simulation of SAF `content://` permissions, reboot grant revocations, SD card unmounts,
 * and cloud-only virtual documents without touching filesystem paths from `content://` URIs.
 */
class VirtualSafTrackSourceProvider(
    override val resourceTracker: OpenResourceTracker = OpenResourceTracker()
) : TrackSourceProvider {

    data class VirtualDocumentEntry(
        val uriString: String,
        val displayName: String,
        val declaredMimeType: String? = null,
        val backingFile: File? = null,
        val inMemoryBytes: ByteArray? = null,
        val lastModifiedMs: Long = 1_700_000_000_000L,
        val mediaStoreId: Long? = null,
        var isCloudOnlyOrVirtual: Boolean = false,
        var offersPersistableGrant: Boolean = true,
        var grantRevoked: Boolean = false,
        var sdCardMounted: Boolean = true
    ) {
        val sizeBytes: Long
            get() = backingFile?.takeIf { it.exists() }?.length()
                ?: inMemoryBytes?.size?.toLong()
                ?: 0L
    }

    private val entries = ConcurrentHashMap<String, VirtualDocumentEntry>()

    fun registerDocument(entry: VirtualDocumentEntry) {
        entries[entry.uriString] = entry
    }

    fun registerFileAsSafDocument(
        uriString: String,
        backingFile: File,
        displayName: String = backingFile.name,
        declaredMimeType: String? = null,
        offersPersistableGrant: Boolean = true,
        isCloudOnlyOrVirtual: Boolean = false
    ): VirtualDocumentEntry {
        val entry = VirtualDocumentEntry(
            uriString = uriString,
            displayName = displayName,
            declaredMimeType = declaredMimeType,
            backingFile = backingFile,
            lastModifiedMs = backingFile.lastModified(),
            offersPersistableGrant = offersPersistableGrant,
            isCloudOnlyOrVirtual = isCloudOnlyOrVirtual
        )
        entries[uriString] = entry
        return entry
    }

    fun setGrantRevoked(uriString: String, revoked: Boolean) {
        entries[uriString]?.grantRevoked = revoked
    }

    fun setSdCardMounted(uriString: String, mounted: Boolean) {
        entries[uriString]?.sdCardMounted = mounted
    }

    fun setCloudOnly(uriString: String, cloudOnly: Boolean) {
        entries[uriString]?.isCloudOnlyOrVirtual = cloudOnly
    }

    override fun takePersistableReadPermissionIfOffered(uriString: String, intentFlags: Int): PersistableGrantStatus {
        if (SafUriGuard.isFileUri(uriString)) {
            return PersistableGrantStatus.SESSION_ONLY
        }
        val entry = entries[uriString] ?: return PersistableGrantStatus.SESSION_ONLY
        if (entry.grantRevoked) {
            return PersistableGrantStatus.REVOKED_OR_DENIED
        }
        val offersPersistable = (intentFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
        val offersRead = (intentFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        return if (entry.offersPersistableGrant && offersPersistable && offersRead) {
            PersistableGrantStatus.PERSISTED
        } else if (offersRead) {
            PersistableGrantStatus.SESSION_ONLY
        } else {
            PersistableGrantStatus.REVOKED_OR_DENIED
        }
    }

    override fun queryMetadata(
        uriString: String,
        grantStatus: PersistableGrantStatus,
        importSource: TrackImportSource
    ): QueriedSourceMetadata {
        if (SafUriGuard.isFileUri(uriString)) {
            val file = SafUriGuard.resolveTestFileOnly(uriString)
            if (!file.exists()) throw FileNotFoundException("File not found: ${file.name}")
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

        val entry = entries[uriString]
            ?: throw FileNotFoundException("No SAF document registered for $uriString")
        if (entry.grantRevoked) {
            throw SecurityException("URI read permission revoked for $uriString")
        }
        if (!entry.sdCardMounted) {
            throw FileNotFoundException("External storage / SD card unmounted for $uriString")
        }
        if (entry.backingFile != null && !entry.backingFile.exists()) {
            throw FileNotFoundException("Backing file removed for $uriString")
        }

        return QueriedSourceMetadata(
            uriString = uriString,
            providerAuthority = SafUriGuard.extractAuthority(uriString) ?: "com.android.externalstorage.documents",
            displayName = entry.displayName,
            declaredMimeType = entry.declaredMimeType,
            sizeBytes = entry.sizeBytes,
            lastModifiedMs = entry.backingFile?.lastModified() ?: entry.lastModifiedMs,
            mediaStoreId = entry.mediaStoreId,
            isCloudOnlyOrVirtual = entry.isCloudOnlyOrVirtual,
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

        val entry = entries[uriString]
            ?: throw FileNotFoundException("No SAF document registered for $uriString")
        if (entry.grantRevoked) {
            throw SecurityException("URI read permission revoked for $uriString")
        }
        if (!entry.sdCardMounted) {
            throw FileNotFoundException("External storage / SD card unmounted for $uriString")
        }
        if (entry.isCloudOnlyOrVirtual) {
            throw CloudDocumentNotOfflineException("Document is cloud-backed; make available offline first")
        }

        val file = entry.backingFile
        if (file != null) {
            if (!file.exists()) throw FileNotFoundException("Backing file missing for $uriString")
            val raf = RandomAccessFile(file, "r")
            val tracked = resourceTracker.registerCloseable(raf)
            return NioFileSeekableTrackChannel(raf.channel, raf.length(), tracked)
        }

        val bytes = entry.inMemoryBytes ?: ByteArray(0)
        val tracked = resourceTracker.registerCloseable(Closeable {})
        return ByteArraySeekableTrackChannel(bytes, tracked)
    }
}
