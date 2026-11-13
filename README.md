# My Video Player

基于 AndroidX Media3 ExoPlayer 的 Android 本地视频播放器。应用可以将常见视频容器转换为私有 `.oxu` 加密文件，并通过本机 Android Keystore 解包文件密钥、按需解密播放数据。

## 项目概览

- 浏览本地视频并进行播放、进度拖动、音轨/字幕选择等播放器操作。
- 主页以可滚动视频列表展示预览、名称和加密状态；支持单项加密/解密、左滑删除和长按多选管理。
- 多选模式支持全选/取消全选、批量加密、解密和删除；删除前会确认，处理后报告未完成项目。
- 使用 Room 缓存视频目录；首次启动时自动扫描 Movies、DCIM、Pictures、Download，之后可在主页下拉刷新并增量同步变更。
- 设置中可配置 SMB 2/3 和 HTTPS WebDAV 共享，并在应用内浏览、播放共享视频。
- 支持按修改时间倒序、名称正序或倒序排序；最近播放的视频以蓝色边框和名称高亮。
- 页面和弹窗支持跟随系统明暗主题。
- 可在设置中管理预设目录和添加共享存储/SD 卡目录。
- 将视频文件内容按固定大小分块，以 AES-GCM 加密和认证。
- 每个文件的数据密钥由本地恢复主密钥包装；设备内的恢复主密钥由 Android Keystore 密钥加密保存。
- 可导出/导入口令保护的 `.mpvkey` 恢复文件，以便重装应用后恢复新加密的视频。
- ExoPlayer 优先播放；ExoPlayer 报错时使用 libVLC 尝试兜底。
- 加密不解析 MP4 sample 表或修改原始容器字节，因此加密层可处理多种媒体容器；实际可播放性由 ExoPlayer/libVLC 的 demuxer、codec 和设备解码能力决定。

| 配置 | 值 |
| --- | --- |
| 最低 Android 版本 | API 26 |
| compileSdk / targetSdk | 36 / 36 |
| Java / Kotlin 字节码目标 | 11 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.10 |
| ExoPlayer (Media3) | 1.5.0 |
| libVLC | 3.7.7 |
| jcifs-ng | 2.1.10 |
| OkHttp | 4.12.0 |
| Room | 2.7.2 |

Gradle 依赖版本集中在 [`gradle/libs.versions.toml`](./gradle/libs.versions.toml)，应用配置位于 [`app/build.gradle.kts`](./app/build.gradle.kts)。

## 模块职责

| 组件 | 职责 |
| --- | --- |
| `VideoRepository`、`VideoFile` | 扫描视频、读取基本信息并识别加密容器和旧格式。 |
| `VideoLibraryDatabase`、`VideoLibraryRepository` | 持久化视频元数据；只写入新增或变更项，并清理已确认失效的记录。 |
| `VideoLibraryViewModel` | 管理后台扫描、列表排序和扫描状态。 |
| `MainVideoAdapter` | 通过 RecyclerView/DiffUtil 显示视频列表、缩略图、加密状态和最近播放高亮，并维护多选/左滑删除交互状态。 |
| `ScanDirectoryStore` | 保存预设及用户添加的视频扫描目录。 |
| `SettingsActivity`、`VideoPickerActivity` | 管理扫描目录和恢复密钥，执行批量加密并显示进度和错误。 |
| `VideoFileConverter` | 在源目录安全创建加密/解密输出，避免覆盖现有文件。 |
| `EncryptedMediaContainer` | 定义 `.oxu` 二进制格式，实现加密、认证和随机范围解密。 |
| `LocalMediaKeyStore` | 本地安全保存恢复主密钥，并兼容读取旧版 Keystore 密钥加密的文件。 |
| `RecoveryKeyBackup` | 使用口令加密/解密 `.mpvkey` 恢复文件。 |
| `EncryptedVideoUri`、`DecryptionProvider` | 为播放器提供只读虚拟媒体文件描述符。 |
| `PlayerActivity`、`PlaybackService` | ExoPlayer 播放、播放控制和 MediaSession。 |
| `VlcFallbackActivity` | ExoPlayer 播放失败后用 libVLC 重试播放。 |
| `NetworkMountStore`、`NetworkMountRepository`、`NetworkMountBrowserActivity` | 通过 Keystore 加密保存共享凭据，浏览 SMB/WebDAV 并把所选视频缓存到应用私有目录后播放。 |

