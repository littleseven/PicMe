#!/bin/bash
# DeepSeek v4 Function Calling 测试脚本 - 不使用 tool_choice，仅提供 tools
# 替换 YOUR_GATEWAY_TOKEN 为实际的网关令牌

# 从环境变量读取 Gateway Token，避免硬编码泄露
# 使用方式: GATEWAY_TOKEN=your_token ./test_deepseek_no_toolchoice.sh
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
        "content": "你是 PoLang 应用的智能助手。用户要求你操作 App 时，你必须使用工具调用（tool_calls）来执行操作。可用工具：\n- navigate_to(destination): 导航到指定页面\n- get_screen_info(): 获取当前屏幕信息\n- click(element_id): 点击元素\n- go_back(): 返回上一页\n- finish(message): 完成任务并回复用户\n\n当用户要求打开相机时，直接调用 navigate_to(destination=\"camera\")。"
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
          "description": "获取当前屏幕的UI层级树和控件信息",
          "parameters": {
            "type": "object",
            "properties": {},
            "additionalProperties": false
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "navigate_to",
          "description": "导航到指定页面",
          "parameters": {
            "type": "object",
            "properties": {
              "destination": {
                "type": "string",
                "enum": ["camera", "album", "editor", "settings", "debug"]
              }
            },
            "required": ["destination"],
            "additionalProperties": false
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "click",
          "description": "点击指定元素",
          "parameters": {
            "type": "object",
            "properties": {
              "element_id": {
                "type": "string"
              }
            },
            "required": ["element_id"],
            "additionalProperties": false
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "go_back",
          "description": "返回上一页",
          "parameters": {
            "type": "object",
            "properties": {},
            "additionalProperties": false
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "finish",
          "description": "完成任务并回复用户",
          "parameters": {
            "type": "object",
            "properties": {
              "message": {
                "type": "string"
              }
            },
            "required": ["message"],
            "additionalProperties": false
          }
        }
      }
    ]
  }' | python3 -m json.tool
