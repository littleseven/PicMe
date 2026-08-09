package com.mamba.picme.data

import com.mamba.picme.domain.repository.AccessState

/**
 * Photos framework 取数 DTO：字段全原始类型，K/N → ObjC 导出友好。
 *
 * 注意：不含 `id` 字段——Swift `hashValue` 每次启动随机化，不能作稳定 id；
 * `MediaAsset.id` 由 Kotlin 侧用 `localIdentifier.hashCode()` 派生（进程内稳定，仅用于查找）。
 */
data class IosMediaItem(
    /** PHAsset.localIdentifier，同时作 MediaAsset.uri（iOS 无 content:// Uri，标识符即定位符） */
    val localIdentifier: String,
    /** "PHOTO" | "VIDEO"（字符串而非枚举，避免 K/N 枚举导出名碰撞） */
    val mediaType: String,
    val captureDateMs: Long,
    val durationMs: Long? = null,
    /** PHAssetResource.originalFilename，对齐 Android MediaStore DISPLAY_NAME（S5） */
    val fileName: String
)

/**
 * Swift（Photos framework）→ Kotlin 的桥协议。由 iosApp `PhMediaBridge`（Swift/NSObject）实现。
 *
 * SharedBridge 铁律（kmp-ios-interop skill）：Swift 实现侧绝不抛异常跨边界——
 * 未声明 @Throws 的 Kotlin 异常逃逸到 Swift 会 signal 6（SIGABRT）崩溃。
 * 失败一律用 false / 空集合表达。
 */
interface IosMediaRepositoryBridge {
    /** 当前相册授权态快照（PHAuthorizationStatus → AccessState 映射在 Swift 侧）。 */
    fun currentAccessState(): AccessState

    /** 全量取数（creationDate 降序，与 Android MediaStore 排序对齐，S5 双端一致）。 */
    fun fetchAllMedia(): List<IosMediaItem>

    /** 异步弹系统授权窗；完成后经 changeListener 通知刷新。 */
    fun requestReadWriteAuthorization()

    /** PHPhotoLibraryObserver 变更回调注册；触发即全量重取（相册量级下成本可接受）。 */
    fun addChangeListener(listener: () -> Unit)

    /** 注销变更回调（awaitClose 时调用，防 listener 泄漏）。 */
    fun removeChangeListener()

    /**
     * 经 PHAssetChangeRequest 删除（iOS 系统弹确认窗，天然免 Android 11+ IntentSender 授权逻辑）。
     * 返回是否成功调度删除请求；实际结果经 changeListener 刷新体现。
     */
    fun deleteMedia(localIdentifiers: List<String>): Boolean

    /**
     * 收藏/取消收藏（PHAssetChangeRequest 改 isFavorite，无系统确认窗）。
     * 返回是否成功提交变更；实际结果经 changeListener 刷新体现。
     * （Phase 6.2 chat favorite_media 工具用）
     */
    fun setFavorite(localIdentifier: String, favorite: Boolean): Boolean
}
