# Error Healer - 编译错误自动分类与修复

> **定位**：PoLang 编译错误自动分类与修复策略，将 Kotlin/Gradle 编译错误映射到标准化修复方案。
> **触发时机**：用户报告编译错误、构建失败或需要修复 Kotlin/Gradle 错误时自动启用。


## 设计目标

将编译失败后的**随机修复尝试**转变为**基于错误模式的定向修复**：

```
编译失败
    ↓
错误日志解析 → 模式匹配 → 修复策略选择 → 自动修复 → 验证
    ↓
如果失败 → 换策略（最多 2 次自愈）→ 超限上报备选方案
```

## 错误分类矩阵

### Class A: 语法错误 (Syntax Errors)

| 错误模式 | 正则匹配 | 修复策略 | 成功率 |
|----------|----------|----------|--------|
| 括号不匹配 | `expecting '\}'\|expecting '\)'` | 定位缺失括号位置，补全对应符号 | 95% |
| 引号未闭合 | `unclosed string\|unclosed character` | 补全引号或转义 | 98% |
| 关键字拼写 | `expecting 'fun'\|expecting 'val'\|expecting 'var'` | 检查关键字拼写，修正为正确关键字 | 90% |
| 缺少分号/逗号 | `expecting ','\|expecting ';'` | 在上一行末尾补全 | 85% |
| 多余符号 | `unexpected token` | 删除多余符号 | 80% |
| 文件结构错误 | `expecting top level declaration` | 检查类/函数是否位于正确层级 | 75% |

**修复流程**：
```
1. 提取错误行号和列号
2. 读取该行 ±3 行上下文
3. 分析括号/引号/关键字状态
4. 应用修复
5. 快速编译验证
```

### Class B: 类型系统错误 (Type System)

| 错误模式 | 正则匹配 | 修复策略 | 成功率 |
|----------|----------|----------|--------|
| 类型不匹配 | `Type mismatch:\s*required:\s*(\w+)\s*found:\s*(\w+)` | 提取 required/found 类型，添加类型转换或修改表达式 | 70% |
| 返回类型错误 | `Return type mismatch:\s*expected\s*(\w+)` | 修改返回值或函数签名 | 65% |
| 泛型参数错误 | `Type parameter.*is not within its bounds` | 检查泛型约束，添加/修改类型参数 | 60% |
| 类型推断失败 | `Type inference failed` | 显式声明变量类型 | 80% |
| Smart Cast 失败 | `Smart cast to .* is impossible` | 添加 `as?` 转换或使用 `when` 分支 | 85% |

**修复流程**：
```
1. 提取 required 和 found 类型
2. 分析类型关系（父子类？可转换？）
3. 策略选择：
   - 可隐式转换 → 确认表达式正确性
   - 需显式转换 → 添加 `.toXxx()` 或 `as`
   - 类型完全错误 → 修改表达式或目标类型
4. 快速编译验证
```

### Class C: 未解析引用 (Unresolved References)

| 错误模式 | 正则匹配 | 修复策略 | 成功率 |
|----------|----------|----------|--------|
| 未解析的类/接口 | `Unresolved reference: (\w+)` + 首字母大写 | 检查拼写 → 添加 import → 检查依赖模块 | 75% |
| 未解析的函数 | `Unresolved reference: (\w+)` + 后跟 `(` | 检查拼写 → 添加 import → 检查扩展函数 | 70% |
| 未解析的变量 | `Unresolved reference: (\w+)` + 小写开头 | 检查拼写 → 检查作用域 → 声明变量 | 65% |
| 未解析的属性 | `Unresolved reference: (\w+)` | 检查拼写 → 检查接收者类型 → 添加/修改属性 | 70% |

**修复流程**：
```
1. 提取未解析的符号名
2. 检查是否为拼写错误（与项目符号做模糊匹配）
3. 检查是否缺少 import：
   - 搜索项目中定义该符号的文件
   - 添加正确的 import 语句
4. 检查是否在正确的作用域内
5. 快速编译验证
```

**PoLang 特定导入规则**：
```kotlin
// beauty-engine 模块的公开 API
import com.mamba.picme.beauty.api.*              // 禁止（AGENTS.md 规则）
import com.mamba.picme.beauty.api.BeautySettings  // 正确
import com.mamba.picme.beauty.api.FaceData        // 正确

// 内部实现不应被外部引用
import com.mamba.picme.beauty.internal.*          // 错误（app 模块不应引用）
```

