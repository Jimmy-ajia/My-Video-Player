package com.yourcompany.videoplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface VideoScanState {
    data object Idle : VideoScanState
    data object Scanning : VideoScanState
    data object Complete : VideoScanState
    data class Failed(val message: String) : VideoScanState
}

@OptIn(ExperimentalCoroutinesApi::class)
class VideoLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences =
        application.getSharedPreferences(SORT_PREFERENCES, Application.MODE_PRIVATE)
    private val database = VideoLibraryDatabase.getInstance(application)
    private val repository = VideoLibraryRepository(VideoRepository(application), database)
    private val mutableSortOrder = MutableStateFlow(
        VideoSortOrder.fromPreference(preferences.getString(SORT_ORDER_KEY, null))
    )
    private val mutableScanState = MutableStateFlow<VideoScanState>(VideoScanState.Idle)

    val sortOrder: StateFlow<VideoSortOrder> = mutableSortOrder.asStateFlow()
    val scanState: StateFlow<VideoScanState> = mutableScanState.asStateFlow()
    val videos: StateFlow<List<VideoFile>> = mutableSortOrder
        .flatMapLatest(repository::observeVideos)
        .map { entities -> entities.map(VideoEntity::toVideoFile) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var scanInProgress = false

    fun setSortOrder(sortOrder: VideoSortOrder) {
        if (mutableSortOrder.value == sortOrder) return
        preferences.edit().putString(SORT_ORDER_KEY, sortOrder.preferenceValue).apply()
        mutableSortOrder.value = sortOrder
    }

    fun refreshVideos() {
        if (scanInProgress) return
        scanInProgress = true
        viewModelScope.launch {
            mutableScanState.value = VideoScanState.Scanning
            try {
                val directories = ScanDirectoryStore.getPaths(getApplication())
                withContext(Dispatchers.IO) {
                    repository.refresh(directories)
                }
                mutableScanState.value = VideoScanState.Complete
            } catch (error: CancellationException) {
                mutableScanState.value = VideoScanState.Idle
                throw error
            } catch (error: Exception) {
                mutableScanState.value =
                    VideoScanState.Failed(error.message ?: error.javaClass.simpleName)
            } finally {
                scanInProgress = false
            }
        }
    }

    private companion object {
        const val SORT_PREFERENCES = "VIDEO_LIST"
        const val SORT_ORDER_KEY = "SORT_ORDER"
    }
}