## 加密及播放流程

### 加密

1. 用户在转换界面选择视频文件。
2. `EncryptedMediaContainer` 使用文件扩展名记录容器类型，不解析/改写 MP4 或其他容器结构。
3. 为每个文件生成随机 256 位数据密钥和随机 nonce 前缀。
4. 数据密钥及原始扩展名由本地恢复主密钥通过 AES-GCM 包装；恢复主密钥的本机副本由 Android Keystore 密钥加密保存。
5. 输入内容按 1 MiB 分块；每个块独立使用 AES-GCM 加密并认证，支持随机读取和认证失败检测。
6. 在源文件同目录写入隐藏临时文件，完成后原子改名为 `<原文件名>_encrypted.oxu`。临时文件不会复制源视频；原始明文视频保留，不会自动删除。

主页锁图标可切换加密状态。解密时使用容器记录的原始扩展名，并在同目录创建解密文件。如果预计原视频已存在，应用会说明匹配依据及无法核验文件内容的限制，并逐次询问是否删除加密文件；选择保留两者时不执行删除。其他情况若解密目标已存在，则选择不冲突的名称。加密会保留明文输入；解密默认保留加密输入，只有用户明确确认删除匹配的加密副本时才会删除，且不会覆盖已有文件。

首页左滑视频条目可显示删除按钮，点击后需再次确认；长按条目进入多选模式，可全选/取消全选并批量加密、解密或删除。批量转换只处理与所选操作匹配的文件，保留原始输入并逐项报告不支持或失败原因；删除仅作用于确认选择的文件，且会检查文件大小是否在操作前发生变化。

### 播放

1. `PlayerActivity` 识别 `.oxu` 加密文件，并先交给 ExoPlayer。
2. 播放器通过应用内部 `content://` URI 打开 `DecryptionProvider`。
3. Provider 验证并解包文件数据密钥，通过代理文件描述符按需提供明文范围；每个块在返回前都通过 AES-GCM 认证。
4. 如果 ExoPlayer 无法识别容器或解码失败，应用打开 `VlcFallbackActivity`，libVLC 使用同一个 Provider 数据源重试。
5. 普通未加密文件也可在 ExoPlayer 失败后交给 VLC。

该设计不生成完整的解密临时文件，但播放器读取到的数据和应用进程内存中的密钥/明文仍可被运行时访问。

### 恢复密钥

- 在设置页选择“Export recovery key”，将 `.mpvkey` 文件保存到本地；导出时需设置并确认至少 12 个字符的口令。恢复文件使用 PBKDF2-HMAC-SHA256 派生密钥和 AES-GCM 加密。
- 重装应用后，先在设置页选择“Import recovery key”，选择 `.mpvkey` 文件并输入原口令，再播放由该恢复主密钥加密的视频。
- 新恢复主密钥只用于启用此功能后加密的文件。更早的文件仍依赖原安装中的不可导出 Keystore 密钥；按当前迁移范围，这些文件不会自动迁移，卸载应用后无法通过新恢复文件解锁。
- 恢复文件和口令应分别妥善备份。遗失恢复文件或忘记口令都无法恢复密钥；口令不保存在应用内。

## `.oxu` 容器 v1

新文件使用私有扩展名 `.oxu`，内部 magic 为 `MVPBLK01`。这是本项目自定义的二进制格式，不是通用媒体标准。此前生成的 `.mpv` 加密文件仍可扫描和播放。

```text
[MVPBLK01 固定头部]
[AES-GCM 包装后的文件密钥及原始扩展名]
[AES-GCM 加密块 0]
[AES-GCM 加密块 1]
...
```

多字节整数采用大端序。固定头部字段：

