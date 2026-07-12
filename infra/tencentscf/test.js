/**
 * 本地测试脚本
 *
 * 启动本地 Web Server 测试分层防御逻辑
 *
 * 运行方式：
 *   export CLOUDFLARE_AIG_TOKEN="your-token"
 *   export TOKENHUB_API_TOKEN="your-token"
 *   node test.js
 */

const http = require('http');

const TEST_PORT = 19000;
const TEST_HOST = '127.0.0.1';

/**
 * 发送 HTTP 请求到本地 Server
 */
function sendRequest(path, method, headers, body) {
    return new Promise(function(resolve, reject) {
        const postData = body ? JSON.stringify(body) : '';
        const options = {
            hostname: TEST_HOST,
            port: TEST_PORT,
            path: path,
            method: method,
            headers: Object.assign({
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(postData)
            }, headers || {})
        };

        const req = http.request(options, function(res) {
            let data = '';
            res.on('data', function(chunk) { data += chunk; });
            res.on('end', function() {
                resolve({
                    statusCode: res.statusCode,
                    headers: res.headers,
                    body: data
                });
            });
        });

        req.on('error', reject);
        if (postData) req.write(postData);
        req.end();
    });
}

// 测试 1：Cloudflare 新 modelId
async function testCloudflareAutoRoute() {
    console.log('\n========== Test 1: Cloudflare Auto Route (new id) ==========');
    const body = {
        model: 'deepseek/deepseek-chat',
        messages: [{ role: 'user', content: 'Hello' }],
        max_tokens: 1024
    };
    const result = await sendRequest('/v1/chat/completions', 'POST', { 'x-app-token': 'com-picme' }, body);
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body.substring(0, 500) + (result.body.length > 500 ? '...' : ''));
}

// 测试 2：Cloudflare 客户端旧 modelId 兼容（deepseek-chat）
async function testCloudflareLegacyAlias() {
    console.log('\n========== Test 2: Cloudflare Legacy Alias (deepseek-chat) ==========');
    const body = {
        model: 'deepseek-chat',
        messages: [{ role: 'user', content: 'Hello' }],
        max_tokens: 1024
    };
    const result = await sendRequest('/v1/chat/completions', 'POST', { 'x-app-token': 'com-picme' }, body);
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body.substring(0, 500) + (result.body.length > 500 ? '...' : ''));
}

// 测试 3：TokenHub kimi-k2.6
async function testTokenHubKimiAutoRoute() {
    console.log('\n========== Test 3: TokenHub kimi-k2.6 Auto Route ==========');
    const body = {
        model: 'kimi-k2.6',
        messages: [{ role: 'user', content: '你好' }],
        max_tokens: 1024
    };
    const result = await sendRequest('/v1/chat/completions', 'POST', { 'x-app-token': 'com-picme' }, body);
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body.substring(0, 500) + (result.body.length > 500 ? '...' : ''));
}

// 测试 4：TokenHub 新 modelId
async function testTokenHubDeepSeekAutoRoute() {
    console.log('\n========== Test 4: TokenHub deepseek-v4-flash-202605 ==========');
    const body = {
        model: 'deepseek-v4-flash-202605',
        messages: [{ role: 'user', content: 'Hello' }],
        max_tokens: 1024
    };
    const result = await sendRequest('/v1/chat/completions', 'POST', { 'x-app-token': 'com-picme' }, body);
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body.substring(0, 500) + (result.body.length > 500 ? '...' : ''));
}

// 测试 5：TokenHub 客户端旧 modelId 兼容（deepseek-v4-flash）
async function testTokenHubLegacyAlias() {
    console.log('\n========== Test 5: TokenHub Legacy Alias (deepseek-v4-flash) ==========');
    const body = {
        model: 'deepseek-v4-flash',
        messages: [{ role: 'user', content: 'Hello' }],
        max_tokens: 1024
    };
    const result = await sendRequest('/v1/chat/completions', 'POST', { 'x-app-token': 'com-picme' }, body);
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body.substring(0, 500) + (result.body.length > 500 ? '...' : ''));
}

// 测试 6：不支持的模型
async function testUnsupportedModel() {
    console.log('\n========== Test 6: Unsupported Model ==========');
    const body = {
        model: 'gpt-4',
        messages: [{ role: 'user', content: 'Hi' }]
    };
    const result = await sendRequest('/v1/chat/completions', 'POST', { 'x-app-token': 'com-picme' }, body);
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body);
}

// 测试 5：超大 max_tokens（触发大小限制）
async function testOversizedRequest() {
    console.log('\n========== Test 6: Oversized max_tokens ==========');
    const body = {
        model: 'kimi-k2.6',
        messages: [{ role: 'user', content: 'Hi' }],
        max_tokens: 99999
    };
    const result = await sendRequest(
        '/v1/chat/completions',
        'POST',
        { 'x-app-token': 'com-picme' },
        body
    );
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body);
}

// 测试 7：GET 请求（方法不允许）
async function testMethodNotAllowed() {
    console.log('\n========== Test 7: GET Request (Method Not Allowed) ==========');
    const result = await sendRequest(
        '/v1/chat/completions',
        'GET',
        { 'x-app-token': 'com-picme' },
        null
    );
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body);
}

// 测试 8：FORCE_PROVIDER 强制路由（模拟环境变量）
async function testForceProvider() {
    console.log('\n========== Test 8: FORCE_PROVIDER Override ==========');
    console.log('Note: Set FORCE_PROVIDER=tokenhub or FORCE_PROVIDER=cloudflare to test');
    console.log('Current FORCE_PROVIDER:', process.env.FORCE_PROVIDER || 'not set (auto-route)');
}

// 顺序执行测试
async function runTests() {
    console.log('=== Web Function Local Test (Layered Defense) ===');
    console.log('Server: http://' + TEST_HOST + ':' + TEST_PORT);

    try {
        await testCloudflareAutoRoute();
    } catch (e) {
        console.error('Test 1 failed:', e.message);
    }

    try {
        await testCloudflareLegacyAlias();
    } catch (e) {
        console.error('Test 2 failed:', e.message);
    }

    try {
        await testTokenHubKimiAutoRoute();
    } catch (e) {
        console.error('Test 3 failed:', e.message);
    }

    try {
        await testTokenHubDeepSeekAutoRoute();
    } catch (e) {
        console.error('Test 4 failed:', e.message);
    }

    try {
        await testTokenHubLegacyAlias();
    } catch (e) {
        console.error('Test 5 failed:', e.message);
    }

    try {
        await testUnsupportedModel();
    } catch (e) {
        console.error('Test 6 failed:', e.message);
    }

    try {
        await testOversizedRequest();
    } catch (e) {
        console.error('Test 7 failed:', e.message);
    }

    try {
        await testMethodNotAllowed();
    } catch (e) {
        console.error('Test 8 failed:', e.message);
    }

    try {
        await testForceProvider();
    } catch (e) {
        console.error('Test 9 failed:', e.message);
    }

    console.log('\n=== All Tests Completed ===');
}

// 启动测试
process.env.NODE_ENV = 'test';
process.env.PORT = TEST_PORT;

const { server, handleRequest } = require('./index');

// 手动启动测试服务器
server.listen(TEST_PORT, TEST_HOST, function() {
    console.log('[Test] Server listening on http://' + TEST_HOST + ':' + TEST_PORT);

    runTests().then(function() {
        console.log('\nShutting down test server...');
        server.close();
        process.exit(0);
    }).catch(function(err) {
        console.error('Test error:', err);
        server.close();
        process.exit(1);
    });
});
