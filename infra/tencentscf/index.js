/**
 * 腾讯云 SCF Web 函数 - AI Gateway 代理（分层防御版）
 *
 * 运行环境：Node.js 18.15（Web 函数类型）
 * 启动方式：scf_bootstrap 启动文件 → 监听 0.0.0.0:9000
 *
 * 统一入口：/v1/chat/completions
 * 路由策略（服务端透明）：
 * - 默认按 model 字段自动路由
 * - FORCE_PROVIDER 环境变量可强制指定后端（cloudflare|tokenhub）
 *
 * 安全设计：
 * - Cloudflare AI Gateway 代理：DeepSeek API Key 由 Cloudflare 网关维护
 * - 腾讯 TokenHub：API Token 由环境变量维护
 * - 第一层防护（腾讯云）：速率限制、请求大小限制、IP 日志
 * - 第二层防护（Cloudflare）：网关级配额管理、上游密钥保护
 */

const http = require('http');
const https = require('https');
const url = require('url');

// 配置区
const PORT = process.env.PORT || 9000;
const APP_TOKEN = process.env.APP_TOKEN || 'com-picme'; // 客户端鉴权 Token（环境变量可覆盖）

// Cloudflare AI Gateway 配置
const CLOUDFLARE_URL = 'https://gateway.ai.cloudflare.com/v1/a7656feec717409a19fa5217f0f7b2f9/picme/compat/chat/completions';
const CLOUDFLARE_MODEL = 'deepseek/deepseek-chat';

// 腾讯 TokenHub 配置
const TOKENHUB_URL = 'https://tokenhub.tencentmaas.com/v1/chat/completions';
const TOKENHUB_MODELS = {
    'deepseek-v4-flash': 'deepseek-v4-flash',
    'deepseek-v4-flash-202605': 'deepseek-v4-flash-202605',
    'kimi-k2.6': 'kimi-k2.6',
    'kimi-k2.7-code': 'kimi-k2.7-code'
};

// 统一入口路径（兼容已发布客户端的旧路径 /chat/completions）
const API_PATH = '/v1/chat/completions';
const LEGACY_PATH = '/chat/completions';
const SUPPORTED_PATHS = [API_PATH, LEGACY_PATH];

// 模型 -> 后端路由映射（客户端无感知）
// 支持客户端已发布的 modelId（含别名）
const MODEL_ROUTES = {
    // Cloudflare 路由
    'deepseek/deepseek-chat': 'cloudflare',
    'deepseek-chat': 'cloudflare',

    // TokenHub 路由
    'deepseek-v4-flash-202605': 'tokenhub',
    'deepseek-v4-flash': 'tokenhub',
    'kimi-k2.6': 'tokenhub',
    'kimi-k2.7-code': 'tokenhub'
};

// 客户端 modelId -> 上游真实 modelId 映射
const MODEL_ALIASES = {
    'deepseek-chat': 'deepseek/deepseek-chat',
    'deepseek-v4-flash': 'deepseek-v4-flash'
};

// FORCE_PROVIDER 环境变量：强制所有请求走指定后端（cloudflare|tokenhub）
const FORCE_PROVIDER = process.env.FORCE_PROVIDER || null;

// 速率限制配置
const RATE_LIMIT = {
    windowMs: 60 * 1000, // 1分钟窗口
    maxRequests: 20,      // 单 IP 每分钟最多 10 次
    maxTokens: 4096      // 单次请求最大 token 数
};

// 内存级请求日志：IP -> [timestamp, ...]
// 注意：SCF 无状态，函数实例回收后日志清零。生产环境建议用 Redis。
const requestLog = new Map();

/**
 * 检查速率限制
 * @param {string} ip - 客户端 IP
 * @returns {boolean} - 是否允许请求
 */
function checkRateLimit(ip) {
    const now = Date.now();
    const windowStart = now - RATE_LIMIT.windowMs;

    if (!requestLog.has(ip)) {
        requestLog.set(ip, [now]);
        return true;
    }

    const requests = requestLog.get(ip).filter(function(t) { return t > windowStart; });
    requests.push(now);
    requestLog.set(ip, requests);

    return requests.length <= RATE_LIMIT.maxRequests;
}

/**
 * 清理过期的请求日志（防止内存泄漏）
 */
function cleanExpiredLogs() {
    const now = Date.now();
    const windowStart = now - RATE_LIMIT.windowMs;
    requestLog.forEach(function(timestamps, ip) {
        const valid = timestamps.filter(function(t) { return t > windowStart; });
        if (valid.length === 0) {
            requestLog.delete(ip);
        } else {
            requestLog.set(ip, valid);
        }
    });
}