| 字段 | 大小 | 含义 |
| --- | ---: | --- |
| magic | 8 字节 | ASCII `MVPBLK01` |
| version | u32 | 当前版本为 1 |
| chunk-size | u32 | 当前固定为 1 MiB |
| plaintext-size | u64 | 源文件字节数 |
| file-nonce-prefix | 8 字节 | 与块序号组合得到每块 nonce |
| wrapping-nonce | 12 字节 | 包装文件密钥的 AES-GCM nonce |
| wrapped-key-size | u32 | 后续包装密钥记录长度 |

后续 key record 在磁盘上是 AES-GCM 密文，明文包含 32 字节文件数据密钥、原始扩展名长度及扩展名。头部作为附加认证数据 (AAD) 参与密钥包装认证。

每块的密文长度等于该块明文长度加 16 字节 GCM tag。块 nonce 为 `file-nonce-prefix || chunk-index:u32`。块 AAD 绑定完整头部、块序号和该块明文长度。因此更改头部、包装密钥记录或任意数据块都会导致认证失败。最后一块可以小于 1 MiB。

## 支持的输入扩展名与播放边界

转换器接受以下扩展名：

- MP4 系列：`.mp4`、`.m4v`、`.mov`、`.3gp`、`.3g2`
- Matroska / WebM：`.mkv`、`.webm`
- MPEG：`.ts`、`.mts`、`.m2ts`、`.mpg`、`.mpeg`、`.vob`
- 其他常见容器：`.flv`、`.avi`、`.wmv`、`.asf`

扩展名校验不是对媒体内容的完整格式或 codec 校验。加密器只保护文件字节，不转码、不保证文件本身可解码。ExoPlayer 先尝试；未支持的 demuxer/codec 交由 VLC 尝试。播放能力仍受各播放器版本、Android 设备解码器、具体 codec/profile、封装变体及文件损坏情况影响；即使扩展名在列表内，也不保证每个文件都能播放。

## 主页扫描目录

应用首次启动时自动扫描设置中列出的目录；之后进入主页不会自动扫描，可在首页下拉刷新手动触发相同的扫描流程。默认目录包含共享存储的 `Movies`、`DCIM`、`Pictures` 和 `Download`；可在设置中移除预设目录，或使用系统目录选择器添加共享存储/SD 卡目录。视频元数据保存在 Room 数据库中，主页先显示上次扫描结果；扫描期间显示“视频加载中…”进度弹窗。同步时只写入新增或元数据有变化的视频，并删除已成功扫描目录中不存在的文件及已从扫描设置中移除目录的记录。暂时不可访问的存储卷不会被误判为已删除。

播放器离开播放页时会保存当前位置并暂停；点击主页最近播放的视频会从对应文件上次记录的位置继续播放。播放到视频末尾时会清除该视频的续播位置。

列表支持按修改时间倒序（默认）、名称正序或名称倒序排序，选择会保存在本机。最近播放的视频以蓝色边框和名称高亮，不再显示单独的 Resume 按钮。视频条目左侧显示预览，右侧显示名称、加密状态和锁图标。

当前目录选择基于 Android 共享存储/SD 卡的文件路径，其他云盘或文档提供方 URI 不作为扫描目录。Android 11 及以上使用“管理所有文件”权限访问这些路径；扫描只列出支持扩展名的视频及本项目 `.oxu`、旧版 `.mpv` 容器。

### 网络共享

在设置页添加 SMB 共享（仅协商 SMB 2/3，不启用 SMB1）或 HTTPS WebDAV 地址后，可从共享条目进入浏览器。账号和密码以 AES-GCM 加密后保存在应用私有设置中，包装密钥由 Android Keystore 管理；地址中不接受内嵌凭据。WebDAV 使用 Basic 身份验证并要求 HTTPS，且不跟随重定向，避免把凭据转发到其他主机。

首次播放网络视频时，应用会先完整下载到应用私有临时缓存，再交给现有播放器；`.oxu` 文件以密文缓存，并继续通过本机恢复密钥解密播放。该方式需要足够的本机存储空间，不是边下边播；缓存可被 Android 清理，清理后需要重新下载。旧版 `.mpv` 网络文件仍受现有旧格式播放限制。

