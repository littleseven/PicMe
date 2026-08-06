# Chat 页 AI 优化抽卡体验设计

> **状态**：设计稿（2026-08-06），待实现
> **范围**：把编辑器已落地的「AI 优化抽卡闭环」（best-of-N + NIMA 评分守卫）延伸到 chat 页——chat 内 AI 优化指令从「固定预设单发、不可挑选」升级为「对话内候选卡条 + 换一组 + 就用这张」。仅覆盖 AI 优化指令，不覆盖参数调整等其他图像编辑指令。
> **关联文档**：`docs/superpowers/specs/2026-08-06-ai-optimize-gacha-design.md`（编辑器抽卡闭环，domain 引擎来源）、`docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md`、`docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`

---

## 1. 背景与问题

编辑器侧抽卡已落地（`optimize/gacha/` 引擎 + `GachaCandidateBar` 对比模式 + 换一组 + 反馈落库）。

chat 页现状（`app/.../features/chat/ChatImageRenderer.kt`）：

- 用户在 chat 里说「优化这张照片」→ LLM tool_call → `ChatImageRenderer.aiOptimize()` → `optimizeUseCase.optimize(imageUri)` **固定预设单发** → 2048px 渲染 → 落 `ChatImageStore` 私有缓存 → 结果图作为一条 `agent_image` 消息出现在对话里
- 一次给值、不可挑选——正是编辑器抽卡要解决的「AI 给的调节值大概率退化」问题，chat 路径完全没有覆盖

**目标**：chat 内 AI 优化走 `optimizeWithGacha()` 抽卡闭环，结果以**对话内候选卡条**呈现，用户「换一组」重抽、点卡预览、显式「就用这张」确认；确认后卡条折叠为普通结果图消息，行为与现有 chat 结果图一致（私有缓存、预览页主动保存进相册）。

**关键前提（均已验证存在）**：

- `AiOptimizeUseCase.optimizeWithGacha(imageUri, baseRecipe, exclude)`（`app/.../domain/usecase/AiOptimizeUseCase.kt:120`）：三分支 `GachaResult.Selected / KeepOriginal / Unavailable`，返回 `GachaOutcome`（含最优卡 recipe、场景说明、`usedFingerprints` 去重指纹）；`auto` 落库已在 usecase 内完成
- `OptimizeFeedbackLogger`：`user` / `dismiss` 由 UI 层落库（编辑器已有先例）
- `ChatMessageEntity`（`app/.../data/local/ChatMessageEntity.kt`）：`type` 为自由字符串 + `metadata` JSON 扩展字段——**新增消息类型无需 Room Migration**
- `ChatImageStore.writeResult(sessionId, bitmap, mime)`：任意 Bitmap 落私有缓存返回 file:// 路径，候选缩略图与结果图同一套回收/对账机制（LRU cap、`reconcileColdStart`、`evictForSession`）
- `ChatEditStateHolder`：按 sessionId 维护当前编辑 Recipe，多轮 delta 调整的基础

## 2. 方案选型记录

| 方案 | 描述 | 结论 |
|------|------|------|
| **A. 复用 GachaEngine + 新增候选卡组消息类型** | chat 消息流新增「候选卡组」消息负载，采样/渲染/评分全复用 `optimize/gacha/` domain 引擎，chat 侧只加消息类型 + 卡条 UI + 编排接线 | ✅ 选定 |
| B. 跳编辑器对比模式 | chat 触发后跳 PhotoEditor 的 gacha 对比模式，选完返回 | ❌ 违背 chat 内闭环初衷（`ChatImageRenderer` 的存在就是为了不离开 chat 页） |
| C. 临时 UI 状态，不落消息 | 卡条只活在 ViewModel 内存态 | ❌ 会话重进/进程重建后卡条消失，交互状态不可恢复；且无法落库 dismiss |

用户已确认的交互决策（2026-08-06）：
- 覆盖范围：**仅 AI 优化指令**（`adjustImage` 等参数调整指令不走抽卡——参数是 LLM 按用户显式意图给的，多候选无意义）
- 呈现形态：**对话内候选卡条**（类比 `MediaResultsCarousel` 的横向滚动态势）
- 确认方式：**显式「就用这张」按钮**（点选只切换预览高亮，不确认）

## 3. 架构与组件

domain 层（`optimize/gacha/`）**零改动**。chat 侧改动：