// 每 5 分钟清理一次过期日志
setInterval(cleanExpiredLogs, 5 * 60 * 1000);

/**
 * 解析请求体
 * @param {http.IncomingMessage} req
 * @returns {Promise<Object>}
 */
function parseBody(req) {
    return new Promise(function(resolve, reject) {
        let body = '';
        req.on('data', function(chunk) { body += chunk; });
        req.on('end', function() {
            try {
                resolve(body ? JSON.parse(body) : {});
            } catch (e) {
                resolve({});
            }
        });
        req.on('error', reject);
    });
}

/**
 * 发送 JSON 响应
 * @param {http.ServerResponse} res
 * @param {number} statusCode
 * @param {Object} data
 */
function sendJson(res, statusCode, data) {
    const json = JSON.stringify(data);
    res.writeHead(statusCode, {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(json),
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type,X-App-Token'
    });
    res.end(json);
}

/**
 * 主请求处理器
 */
async function handleRequest(req, res) {
    const clientIp = req.headers['x-forwarded-for'] ||
                     (req.connection && req.connection.remoteAddress) ||
                     'unknown';
    console.log('[Web] Request received, method=' + req.method +
                ', path=' + req.url + ', ip=' + clientIp);

    // 处理 CORS 预检请求
    if (req.method === 'OPTIONS') {
        res.writeHead(204, {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type,X-App-Token'
        });
        res.end();
        return;
    }

    // 1. 只允许 POST 请求
    if (req.method !== 'POST') {
        sendJson(res, 405, { error: 'Method Not Allowed' });
        return;
    }

    // 2. 第一层防护：速率限制
    if (!checkRateLimit(clientIp)) {
        console.warn('[Web] Rate limit exceeded, ip=' + clientIp);
        sendJson(res, 429, {
            error: 'Rate limit exceeded. Max ' + RATE_LIMIT.maxRequests +
                   ' requests per ' + (RATE_LIMIT.windowMs / 1000) + ' seconds.'
        });
        return;
    }

    // 3. 简单鉴权（生产环境建议开启）
    const authToken = req.headers['x-app-token'];
    if (authToken !== APP_TOKEN) {
        // 如需开启鉴权，取消下面注释
        // sendJson(res, 401, { error: 'Unauthorized' });
        // return;
    }

    // 4. 解析请求体
    const body = await parseBody(req);

    // 5. 请求大小限制（防盗刷大 token 请求）
    if (body.max_tokens && body.max_tokens > RATE_LIMIT.maxTokens) {
        sendJson(res, 400, {
            error: 'max_tokens exceeds limit of ' + RATE_LIMIT.maxTokens
        });
        return;
    }

    // 6. 统一入口校验（兼容旧路径）
    if (!SUPPORTED_PATHS.includes(req.url)) {
        sendJson(res, 404, { error: 'Not Found. Supported paths: ' + SUPPORTED_PATHS.join(', ') });
        return;
    }

    // 7. 路由决策（服务端透明）
    const provider = resolveProvider(body.model);
    if (!provider) {
        sendJson(res, 400, {
            error: 'Unsupported model: ' + body.model +
                   '. Supported models: ' + Object.keys(MODEL_ROUTES).join(', ')
        });
        return;
    }

    console.log('[Web] Route resolved, model=' + (body.model || 'default') +
                ', provider=' + provider + ', ip=' + clientIp);

    try {
        if (provider === 'cloudflare') {
            await forwardToCloudflare(req, res, body, clientIp);
        } else if (provider === 'tokenhub') {
            await forwardToTokenHub(req, res, body, clientIp);
        }
    } catch (error) {
        console.error('[Web] Request failed, ip=' + clientIp +
                      ', error=' + error.message);
        sendJson(res, 500, { error: error.message });
    }
}

/**
 * 根据模型 ID 解析后端提供商
 * 优先级：FORCE_PROVIDER 环境变量 > 模型映射表
 */
function resolveProvider(modelId) {
    if (FORCE_PROVIDER) {
        return FORCE_PROVIDER;
    }
    return MODEL_ROUTES[modelId] || null;
}

/**
 * 将客户端 modelId 解析为上游真实 modelId
 */
function resolveUpstreamModel(modelId, defaultModel) {
    const normalized = modelId || defaultModel;
    return MODEL_ALIASES[normalized] || normalized;
}

