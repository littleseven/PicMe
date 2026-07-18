#!/bin/bash
# DeepSeek v4 Function Calling 测试脚本 - 尝试 tool_choice 指定具体函数
# 替换 YOUR_GATEWAY_TOKEN 为实际的网关令牌

# 从环境变量读取 Gateway Token，避免硬编码泄露
# 使用方式: GATEWAY_TOKEN=your_token ./test_deepseek_tool_choice2.sh
GATEWAY_TOKEN="${GATEWAY_TOKEN:-}"

curl -s -X POST \
  "https://1412656811-f92agkf1y7.ap-guangzhou.tencentscf.com/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer gateway-auth" \
  -H "X-Gateway-Token: ${GATEWAY_TOKEN}" \
  -d '{
    "model": "deepseek-v4-flash",
    "temperature": 0.1,
    "messages": [
      {
        "role": "system",
        "content": "## ROLE\n你是 PoLang 应用的智能助手（AI Agent）。你通过调用工具与界面交互，完成用户的图片编辑、相册管理和其他任务。\n\n## 重要限制：纯文本 UI 感知（无多模态）\n\n当前对接的远程推理模型（DeepSeek）不支持图像/截图输入。你只能通过 get_screen_info 返回的 XML/JSON 层级树来感知 UI 状态，绝对不要请求或依赖截图、图片、屏幕捕获等视觉信息。\n\n## 可用工具\n\n- get_screen_info(): 获取当前屏幕的 UI 层级树（JSON 格式），包含所有可见元素的 class/id/text/bounds/clickable/scrollable 等信息。这是你感知 UI 的唯一途径。\n- click(x, y): 在指定坐标点击屏幕元素。坐标必须从 get_screen_info 返回的 bounds 中计算（取中心点）。\n- click_by_text(text): 按可见文本查找并点击元素。文本必须与 get_screen_info 返回的 text 字段匹配。\n- input_text(text): 在当前焦点输入框输入文字\n- scroll(direction, distance): 在当前可滚动区域上下滚动\n- navigate_to(destination): 导航到指定页面，destination 可选：camera(相机)|gallery(相册)|settings(设置)|debug(调试)\n- go_back(): 返回上一页\n- finish(summary): 任务完成时调用，传入任务总结\n\n## 执行协议（OpenAI Function Calling 标准）\n\n本系统通过 OpenAI Function Calling 机制支持工具调用。当需要执行工具时，直接发起函数调用，系统会自动解析并执行。\n\n**核心规则**：\n1. 当需要执行工具时，直接发起函数调用（function calling），系统会自动解析并执行\n2. 不要在回复文本中输出 JSON 格式的工具调用，也不要使用 think 标签\n3. 系统会自动执行工具，并将结果返回给你\n4. 你基于工具执行结果继续思考，决定下一步行动\n\n**绝对禁止**：\n- 在 content 字段中输出工具调用 JSON\n- 在 content 中写 \"我将调用...\" 等描述性文本\n- 使用 markdown 代码块包裹工具调用\n- 返回纯文本而不调用工具\n\n## 核心规则\n\n规则 1：区分任务类型，选择正确的响应方式。\n  **类型 A - 需要操作 App（工具调用）**：用户要求打开页面、点击按钮、调整参数、拍照等。\n    - 操作前必须先调用 get_screen_info 了解当前屏幕状态\n    - 然后基于屏幕信息调用相应工具\n    - 导航类操作（打开相机/相册/设置/调试）可直接调用 navigate_to，不需要先 get_screen_info\n  **类型 B - 纯知识问答/闲聊（自然语言回复）**：用户问\"牛顿是谁\"、\"你好\"、\"解释某个概念\"等。\n    - 直接通过 content 输出自然语言回复\n    - 不要调用任何工具\n    - 不要调用 get_screen_info，不要调用 finish\n\n规则 6：导航直接使用 navigate_to(destination)。\n  当用户要求打开相机/相册/设置/调试页面时，直接调用 navigate_to，不需要先 get_screen_info。\n\n## 回复格式（极其重要）\n\n**正确做法**：\n- 当需要执行工具时，直接发起函数调用，系统会自动解析\n- 当不需要工具时（如闲聊、知识问答），使用 content 输出自然语言，不要调用任何工具\n\n**错误做法（禁止）**：\n- 在 content 字段中输出工具调用 JSON\n- 在 content 中写 \"我将调用 navigate_to...\" 等描述性文本\n- 用 markdown 代码块包裹工具调用\n- 对于纯知识问答，错误地调用 get_screen_info 或其他工具\n- 对于纯知识问答，调用 finish 工具\n\n**示例说明**：\n- 用户说\"打开相机\" -> 系统会调用 navigate_to(destination=\"camera\") 工具\n- 用户说\"你好\" -> content: \"你好呀，我是小觅\"（不调用任何工具）\n- 用户说\"点击设置按钮\" -> 先调用 get_screen_info，找到设置按钮的 bounds，再调用 click(x, y)"
      },
      {
        "role": "user",
        "content": "打开相机"
      }
    ],
    "tools": [
      {
        "type": "function",
        "function": {
          "name": "get_screen_info",
          "description": "获取当前屏幕的 UI 层级树信息（纯文本描述），包含所有可见元素的 class/id/text/bounds/clickable/scrollable 等属性。这是感知 UI 状态的唯一途径。"
        }
      },
      {
        "type": "function",
        "function": {
          "name": "navigate_to",
          "description": "导航到指定页面。可选值：camera（相机）、gallery（相册）、settings（设置）、debug（调试）",
          "parameters": {
            "type": "object",
            "properties": {
              "destination": {
                "type": "string",
                "description": "目标页面: camera|gallery|settings|debug"
              }
            },
            "required": ["destination"]
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "click",
          "description": "点击屏幕上的元素。支持通过坐标(x,y)或文本(text)定位目标。",
          "parameters": {
            "type": "object",
            "properties": {
              "x": { "type": "integer", "description": "X coordinate" },
              "y": { "type": "integer", "description": "Y coordinate" },
              "text": { "type": "string", "description": "Click element by visible text" }
            }
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "go_back",
          "description": "返回上一页"
        }
      },
      {
        "type": "function",
        "function": {
          "name": "finish",
          "description": "任务完成时调用，传入任务总结",
          "parameters": {
            "type": "object",
            "properties": {
              "summary": { "type": "string", "description": "任务总结" }
            },
            "required": ["summary"]
          }
        }
      }
    ],
    "tool_choice": {
      "type": "function",
      "function": {
        "name": "navigate_to"
      }
    }
  }' | python3 -m json.tool
