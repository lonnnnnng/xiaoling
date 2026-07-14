# 实现说明

## 技术栈

- Kotlin
- Jetpack Compose
- OkHttp
- Android Keystore
- Gradle Wrapper

包名：`com.longdev.endpointtester`

## 模块职责

| 模块 | 职责 |
|---|---|
| UI | 双 Tab 界面：“测试”页选择 Provider / 已勾选模型并对话，“管理”页维护 Provider、端点、密钥和可测试模型列表。 |
| ViewModel | 维护页面状态，做表单校验，调用网络层，保存配置。 |
| Network | 构造 OpenAI-compatible 请求，解析响应，分类错误。 |
| Storage | 保存多 Provider 配置，并为每个配置加密保存 API Key。 |
| Tests | 覆盖 URL 归一化、Responses 解析和 SSE 增量解析。 |

## 请求行为

### 获取模型

用户在“管理”页点击“获取模型”后：

1. 校验 `Base URL` 必须以 `http://` 或 `https://` 开头。
2. 归一化 API 根路径。
3. 请求 `GET <api-root>/models`。
4. 从 `data[].id`、`data[].name`、`models[]` 或字符串数组中提取模型名。
5. 上游模型列表展示为复选项，只有勾选后的模型保存为可测试模型并出现在“测试”页。

### 测试模型

用户在“测试”页选择 Provider 和模型，输入消息并发送后：

1. 校验 `Base URL`、已勾选模型和测试消息。
2. 按当前 API 模式请求 `POST <api-root>/chat/completions` 或 `POST <api-root>/responses`。
3. Chat Completions 模式发送测试 payload：

```json
{
  "model": "mock-model",
  "messages": [
    {
      "role": "user",
      "content": "请只回复 OK"
    }
  ],
  "temperature": 0,
  "top_p": 1,
  "max_tokens": 32768,
  "stream": false
}
```

4. Responses API 模式发送 `model`、`input`、`temperature`、`top_p`、`max_output_tokens` 和 `stream`。
5. 非流式响应从 `choices[0].message.content`、`choices[0].text`、`output_text` 或 `output[].content[].text` 提取文本。
6. SSE streaming 响应读取 `data:` 行，并聚合 Chat Completions `choices[].delta.content` 或 Responses `delta` 文本。
7. 显示响应内容、耗时和最终 endpoint。
8. `max_tokens` / `max_output_tokens` 固定为 `32768`，不在 UI 中暴露配置项。

## URL 归一化

用户可能填写：

- `https://api.example.com/v1`
- `https://api.example.com/v1/models`
- `https://api.example.com/v1/chat/completions`
- `https://api.example.com/v1/responses`

因此代码会先识别已知后缀，再回退到 API 根路径，避免生成：

```text
/chat/completions/chat/completions
```

## API Key 保存

API Key 按 Provider 配置加密保存：

- 密钥生成并保存在 Android Keystore。
- SharedPreferences 中的 Provider 配置只保存 IV 和密文。
- 使用 AES-GCM。
这个设计解决的是“避免明文落盘”，不是防止用户已解锁设备上的所有威胁模型。

## 错误分类

App 将网络和 HTTP 错误转成可读提示：

- `401` / `403`：鉴权失败
- `404`：端点不存在
- `429`：请求过多或额度限制
- DNS 失败
- TLS 失败
- 连接失败
- 超时
- 响应 JSON 结构不符合预期

## 当前限制

- SSE streaming 目前是聚合完成后展示，不做逐 token 实时 UI 刷新。
- 不提供模板入口；新增或编辑模型提供方时只填写名称、URL 和 API Key。
- 不做聊天历史。
