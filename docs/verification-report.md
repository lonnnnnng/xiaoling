# 验证报告

验证日期：2026-07-14（北京时间）

## 环境

- macOS 原生环境 + zsh
- Android 真机：`wsvwypiz7xwslvl7`
- 设备型号：Redmi Note 8 Pro
- Android：14 / API 34
- App 包名：`com.longdev.endpointtester`

## 构建验证

执行命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug
```

结果：

```text
BUILD SUCCESSFUL
```

## 安装验证

执行命令：

```zsh
adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk
```

结果：

```text
Performing Streamed Install
Success
```

设备上已安装包：

```text
com.longdev.endpointtester
```

## 端到端验证

本地启动 OpenAI-compatible mock 服务，端口 `8765`，真机通过 adb reverse 访问：

```zsh
adb -s wsvwypiz7xwslvl7 reverse tcp:8765 tcp:8765
```

App 输入：

```text
Base URL: http://127.0.0.1:8765/v1
API Key: test-key
```

### 获取模型

mock 服务收到：

```text
GET /v1/models
```

App 行为：

- 模型列表请求成功。
- `Model ID` 自动填入 `mock-model`。

### 测试模型

mock 服务收到：

```text
POST /v1/chat/completions {"model":"mock-model","messages":[{"role":"user","content":"请只回复 OK"}],"temperature":0,"max_tokens":32,"stream":false}
```

App 显示：

```text
模型可用
OK
14 ms
http://127.0.0.1:8765/v1/chat/completions
```

## 日志检查

当前进程 logcat 中未命中：

- `FATAL EXCEPTION`
- `AndroidRuntime`
- `ANR`
- `crash`
- `ApiFailure`
- `StrictMode`
- `Exception`

error 级日志里只看到系统侧噪声：

- `ion ioctl ... Invalid argument`
- `ImeBackDispatcher: Ime callback not found`

没有看到 App 崩溃。

## 产物

| 文件 | 说明 | SHA-256 |
|---|---|---|
| `../outputs/endpoint-model-tester-debug.apk` | 已验证 debug APK | `59f7fc4c2ab8fe25ed86d7ecdda87d4e0b4a17e480b2065b271debd0d14fa212` |
| `../outputs/endpoint-tester-success.png` | 真机成功截图 | `74969b470b619d7cb887d1fe2678c044108f02d58ff9d2ac916e3f7ea5abd30b` |

## 清理状态

- 临时 mock 服务已停止。
- `adb reverse tcp:8765` 已清理。
- App 保留在真机上。

