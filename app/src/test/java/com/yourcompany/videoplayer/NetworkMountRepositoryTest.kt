package com.yourcompany.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkMountRepositoryTest {
    @Test
    fun usesListedFileLengthWhenSmbMetadataReportsZero() {
        assertEquals(15_000_000L, NetworkVideoUri.effectiveFileLength(0L, 15_000_000L))
    }

    @Test
    fun prefersReportedSmbFileLengthWhenAvailable() {
        assertEquals(14_000_000L, NetworkVideoUri.effectiveFileLength(14_000_000L, 15_000_000L))
    }

    @Test
    fun normalizesSmbShareAddress() {
        assertEquals(
            "smb://192.168.1.10/videos/",
            NetworkMountRepository.normalizeServerUrl(
                NetworkMountType.SMB,
                "smb://192.168.1.10/videos"
            )
        )
    }

    @Test
    fun normalizesHttpsWebDavAddress() {
        assertEquals(
            "https://example.com/dav/",
            NetworkMountRepository.normalizeServerUrl(
                NetworkMountType.WEBDAV,
                "https://example.com/dav/"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWebDavOverCleartextHttp() {
        NetworkMountRepository.normalizeServerUrl(
            NetworkMountType.WEBDAV,
            "http://example.com/dav/"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialsEmbeddedInAddress() {
        NetworkMountRepository.normalizeServerUrl(
            NetworkMountType.SMB,
            "smb://user:password@192.168.1.10/videos/"
        )
    }

    @Test
    fun acceptsBareIpAndDefaultsToRootShare() {
        assertEquals(
            "smb://192.168.1.10/",
            NetworkMountRepository.normalizeServerUrl(
                NetworkMountType.SMB,
                "192.168.1.10"
            )
        )
    }

    @Test
    fun normalizesBareIpWithShareName() {
        assertEquals(
            "smb://192.168.31.236/g/",
            NetworkMountRepository.normalizeServerUrl(
                NetworkMountType.SMB,
                "192.168.31.236/g"
            )
        )
    }


    @Test(expected = IllegalArgumentException::class)
    fun rejectsSmbFullUriWithoutSharePath() {
        NetworkMountRepository.normalizeServerUrl(
            NetworkMountType.SMB,
            "smb://192.168.1.10/"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEncodedParentSegmentsInWebDavAddress() {
        NetworkMountRepository.normalizeServerUrl(
            NetworkMountType.WEBDAV,
            "https://example.com/dav/%2e%2e/private/"
        )
    }
}