/**
 * 转发请求到 Cloudflare AI Gateway
 */
async function forwardToCloudflare(req, res, body, clientIp) {
    const payload = Object.assign({}, body);
    payload.model = resolveUpstreamModel(body.model, CLOUDFLARE_MODEL);
    payload.stream = false;

    const cloudflareAigToken = process.env.CLOUDFLARE_AIG_TOKEN;
    if (!cloudflareAigToken) {
        console.error('[Web] CLOUDFLARE_AIG_TOKEN not configured');
        sendJson(res, 500, {
            error: 'Server configuration error: CLOUDFLARE_AIG_TOKEN missing'
        });
        return;
    }

    console.log('[Web] Forwarding to Cloudflare AI Gateway, model=' + CLOUDFLARE_MODEL +
                ', ip=' + clientIp);

    const requestHeaders = {
        'Content-Type': 'application/json',
        'cf-aig-authorization': 'Bearer ' + cloudflareAigToken
    };

    const response = await sendHttpsRequest(CLOUDFLARE_URL, payload, requestHeaders);

    console.log('[Web] Cloudflare response status=' + response.statusCode +
                ', ip=' + clientIp);

    res.writeHead(response.statusCode, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type,X-App-Token'
    });
    res.end(response.body);
}

/**
 * 转发请求到腾讯 TokenHub
 */
async function forwardToTokenHub(req, res, body, clientIp) {
    const requestedModel = body.model || 'deepseek-v4-flash-202605';

    // 兼容客户端旧 modelId：先解析别名再校验
    const upstreamModel = resolveUpstreamModel(requestedModel, 'deepseek-v4-flash-202605');

    // 校验模型是否支持
    if (!TOKENHUB_MODELS[upstreamModel]) {
        sendJson(res, 400, {
            error: 'Unsupported model: ' + requestedModel +
                   '. Supported models: ' + Object.keys(TOKENHUB_MODELS).join(', ')
        });
        return;
    }

    const payload = Object.assign({}, body);
    payload.model = TOKENHUB_MODELS[upstreamModel];
    payload.stream = false;

    const tokenHubApiToken = process.env.TOKENHUB_API_TOKEN;
    if (!tokenHubApiToken) {
        console.error('[Web] TOKENHUB_API_TOKEN not configured');
        sendJson(res, 500, {
            error: 'Server configuration error: TOKENHUB_API_TOKEN missing'
        });
        return;
    }

    console.log('[Web] Forwarding to TokenHub, model=' + payload.model +
                ', ip=' + clientIp);

    const requestHeaders = {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + tokenHubApiToken
    };

    const response = await sendHttpsRequest(TOKENHUB_URL, payload, requestHeaders);

    console.log('[Web] TokenHub response status=' + response.statusCode +
                ', body=' + response.body +
                ', ip=' + clientIp);

    res.writeHead(response.statusCode, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type,X-App-Token'
    });
    res.end(response.body);
}

/**
 * 通用 HTTPS 请求发送
 */
function sendHttpsRequest(targetUrl, payload, requestHeaders) {
    return new Promise(function(resolve, reject) {
        const parsedUrl = url.parse(targetUrl);
        const postData = JSON.stringify(payload);

        const options = {
            hostname: parsedUrl.hostname,
            path: parsedUrl.path,
            method: 'POST',
            headers: Object.assign({
                'Content-Length': Buffer.byteLength(postData)
            }, requestHeaders)
        };

        const apiReq = https.request(options, function(apiRes) {
            let data = '';
            apiRes.on('data', function(chunk) { data += chunk; });
            apiRes.on('end', function() {
                resolve({
                    statusCode: apiRes.statusCode,
                    headers: apiRes.headers,
                    body: data
                });
            });
        });

        apiReq.on('error', function(err) {
            reject(err);
        });

        apiReq.write(postData);
        apiReq.end();
    });
}

// 创建 HTTP Server
const server = http.createServer(function(req, res) {
    handleRequest(req, res).catch(function(err) {
        console.error('[Web] Unhandled error:', err);
        sendJson(res, 500, { error: 'Internal Server Error' });
    });
});

// 监听 0.0.0.0:9000（腾讯云 Web 函数要求）
// 仅在非测试环境自动启动
if (process.env.NODE_ENV !== 'test') {
    server.listen(PORT, '0.0.0.0', function() {
        console.log('[Web] Server listening on http://0.0.0.0:' + PORT);
    });
}

module.exports = { server, handleRequest };
