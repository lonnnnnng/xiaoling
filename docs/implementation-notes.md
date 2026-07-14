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
| UI | 单屏配置和测试界面，显示输入框、开关、按钮和结果卡片。 |
| ViewModel | 维护页面状态，做表单校验，调用网络层，保存配置。 |
| Network | 构造 OpenAI-compatible 请求，解析响应，分类错误。 |
| Storage | 保存 Base URL、Model ID、Prompt、Headers，并加密保存 API Key。 |
| Tests | 覆盖 URL 归一化和自定义 Header 解析。 |

## 请求行为

### 获取模型

用户点击“获取模型”后：

1. 校验 `Base URL` 必须以 `http://` 或 `https://` 开头。
2. 归一化 API 根路径。
3. 请求 `GET <api-root>/models`。
4. 从 `data[].id`、`data[].name`、`models[]` 或字符串数组中提取模型名。
5. 如果当前没有手动填写模型，则自动选择第一个模型。

### 测试模型

用户点击“测试模型”后：

1. 校验 `Base URL`、`Model ID` 和测试消息。
2. 请求 `POST <api-root>/chat/completions`。
3. 发送最小测试 payload：

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
  "max_tokens": 32,
  "stream": false
}
```

4. 从 `choices[0].message.content`、`choices[0].text` 或 `output_text` 提取文本。
5. 显示响应内容、耗时和最终 endpoint。

## URL 归一化

用户可能填写：

- `https://api.example.com/v1`
- `https://api.example.com/v1/models`
- `https://api.example.com/v1/chat/completions`

因此代码会先识别已知后缀，再回退到 API 根路径，避免生成：

```text
/chat/completions/chat/completions
```

## API Key 保存

API Key 默认加密保存：

- 密钥生成并保存在 Android Keystore。
- SharedPreferences 只保存 IV 和密文。
- 使用 AES-GCM。
- 如果用户关闭“加密保存 API Key”，会清理已保存的密文。

这个设计解决的是“避免明文落盘”，不是防止用户已解锁设备上的所有威胁模型。

## 自定义 Headers

自定义 Header 采用每行一个：

```text
Header-Name: Header Value
```

自定义 Header 最后写入请求，允许覆盖默认 `Authorization: Bearer <api-key>`。这样 Azure、代理网关或非 Bearer 鉴权可以直接测试。

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

- 只测试非流式 Chat Completions。
- 不支持 Responses API。
- 不支持 SSE streaming。
- 不做多 Provider 配置保存。
- 不做模型参数高级配置。
- 不做聊天历史。