### Class D: 可见性与访问权限 (Visibility)

| 错误模式 | 正则匹配 | 修复策略 | 成功率 |
|----------|----------|----------|--------|
| private 不可访问 | `Cannot access '.*': it is private in` | 将目标改为 internal/public，或在外部添加公开接口 | 60% |
| internal 跨模块 | `Cannot access '.*': it is internal in` | 改为 public，或在同模块内使用 | 65% |
| protected 访问 | `Cannot access '.*': it is protected` | 通过子类访问，或改为 public | 70% |
| 模块边界 | `is invisible` | 检查 Gradle 依赖关系 + 可见性修饰符 | 55% |

**修复流程**：
```
1. 确定访问者和被访问者的模块关系
2. 检查 Gradle 依赖：访问者是否 depend on 被访问者模块
3. 策略选择：
   - 同模块 → 检查可见性修饰符
   - 跨模块但有依赖 → 提升被访问者可见性
   - 跨模块无依赖 → 添加 Gradle 依赖（需人工确认）
4. 快速编译验证
```

### Class E: 空安全错误 (Null Safety)

| 错误模式 | 正则匹配 | 修复策略 | 成功率 |
|----------|----------|----------|--------|
| 非空类型赋 null | `Null can not be a value of a non-null type` | 改为可空类型 `?` 或提供非空默认值 | 85% |
| 需安全调用 | `Only safe (?.) or non-null asserted (!!.) calls are allowed` | 根据语义选择 `?.` / `?:` / `!!` / `let` | 80% |
| 智能转换失效 | `Smart cast to .* is impossible` | 使用 `when` 分支、局部变量、或 `as?` | 75% |
| lateinit 未初始化 | `lateinit property .* has not been initialized` | 检查初始化时机，或改为可空类型 | 70% |
| 平台类型 | `Platform type declared` | 显式声明可空性 | 90% |

**修复策略优先级**：
```
1. 首选: `?.let { }` 或 `?.also { }`（安全作用域函数）
2. 次选: `?: defaultValue`（提供默认值）
3. 谨慎: `!!`（仅在 100% 确定非空时使用，需注释说明）
4. 重构: 将可空类型改为非空（调整数据源）
```

### Class F: 导入与规范 (Imports)

| 错误模式 | 正则匹配 | 修复策略 | 成功率 |
|----------|----------|----------|--------|
| 通配符导入 | `Wildcard import` | 替换为显式导入 | 95% |
| 未使用导入 | `unused import` | 删除未使用的 import | 98% |
| 导入顺序 | `Import ordering` | 按 ktlint 规则重排 | 90% |
| 重复导入 | `Duplicate import` | 删除重复项 | 99% |

**自动修复**：`./gradlew ktlintFormat`

### Class G: Gradle/构建错误 (Build System)

| 错误模式 | 正则匹配 | 修复策略 | 成功率 |
|----------|----------|----------|--------|
| 依赖冲突 | `Conflict.*dependency` | 统一版本号（查看 libs.versions.toml） | 60% |
| 模块未找到 | `Project with path ':.*' could not be found` | 检查 settings.gradle.kts + 路径拼写 | 75% |
| 插件错误 | `Plugin .* not found` | 检查 plugin 版本和仓库配置 | 70% |
| 资源冲突 | `Duplicate resources` | 检查 res/ 目录重复文件 | 65% |
| NDK/CMake | `CMake Error\|NDK not configured` | 检查 local.properties + NDK 路径 | 55% |

### Class H: PoLang 特定错误 (Project-Specific)

| 错误模式 | 来源 | 修复策略 | 成功率 |
|----------|------|----------|--------|
| Shader 编译失败 | `GL compile error\|Shader.*failed` | 检查 GLSL 语法、varying/uniform 一致性、精度修饰符 | 50% |
| EGL 上下文错误 | `EGL.*error\|eglMakeCurrent failed` | 检查 EGL 配置、上下文共享、线程绑定 | 40% |
| 纹理加载失败 | `glTexImage2D.*error\|texture.*failed` | 检查 Bitmap 格式、尺寸限制、内存 | 55% |
| 人脸检测初始化 | `FaceDetector.*init\|ONNX.*error` | 检查模型文件存在性、设备 NNAPI 支持 | 50% |
| I18N 缺失 | `strings.xml mismatch`（自定义检查）| 同步三个 strings.xml 文件 | 90% |

## 自愈策略决策树