| 组件 | 职责 | 依赖 | 可测性 |
|------|------|------|--------|
| `ChatOptimizeGachaController`（新，`features/chat/`） | 编排：调 `optimizeWithGacha` → 候选缩略图经 `ChatImageStore` 落盘 → 构造/更新候选卡组消息；处理换一组/点选/确认/废弃 | AiOptimizeUseCase、ChatImageStore、OptimizeFeedbackLogger | mock 三依赖 |
| `OptimizeCandidateGroup`（新，消息负载） | 候选卡组消息的 metadata 模型（见 §4），`toJson/fromJson` 双向 | 无（org.json，与现有 metadata 解析一致） | 纯 JVM 单测 |
| `GachaCandidateStrip`（新，`features/chat/components/`） | 候选卡条 Composable：横向卡列表（缩略图 + 方向标签 + 推荐徽标）+ 「换一组」+「就用这张」 | 无（纯 UI） | 截图/真机验证 |
| `ChatImageRenderer.aiOptimize`（改） | 改走 `optimizeWithGacha`，三分支分流 | AiOptimizeUseCase | 现有测试适配 |
| `ChatViewModel`（改） | 挂接 controller：发卡组消息、换一组/确认/废弃回调、确认后写 `ChatEditStateHolder` | controller | mock controller |

### 3.1 触发链路与三分支

```
chat 内 AI 优化 tool_call
  → ChatOptimizeGachaController.draw(imageUri, sessionId, exclude=∅)
  → optimizeUseCase.optimizeWithGacha(imageUri)
  ├─ Selected      → 候选缩略图 ×4 经 ChatImageStore 落盘
  │                  → 发 type=optimize_candidates 消息（state=pending，recommendedIndex=NIMA 最优卡）
  │                  → 文案：场景说明 explanation
  ├─ KeepOriginal  → 同样发卡组消息（recommendedIndex=-1，不预选）
  │                  → 文案："AI 认为原图已很好，仍可试看候选"
  └─ Unavailable   → 退回现有 optimize() 单发路径（发 agent_image 结果消息，行为与今天完全一致）
```

### 3.2 候选卡条交互

```
卡条消息（state=pending）：
  点某卡        → 高亮切换 + 点卡看大图预览（复用现有图片预览页，不确认）
  「换一组」    → controller.reroll(messageId)：usedFingerprints 回传 exclude 重抽
                → 新缩略图落盘 + 更新该条消息（candidates/recommendedIndex/usedFingerprints 替换，
                  旧缩略图文件由 ChatImageStore LRU 自然回收）
  「就用这张」  → controller.confirm(messageId, candidateIndex)：
                选中卡 preset → EditRecipe → 2048px renderRecipe 全尺寸渲染 → 落盘
                → 该条消息改写为 type=agent_image 结果消息（折叠，与现有结果消息一致）
                → 选中 recipe 写入 ChatEditStateHolder（后续「再亮一点」基于它继续）
                → feedbackLogger.log(source=user)
废弃          → 卡条 pending 状态下：用户发送新消息 / 切换会话 / 清空对话 / 进程被杀后未恢复
                → feedbackLogger.log(source=dismiss)（能拦截到的前两种场景）
```

- 「就用这张」按钮在**有选中卡时可用**：`Selected` 分支默认选中 NIMA 最优卡（按钮立即可用）；`KeepOriginal` 分支不预选，按钮初始禁用，用户点选某卡后可用
- 换一组期间卡条显示局部 loading，不阻塞对话流其他操作
- 所有文案新增字符串资源，三语同步（[I18N] 红线）

## 4. 消息负载（OptimizeCandidateGroup）

新消息类型 `type = "optimize_candidates"`，`content` 存展示文案（场景说明 / KeepOriginal 提示），`metadata` JSON：

```json
{
  "state": "pending",              // pending（卡条可交互）；确认后整条消息改写为 agent_image，无终态残留
  "sourceImageUri": "content://...", // 原图
  "scene": "GENERAL",
  "recommendedIndex": 1,           // NIMA 最优卡；-1 = KeepOriginal 不预选
  "candidates": [
    {
      "direction": "base",          // 方向标签（base/clarity/warm/...）
      "thumbPath": "file:///.../chat_edit_cache/xxx.jpg",  // ChatImageStore 落盘的 512px 候选图
      "nimaScore": 6.2,             // null = 未评分（护栏淘汰/推理失败）
      "rejected": false
    }
  ],
  "usedFingerprints": ["..."],      // 「换一组」回传 exclude 去重
  "drawIndex": 1                    // 第几组（换一组 +1，落库/日志用）
}
```

- 候选卡的完整 `OptimizePreset` **不进消息**（JSON 体积大且无展示必要），但「就用这张」确认时需要选中卡的 preset 生成全尺寸 recipe。**处理**：卡条 pending 期间候选 preset 保存在 controller 的内存态（messageId → List\<OptimizeCandidate\>），消息只存展示数据；进程重建后若内存态丢失，卡条降级为**只读展示**（可看图，换一组/确认按钮隐藏，文案提示「该组候选已过期」），不落库 dismiss（无法确认也不算主动放弃）
- 消息改写（confirm/reroll）走现有消息更新路径（Room `chat_messages` 按 id 更新 content/metadata）

## 5. 确认语义与多轮衔接

