# Pexels API 测试图下载设计（图片下载页改版）

> **状态**：设计稿（2026-08-06），待实现
> **范围**：Debug 页「数据生成」区改版为双 Tab——「批量生成」（现有抓取功能原样保留）+「Pexels 图库」（新增）。Pexels 图库通过 Pexels 官方 API 浏览/搜索高质量图片，支持多选下载与批量下载，产物纳入现有测试数据体系。
> **关联文档**：`app/src/main/java/com/mamba/picme/features/debug/AGENTS.md`（调试模块规范）

---

## 1. 背景与问题

现有图片下载页（Debug 页数据生成区，`SampleDataGenerator`）靠抓百度图片搜索间接获取 pexels/unsplash 等站点的图片：

- 抓取链路脆弱（反爬、页面结构变化），命中率低
- 图片质量不可控，无法满足人脸检测/美颜/打标等测试对清晰度的要求
- Pexels 提供官方免费 API（200 次/小时、20,000 次/月），搜索/精选接口稳定，多尺寸可选

**目标**：接入 Pexels 官方 API 作为高质量测试图来源，对下载页做双 Tab 改版；旧抓取功能保留不动。

## 2. 方案选型记录

| 方案 | 描述 | 结论 |
|------|------|------|
| **A. Debug 模块内自包含** | 新增 `features/debug/pexels/` 子包，双 Tab 并存，下载复用现有存图链路 | ✅ 已选定 |
| B. 抽象多图源 TestImageSource 框架 | 百度抓取与 Pexels 统一为插件接口 | ❌ 过度设计（YAGNI），不动稳定代码 |
| C. 经自家 server 代理 Pexels API | Key 放服务端 | ❌ debug-only 工具，无需服务端改动 |

已确认的关键决策（用户逐项确认）：

1. **并存而非替换**：旧抓取逻辑原样保留，Pexels 作为新 Tab
2. **API Key 页面输入 + 本地保存**：独立 SharedPreferences，不入库、可随时修改
3. **功能形态**：浏览挑选下载 + 关键词批量下载，两者都要
4. **测试数据体系**：复用现有链路（`TEST_PEXELS_` 前缀 + `Pictures/PoLang` + MediaRepository + 随机拍摄日期），可被现有「清除测试数据」清理
5. **下载尺寸**：`large2x`（长边约 1880px），兼顾清晰度与流量
6. **UI 布局**：布局 A——搜索栏 + 网格 + 底部固定操作栏（已选计数 + 下载所选 + 批量下载）

## 3. 架构与组件

新增包 `app/src/main/java/com/mamba/picme/features/debug/pexels/`：

| 组件 | 职责 | 依赖 | 可测性 |
|------|------|------|--------|
| `PexelsModels.kt` | Moshi `@JsonClass` 数据模型：`PexelsPhoto(id, width, height, photographer, alt, src)`、`PexelsSrc(large2x, medium, small, portrait)`、`PexelsSearchResponse(photos, page, perPage, totalResults)` | Moshi | 纯数据类 |
| `PexelsApi.kt` | Retrofit 接口：`GET /v1/search`、`GET /v1/curated`；Key 以 `@Header("Authorization")` 逐请求传入 | Retrofit/OkHttp | MockWebServer 或 fake |
| `PexelsKeyStore.kt` | 独立 SharedPreferences（`debug_pexels_prefs`）读写 API Key | Context | Robolectric/fake |
| `PexelsViewModel.kt` | 状态机 + 动作编排（见 3.1） | Api、KeyStore、SampleDataGenerator | 单测重点 |
| `PexelsSection.kt` | Compose UI（见第 4 节） | ViewModel、Coil | 手动验证 |

改动现有文件：

- `DebugScreen.kt`：数据生成区改为双 Tab（`TabRow`：「批量生成」/「Pexels 图库」），现有内容移入 Tab 1
- `SampleDataGenerator.kt`：新增公开方法 `savePexelsPhoto(context, repository, photoUrl, fileName): Boolean`——复用 `downloadAndValidateImage` / `saveTestImageToAlbum` / MediaAsset 插入，写日志到现有 `logs` 通道；不改任何现有方法行为

**技术选型**：Retrofit + Moshi + Coil + OkHttp 全部为现有依赖，零新增库。Retrofit 实例为 pexels 包内 lazy 单例（base URL `https://api.pexels.com/`）。

**合规**：页面底部加「Photos provided by Pexels」署名文字（Pexels API 使用条款要求）。

### 3.1 状态机

```kotlin
sealed interface PexelsUiState {
    data object NoKey : PexelsUiState                     // 未配置 API Key
    data object Idle : PexelsUiState                      // 已配 Key，未加载
    data object Loading : PexelsUiState                   // 首页加载中
    data class Ready(
        val photos: List<PexelsPhoto>,
        val selectedIds: Set<Long>,
        val page: Int,
        val endReached: Boolean,
        val loadingMore: Boolean,
        val downloading: Boolean,
        val downloadProgress: String                      // 如 "5/20"
    ) : PexelsUiState
    data class Error(val message: String, val retryable: Boolean) : PexelsUiState
}
```

