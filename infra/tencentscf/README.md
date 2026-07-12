# PicMe AI Gateway - 腾讯云 SCF

腾讯云云函数（SCF）Web 函数代理服务，将端侧请求转发到 Cloudflare AI Gateway，隐藏 DeepSeek API Key。

采用**分层防御**架构：
- **第一层（腾讯云）**：速率限制、请求大小限制、IP 日志审计
- **第二层（Cloudflare）**：网关级配额管理、上游密钥保护

## 文件说明

| 文件 | 说明 |
|------|------|
| `index.js` | Web Server 入口，监听 0.0.0.0:9000 |
| `scf_bootstrap` | SCF 启动文件（Node.js 18.15 官方规范） |
| `package.json` | Node.js 项目配置 |
| `test.js` | 本地测试脚本 |

## 部署方式：控制台文件夹上传（Web 函数）

### 1. 准备部署包

```bash
cd tencentscf
npm install   # 如有依赖（当前无第三方依赖，可跳过）
```

### 2. 打包文件夹

```bash
zip -r picme-ai-gateway.zip index.js scf_bootstrap package.json
```

### 3. 腾讯云控制台上传

1. 登录 [腾讯云 SCF 控制台](https://console.cloud.tencent.com/scf/list)
2. 点击「新建」→「从头开始」
3. 基础配置：
   - **函数类型**：**Web 函数**（注意：不是事件函数）
   - **函数名称**：`picme-ai-gateway`
   - **地域**：选择靠近你的地域（如广州）
   - **运行环境**：`Node.js 18.15`
   - **时区**：Asia/Shanghai
4. 函数代码：
   - **提交方法**：本地上传文件夹
   - 选择 `tencentscf` 文件夹（或打包的 zip）
   - **执行方法**：无需填写（Web 函数通过 `scf_bootstrap` 启动）
5. 高级配置：
   - **内存**：128MB
   - **超时时间**：30秒
   - **环境变量**：
     - `CLOUDFLARE_AIG_TOKEN` = 你的 Cloudflare AIG Token（必需，网关认证）
     - `APP_TOKEN` = 客户端鉴权 Token（可选，默认 `com-picme`，建议自定义并同步到端侧 BuildConfig）
     - DeepSeek API Key 由 Cloudflare AI Gateway 维护，无需在腾讯云函数中配置
6. **Web 函数配置**（Web 函数特有）：
   - **访问路径**：`/chat/completions`
   - **鉴权类型**：免鉴权（或根据需求选择）
   - **启用 CORS**：是
   - **请求方法**：POST
7. 点击「完成」

> **注意**：`scf_bootstrap` 为 Web 函数启动文件，必须包含可执行权限（755/777）。系统会自动检测项目中的 `scf_bootstrap` 文件并以其为准启动服务。详见 [腾讯云官方文档 - 启动文件说明](https://cloud.tencent.com/document/product/583/56126)。

### 4. 获取访问地址

部署完成后，在「函数管理」→「函数 URL」标签页查看访问路径：
```
https://picme-ai-gateway-xxx.gz.tencentscfs.com/chat/completions
```

### 5. 端侧配置

将 Android 端的 baseUrl 改为上述地址：
```kotlin
val TENCENT_SCF_DEFAULT = RemoteModelConfig(
    modelId = "deepseek-chat",
    protocol = RemoteProtocol.OPENAI,
    baseUrl = "https://picme-ai-gateway-xxx.gz.tencentscfs.com/"
)
```

## 本地测试

```bash
# 设置环境变量（仅需 Cloudflare AIG Token）
export CLOUDFLARE_AIG_TOKEN="your-cloudflare-token"

# 运行测试
node test.js
```