网络共享配置使用的 Keystore 密钥不参与 Android 备份；重装或清除应用数据后若配置无法解密，需要重新添加共享。SMB 是否启用传输加密取决于共享服务器的 SMB 设置。

## 密钥与云盘安全边界

- 设备内的恢复主密钥由不可导出的 Android Keystore 密钥加密保存；若用户导出 `.mpvkey` 并妥善保管口令，可在重装应用后导入恢复新加密文件。
- **尚未导出恢复文件的 `.oxu` 不能跨安装恢复。** 旧版 `.mpv`/Keystore 密钥加密的文件也不包含在新恢复文件内。Keystore 重置、应用卸载或设备更换前，应确认目标文件对应的恢复密钥已导出且可用。
- 恢复文件是口令保护的离线备份，不提供账号同步、远程撤销或找回口令功能。口令过弱可能被离线猜测；建议使用独立、强口令。
- 保护仅适用于上传 `.oxu` 加密输出的情形。转换不会删除原文件；若将原始视频也上传，云端仍可读取原文件。
- 当前输出文件名保留源视频名称的一部分，路径、名称、文件大小和访问时间等外部元数据不会加密。
- Android Keystore 提高静态密钥提取难度，但不防拥有设备/应用运行时控制权的攻击者；播放时密钥和解密数据必须在应用可用。
- 旧版 `MVPCTR01` MP4 容器和更早 marker/signature 格式不兼容新格式，不会被新版本解密。

## 第三方库授权说明

项目使用 `org.videolan.android:libvlc-all:3.7.7` 和 `eu.agno3.jcifs:jcifs-ng:2.1.10`。两者 Maven POM 分别声明 GNU LGPL 2.1（libVLC）和 LGPL（jcifs-ng）；VLC AAR 约 88 MiB。VLC 分发物可能包含具有各自许可条件的编解码器及其他组件，Maven POM 声明不能替代对最终分发物内容、NOTICE/源码提供要求和应用分发方式的逐项审查。发布前应由项目维护方完成相应许可合规审查。参考：[VideoLAN 法律信息](https://www.videolan.org/legal.html)、[jcifs-ng 项目](https://github.com/AgNO3/jcifs-ng/)。

## 构建和测试

需要配置 Android SDK Platform 36 和 JDK 21。执行：

```shell
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Android SDK 路径通过未纳入版本控制的 `local.properties` 中的 `sdk.dir` 配置。测试视频样本保存在 [`app/src/test/resources/video-samples/`](./app/src/test/resources/video-samples/)：

- `flower.mp4` 和 `flower.webm` 来自 [MDN CC0 video samples](https://interactive-examples.mdn.mozilla.net/media/cc0-videos/)。
- `flower.mkv`、`flower.mov`、`flower.avi`、`flower.flv`、`flower.ts`、`flower.wmv`、`flower.3gp`、`flower.vob` 是使用 FFmpeg 从上述 CC0 样本生成的容器变体。

单元测试会对这些真实容器样本逐字节加密和还原比对，并覆盖跨 1 MiB 边界的随机范围读取、错误包装密钥拒绝、数据块篡改拒绝及头部修改拒绝。另覆盖解密文件往返、保留输入、同目录输出及同名文件不覆盖；恢复密钥测试覆盖口令加密往返、错误口令/篡改拒绝及导出口令长度限制。样本测试只验证加密容器的字节保真，不代表播放器兼容性；仍需在真实设备上验证 Android Keystore、目录选择器、MediaStore 对 `.oxu`/`.mpv` 的发现、ExoPlayer 到 VLC 的回退，以及各扩展名的实际播放。

### 代办功能
 
 - 视频加载样式优化，或只首次进入扫描
 - 局域网共享 smba
 - webdva挂载
 - 分享加密文件到各个网盘
 - 调整产品名称【私密视频】- 待确认
 - 管理文件，删除，多选，批量加密，解密