动作：`saveKey(key)`、`loadCurated()`、`search(query)`、`loadMore()`、`toggleSelect(id)`、`downloadSelected()`、`downloadBatch(count)`。

### 3.2 数据流

1. 进入 Tab：`PexelsKeyStore` 无 Key → `NoKey` 态（输入框 + 「前往 pexels.com/api 申请」链接）；有 Key → 自动 `loadCurated()`
2. 浏览：`curated`（per_page=30）或 `search(query)`；滚动到底自动 `loadMore` 翻页；`photos.isEmpty()` 时 `endReached=true`
3. 下载（多选或批量 N 张，默认 N=20）→ 逐张调 `SampleDataGenerator.savePexelsPhoto()`。批量下载从当前展示列表（精选或搜索结果）顺序取图；已加载不足 N 张时自动翻页补足，直到取够或 `endReached`：
   - 下载 `src.large2x` → 现有校验（≥5KB、可解码、内容有效）
   - 随机拍摄日期（近 180 天内），文件名 `TEST_PEXELS_{photoId}_{timestamp}.jpg`
   - 存 `Pictures/PoLang`（MediaStore）→ 插 MediaRepository（`source = "pexels"`）
   - 并发沿用 `Semaphore(2)`，日志写入现有 `logs` 通道
4. 进度反馈：底部操作栏显示 `downloadProgress` 文案 + Snackbar 完成提示

## 4. UI 布局（布局 A）

```
┌──────────────────────────────┐
│ [Tab: 批量生成 | Pexels 图库] │
├──────────────────────────────┤
│ API Key 状态条（已配置 ✎ /    │
│   未配置时显示输入框+申请链接）│
│ [搜索关键词________] [搜索]   │
│ ┌────┬────┬────┐             │
│ │ ✓图│ 图 │ ✓图│  3 列网格    │
│ ├────┼────┼────┤  Coil 加载   │
│ │ 图 │ 图 │ ✓图│  src.medium  │
│ └────┴────┴────┘  点击勾选    │
│ Photos provided by Pexels    │
├──────────────────────────────┤
│ 已选 3 张 │ [下载所选] [批量20]│
└──────────────────────────────┘
```

- 网格缩略图用 `src.medium`，Coil 加载
- 选中态：右上角勾选标记 + 半透明遮罩
- `NoKey` 态：Key 输入框 + 保存按钮 + pexels.com/api 链接（`LocalUriHandler` 打开）
- 批量下载张数：默认 20，可选 10/20/50（下拉）

## 5. 错误处理

| 场景 | 行为 |
|------|------|
| 401 Unauthorized | 提示 Key 无效，回 `NoKey` 态可重输 |
| 429 限流（200 次/小时） | 提示额度耗尽，终止当次加载/下载 |
| 网络失败 | `Error(retryable=true)` + Snackbar 重试 |
| 单张下载失败 | 沿用现有重试 2 次 + 指数退避；失败跳过不阻塞队列，最终汇总成功数 |
| 空搜索结果 | 网格区空态文案「无结果」 |

## 6. 红线与约束核对

- **[DEV_ONLY]**：全部代码在 `features/debug/`，沿用现有 debug 可见性机制
- **[PRIVACY]**：仅向 `api.pexels.com` 发送关键词文本与 Key；不上传任何用户数据 ✅
- **[PERF]**：并发 `Semaphore(2)`，网络超时 10s（沿用现有常量）✅
- **[I18N]**：全部 UI 文案进 strings.xml，values / values-zh / values-zh-rCN 三语同步 ✅
- **API Key 安全**：仅存本地 SharedPreferences，不进 git、不进日志 ✅

## 7. 测试计划

- **单测**：`PexelsViewModelTest`——状态机转换（NoKey→Idle→Loading→Ready/Error）、选择/取消、分页 `endReached` 边界、401→NoKey 回退
- **复用逻辑**：`savePexelsPhoto` 内部全为现有已验证逻辑，不新增单测
- **闭环验证**：`auto-dev-loop.sh`（编译→安装→真机）→ 搜索「雪山」→ 批量下载 20 张 → 相册确认 `TEST_PEXELS_` 前缀图片 → 「清除测试数据」确认可清理

## 8. 交付清单

- [ ] 新增 `features/debug/pexels/` 5 个文件
- [ ] `DebugScreen.kt` 双 Tab 改版
- [ ] `SampleDataGenerator.savePexelsPhoto()` 公开方法
- [ ] strings.xml 三语同步
- [ ] `PexelsViewModelTest` 单测通过
- [ ] 真机闭环验证通过
- [ ] 更新 `features/debug/AGENTS.md`（新增 Pexels 小节）
