# 问题分析：加密视频无法显示时长

## 问题描述
非加密视频可以正常显示时间，但私有加密视频在首页列表无法显示时长（显示为 0）。

## 原因定位
通过代码审查发现，问题集中在 `VideoRepository.kt` 的 `getDurationManually` 方法中。

### 核心逻辑缺陷：
1. **URI 获取失败**：对于加密容器，程序尝试使用 `context.contentResolver.openFileDescriptor(EncryptedVideoUri.fromPath(path), "r")` 获取文件描述符。如果 `standardizePath` 处理后的路径与 `EncryptedVideoUri` 预期的格式不匹配，或者由于私有目录权限限制导致 `openFileDescriptor` 返回 `null`，则 `retriever` 将无法获取数据源。
2. **静默失败**：当前的 `catch` 块仅打印堆栈信息，且没有提供备选方案。当 URI 方式失败时，程序直接返回初始值 `0L`。

## 解决方案

### 1. 短期修复（增加容错与备选路径）
修改 `getDurationManually` 方法，在 URI 方式获取文件描述符失败时，尝试回退到使用 `File` 对象作为数据源。

**修改代码建议：**
```kotlin
private fun getDurationManually(path: String): Long {
    var duration = 0L
    val retriever = MediaMetadataRetriever()
    try {
        if (EncryptedMediaContainer.isContainer(File(path))) {
            val uri = EncryptedVideoUri.fromPath(path)
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
            if (fd != null) {
                fd.use { retriever.setDataSource(it.fileDescriptor) }
            } else {
                // 备选方案：如果 ContentResolver 无法获取 FD，尝试直接使用 File 对象
                retriever.setDataSource(File(path).absolutePath)
            }
        } else {
            retriever.setDataSource(path)
        }
        val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        duration = time?.toLong() ?: 0L
    } catch (e: Exception) {
        android.util.Log.e("VideoRepo", "Failed to get duration for $path: ${e.message}")
    } finally {
        try { retriever.release() } catch (e: Exception) {}
    }
    return duration
}
```

### 2. 长期优化（日志监控与路径核对）
*   **增加详细日志**：在 `catch` 块中记录具体的异常信息，以便区分是 `FileNotFoundException`（路径问题）还是 `SecurityException`（权限问题）。
*   **路径一致性检查**：验证 `standardizePath` 函数处理后的字符串是否与 `EncryptedVideoUri.fromPath()` 内部解析逻辑完全兼容。

---
*Status: Proposed Solution*
*Reference Files: `app/src/main/java/com/yourcompany/videoplayer/VideoRepository.kt`*