- 确认后选中 recipe 写入 `ChatEditStateHolder`（`update(sessionId, recipe)`），后续多轮 delta 调整（「再亮一点」）基于选中卡继续——与编辑器「应用后入历史」语义对齐
- 结果图不落相册：与现有 chat 行为一致，用户在预览页主动保存（`SaveChatEditResultUseCase` 链路不动）
- v1 不支持从已折叠的结果图回看卡条（YAGNI）

## 6. 反馈落库

复用 `OptimizeFeedbackLogger`，无需改 Room schema：

| 时点 | source | 说明 |
|------|--------|------|
| 每组生成时（NIMA 自动选优） | `auto` | 已在 `AiOptimizeUseCase.optimizeWithGacha` 内完成，chat 零改动 |
| 「就用这张」 | `user` | controller.confirm 落库 |
| 废弃（pending 卡条被新消息/切会话打断） | `dismiss` | controller 拦截点落库；进程被杀无法拦截，接受遗漏 |

「换一组」本身不落新行（新组的 `auto` 由 usecase 落），与编辑器行为一致。

## 7. 性能预算

| 阶段 | 预算 | 说明 |
|------|------|------|
| 抽卡端到端（采样+渲染×4+NIMA×5） | P50 < 2.5s | 沿用编辑器抽卡预算（512px 小图）；chat 异步场景，tool_call 本身已有 LLM 网络延迟，可接受 |
| 候选缩略图落盘 ×4 | < 200ms | JPEG 512px 写私有缓存 |
| 确认时 2048px 全尺寸渲染 | 与现有 aiOptimize 相同 | 复用 `renderRecipe` 路径（含 bitmapCache） |

磁盘：每组 4 张 512px JPEG（约 100~200KB/张），纳入 `ChatImageStore` 200MB LRU cap，不新增配额。

## 8. 错误处理与降级

| 场景 | 处理 |
|------|------|
| NIMA 模型未下载 | `Unavailable` → 退回固定预设单发（现有行为） |
| 单卡渲染失败 | gacha 引擎内丢弃该卡；有效卡 <2 → `Unavailable` |
| 缩略图落盘失败（个别） | 该卡展示占位图，不影响其余卡 |
| 落盘全失败 | 退回 `Unavailable` 单发路径 |
| 确认时全尺寸渲染失败 | 卡条保持 pending + toast 错误，可重试 |
| 进程重建后 controller 内存态丢失 | 卡条降级只读（见 §4），不崩溃、不误确认 |
| 候选缩略图被 LRU 回收 | 卡条对应卡位显示占位图；确认时若选中卡 preset 仍在内存态则不受影响（全尺寸渲染从原图重新渲染，不依赖缩略图） |

## 9. 测试计划

- **单测（纯 JVM）**：
  - `OptimizeCandidateGroup`：toJson/fromJson 往返、缺失字段容错、recommendedIndex=-1
  - `ChatOptimizeGachaController`（mock usecase/store/logger）：三分支消息构造、换一组 fingerprint 回传与消息更新、确认后消息改写 + `ChatEditStateHolder` 写入 + user 落库、废弃 dismiss 落库、内存态丢失降级
- **真机闭环**：编译 → 安装 → chat 发图 → 「优化一下」→ 验证卡条出现/换一组去重/点卡预览/就用这张折叠为结果图/KeepOriginal 文案/NIMA 未下载退回单发；进程重建后卡条只读降级

## 10. 范围边界（v1 不做）

- 不覆盖 `adjustImage` 等参数调整指令（参数是用户显式意图，多候选无意义）
- 不做结果图回看卡条（确认即折叠）
- 不做个性化学习（只落库，Phase 2 与编辑器抽卡共用 `optimize_feedback` 数据）
- 不改动 domain 层 gacha 引擎、不改 Room schema
- 不做批量场景的 chat 抽卡（chat 优化本就是单图交互）

## 11. 改动清单（预估）

| 位置 | 改动 |
|------|------|
| `features/chat/ChatOptimizeGachaController.kt` | 新增编排器 + `OptimizeCandidateGroup` 消息负载 |
| `features/chat/components/GachaCandidateStrip.kt` | 新增候选卡条 Composable |
| `features/chat/ChatImageRenderer.kt` | `aiOptimize` 改走抽卡（或拆出由 controller 调用） |
| `features/chat/ChatViewModel.kt` + `ChatScreen.kt` | 挂接卡条消息渲染与换一组/确认/废弃回调 |
| `di/AppContainer.kt` | 组装 controller 依赖 |
| `res/values{,-zh,-zh-rCN,-zh-rTW}/strings.xml` | 新文案三语同步 |
| `app/src/test/.../features/chat/` | controller + 消息负载单测 |
| `docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md` | 实现后补「chat 抽卡」章节链接回本 spec |
| `app/.../features/chat/`（AGENTS.md 如有） | 同步消息类型清单 |