```
编译失败
    ↓
读取错误日志（提取前 3 个错误）
    ↓
错误分类（Class A-H）
    ↓
第 1 次修复
    ├── Class A(语法) → 直接修复 → compileDebugKotlin 验证
    ├── Class B(类型) → 分析类型关系 → 添加转换/修改签名
    ├── Class C(引用) → 模糊匹配 → 添加 import / 修正拼写
    ├── Class D(可见性) → 检查模块边界 → 提升可见性
    ├── Class E(空安全) → 选择安全操作符 → 应用
    ├── Class F(导入) → ktlintFormat → 自动修复
    ├── Class G(Gradle) → 检查依赖/配置 → 修正
    └── Class H(特定) → 读取对应 Skill → 按规范修复
    ↓
第 2 次修复（如果第 1 次失败）
    ├── 同一错误 → 换修复策略（如类型转换改为修改签名）
    ├── 新错误（级联）→ 扩大修复范围
    └── 无法理解 → 读取相关 Skill 获取领域知识
    ↓
第 3 次失败（超限）
    → 停止自动修复
    → 生成诊断报告
    → 上报用户 + 2 个备选方案
```

## 修复效率优化

### 分层验证（减少编译等待）

```bash
# 第 1 层: 语法/格式检查（~2s）
./gradlew :app:ktlintCheck

# 第 2 层: Kotlin 编译到 class（~5-30s）
./gradlew :app:compileDebugKotlin

# 第 3 层: 完整 APK（~30-120s，仅最终验证）
./gradlew :app:assembleDebug
```

**规则**：每层失败后立即修复，不继续下一层。

### 快速失败日志解析

```bash
# 提取关键错误信息（前 3 个错误）
./gradlew :app:compileDebugKotlin 2>&1 | \
    grep -E "^e:\s+" | head -3 > /tmp/compile_errors.txt

# 提取文件和行号
./gradlew :app:compileDebugKotlin 2>&1 | \
    grep -E "\.kt:\d+:\d+" | head -3 > /tmp/error_locations.txt
```

### 并行修复尝试

当错误涉及多个独立文件时，可以并行修复：

```
错误 1: app/A.kt:45 (Class A-语法)
错误 2: app/B.kt:120 (Class C-引用)
错误 3: beauty-engine/C.kt:30 (Class E-空安全)
    ↓
并行修复 3 个文件
    ↓
统一验证
```

## 诊断报告模板

当自愈超限时，生成结构化诊断报告：

```markdown
## 编译错误诊断报告

### 原始错误
```
[错误日志前 3 条]
```

### 已尝试的修复
| 次数 | 策略 | 结果 |
|------|------|------|
| 1 | [策略描述] | 失败，新错误: [错误描述] |
| 2 | [策略描述] | 失败，新错误: [错误描述] |

### 错误分析
- **主要类别**: [Class X]
- **根本原因推测**: [AI 分析]
- **涉及文件**: [文件列表]

### 建议方案
**方案 A**（推荐）: [具体修复步骤]
**方案 B**（备选）: [绕开方案]

### 需要人工确认
- [ ] [确认问题 1]
- [ ] [确认问题 2]
```

## 与现有工具整合

| 工具 | 整合方式 |
|------|----------|
| `scripts/quick-compile.sh` | 每层编译失败后调用错误分类器 |
| `scripts/auto-dev-loop.sh` | 编码闭环中的自愈环节 |
| `scripts/ai-gate.sh` | 最终验证阶段的错误收集 |
| [av-gl-expert](/av-gl-expert) | Shader/EGL 相关错误时读取 |
| [coordinate-system-standard](/coordinate-system-standard) | 坐标相关错误时读取 |
| [mediapipe-landmark-mapping](/mediapipe-landmark-mapping) | 人脸关键点相关错误时读取 |
| [compose-ui-expert](/compose-ui-expert) | Compose UI 编译错误时读取 |
| [perf-optimizer](/perf-optimizer) | 性能相关构建问题时读取 |

## 关键指标

| 指标 | 基线 | 目标 |
|------|------|------|
| 语法错误自动修复率 | - | 90%+ |
| 引用错误自动修复率 | - | 75%+ |
| 空安全错误自动修复率 | - | 80%+ |
| 导入问题自动修复率 | - | 95%+ |
| 平均编译修复轮次 | 3-5 | ≤2 |
| 超限上报率 | - | <10% |

## 相关文件

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
