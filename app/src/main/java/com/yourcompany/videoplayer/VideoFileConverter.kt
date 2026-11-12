package com.yourcompany.videoplayer

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object VideoFileConverter {
    fun encrypt(
        context: Context,
        source: File,
        onProgress: (Int) -> Unit = {}
    ): File {
        if (VideoFile.isLegacyEncrypted(source.absolutePath)) {
            throw IOException("Legacy encrypted videos cannot be converted.")
        }
        val target = encryptedOutputFor(source)
        val temporary = temporarySibling(target)
        try {
            EncryptedMediaContainer.encrypt(
                source,
                temporary,
                source.extension,
                LocalMediaKeyStore.getOrCreateWrappingKey(context),
                onProgress
            )
            moveIntoPlace(temporary, target)
            return target
        } finally {
            temporary.delete()
        }
    }

    fun decrypt(
        context: Context,
        source: File,
        onProgress: (Int) -> Unit = {}
    ): File {
        val reader = LocalMediaKeyStore.openEncryptedMedia(context, source)
        reader.use {
            val target = decryptedOutputFor(source, reader.originalExtension)
            val temporary = temporarySibling(target)
            try {
                EncryptedMediaContainer.decrypt(reader, temporary, onProgress)
                moveIntoPlace(temporary, target)
                return target
            } finally {
                temporary.delete()
            }
        }
    }

    fun existingPlaintextFor(context: Context, source: File): File? {
        val reader = LocalMediaKeyStore.openEncryptedMedia(context, source)
        reader.use {
            val expected = expectedPlaintextFor(source, reader.originalExtension)
            return expected.takeIf {
                it.isFile &&
                    !EncryptedMediaContainer.isContainer(it) &&
                    !VideoFile.isLegacyEncrypted(it.absolutePath)
            }
        }
    }

    internal fun encryptedOutputFor(source: File): File =
        uniqueSibling(
            source,
            "${source.nameWithoutExtension}_encrypted",
            EncryptedMediaContainer.FILE_EXTENSION,
            "${source.nameWithoutExtension}_encrypted"
        )

    internal fun decryptedOutputFor(source: File, originalExtension: String): File {
        val baseName = decryptedBaseName(source)
        return uniqueSibling(source, baseName, originalExtension, "${baseName}_decrypted")
    }

    internal fun expectedPlaintextFor(source: File, originalExtension: String): File {
        val parent = source.absoluteFile.parentFile
            ?: throw IOException("The source video has no parent directory.")
        return File(parent, "${decryptedBaseName(source)}.${originalExtension.trimStart('.')}")
    }

    private fun decryptedBaseName(source: File): String =
        source.nameWithoutExtension.removeSuffixIgnoreCase("_encrypted")
            .ifBlank { source.nameWithoutExtension }

    private fun uniqueSibling(
        source: File,
        preferredBaseName: String,
        extension: String,
        collisionBaseName: String
    ): File {
        val parent = source.absoluteFile.parentFile
            ?: throw IOException("The source video has no parent directory.")
        val normalizedExtension = extension.trimStart('.')
        var index = 0
        while (true) {
            val baseName = when (index) {
                0 -> preferredBaseName
                1 -> collisionBaseName
                else -> "${collisionBaseName}_${index - 1}"
            }
            val candidate = File(parent, "$baseName.$normalizedExtension")
            if (candidate.canonicalFile != source.canonicalFile && !candidate.exists()) return candidate
            index++
        }
    }

    private fun temporarySibling(target: File): File =
        File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
