// 配置区（需修改）
const GATEWAY_URL = 'https://gateway.ai.cloudflare.com/v1/a7656feec717409a19fa5217f0f7b2f9/picme/compat/chat/completions'; // 替换为你的 AI Gateway 地址
const MODEL = 'deepseek/deepseek-chat'; // 模型名

export default {
  async fetch(request, env) {
    // 1. 只允许 POST 请求
    if (request.method !== 'POST') {
      return new Response('Method Not Allowed', { status: 405 });
    }

    // 2. 可选：简单鉴权（Demo 可删，生产建议留）
    const authToken = request.headers.get('X-App-Token');
    if (authToken !== 'demo_app_token_2025') {
      //return new Response('Unauthorized', { status: 401 });
    }

    // 3. 构建转发到 AI Gateway 的请求
    const body = await request.json();
    const payload = {
      model: MODEL,
      messages: body.messages || [],
      temperature: body.temperature || 0.7,
      max_tokens: body.max_tokens || 2048,
      stream: false // Demo 先关掉流式，简化处理
    };

    try {
      const response = await fetch(GATEWAY_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${env.DEEPSEEK_API_KEY}`, // 你的 DeepSeek Key
          'cf-aig-authorization': `Bearer ${env.CLOUDFLARE_AIG_TOKEN}` // AI Gateway Token
        },
        body: JSON.stringify(payload)
      });

      // 4. 将 AI Gateway 的响应直接返回给客户端
      return new Response(response.body, {
        status: response.status,
        headers: { 'Content-Type': 'application/json' }
      });
    } catch (error) {
      return new Response(JSON.stringify({ error: error.message }), { 
        status: 500,
        headers: { 'Content-Type': 'application/json' }
      });
    }
  }
};
