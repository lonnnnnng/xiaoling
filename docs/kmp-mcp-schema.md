# KMP MCP inputSchema codec 与参数校验

第 291 阶段第三片，继续第 7 点协议固化；前序为[可读证据](kmp-readable-evidence.md)和[context snapshot](kmp-context-snapshot.md)。本片下沉 descriptor、原有界参数 validator，以及可供未来 iOS 使用的严格 raw JSON codec。shared Agent contract v4、Room v44、发布版本 `0.1.22 (23)` 保持。

## Descriptor 与平台归属

`SharedMcpToolDescriptor` 保留 `name / description / inputSchemaJson / readOnlyHint / destructiveHint` 五字段及两个 false 默认值；Android 原类型为 typealias。data-class equality 仍包括完整 schema 字符串、说明与 hints。即使 schema 仅增加空白，也不能拿旧 descriptor 通过当前目录漂移门禁。

Android 原 tools/list page parser、名称 trim、说明截断、缺省 object schema、annotations coercion、cursor、同名后值覆盖/首次名称顺序、分页及目录限额均保留。schema 的 JSONObject 原序列化文本不改用 shared encoder，目录不重写。Resources/Prompts、JSON-RPC 外层 codec、MCP 注册表与 transport 没有下沉；此处不是完整 MCP 客户端。

服务器 hints 是元数据，不能生成 SAFE、审批、白名单或本地执行权限。原 Run 显式发现、配置指纹、当前 descriptor、启停/白名单、前台 Direct、目录 TTL、参数 32 KiB，以及调用前后 generation/取消检查继续独立生效。会话及网络边界见[注册表与 SSE](mcp-session-registry.md)。

## 一份算法与两类 JSON 入口

`SharedMcpJsonSchemaValidator` 只依赖 `SharedMcpJsonObject / SharedMcpJsonArray` 读取端口、Kotlin 基础类型和 Regex。Android adapter 原样委托 JSONObject/JSONArray 的解析、optString/optBoolean/optInt/optDouble/getString、键顺序与序列化文本，Number 原对象保留，JSONObject.NULL 映射 Kotlin null。原校验异常和 parser cause 保留；没有声称 Android 原文入口因迁移而严格化。

共享 `SharedMcpSchemaCodec` 用严格扫描和 kotlinx-serialization-json 1.9.0 提供独立 raw 入口：

| 入口 | 原文边界 |
| --- | --- |
| decodeSchema | 标准 JSON object，最多 512 KiB UTF-8 bytes |
| decodeArguments | 标准 JSON object，最多 32 KiB UTF-8 bytes |
| 两入口共用 | 严格 UTF-8；完整原文、解码后唯一键、嵌套最多 32；禁止方言、尾随正文、孤立 surrogate |
| 格式错误 | 固定 `MCP inputSchema/arguments 不是有效 JSON object`，无原文和 cause |

raw encoder 从读取端口的 wire 解码后输出共享树的紧凑 JSON；空白/转义可能规范化，字段顺序与数字词法由树保留。无 schemaVersion 字段；`SharedMcpSchemaContract.VERSION=1` 是本机有界子集/codec 版本，不改变 MCP 的协商版本。

raw 入口冻结解析后的只读树；Android compatibility 入口仍持有原平台对象。已解析对象无法追溯原始重复键/方言，也不自动获得 raw codec 的字节限额。Android Direct Agent 的参数容量仍由原 Registry 控制，transport 回复限额仍由原 client 控制；直接 validator 兼容 API 不新增容量闸门。读取端口是可信宿主 adapter，不能拿自定义端口代替不可信 raw 数据的语法检查。

portable required 项必须是 string；Android getString/coercion 沿本机 org.json 实现。portable 数字先 Long、其次 Double，整数限值/布尔等仍提供兼容读取；这不保证任意畸形数值或不同 org.json 版本的 coercion 完全相同。Android descriptor 运行时类名随 typealias 变为 shared 类，没有承诺独立二进制 SDK 或反射兼容。

## 固化的现有校验子集

| 类别 | 本片保留的行为 |
| --- | --- |
| object | required 检查键存在，显式 null 也算存在；properties 最多 100；min/maxProperties；只有 properties 为对象时才执行 additionalProperties=false |
| array | 最多 100 项；min/maxItems；items 只有对象形式才递归，数组形式继续忽略 |
| string | UTF-16 String.length 的 min/maxLength；Regex containsMatchIn；不升级为 code-point 长度或全串匹配 |
| number/integer | Number、有限 Double、integer 的取余、minimum/maximum；没有任意精度保证 |
| boolean/null | 原布尔类型与 null 判断，字符串布尔不充当参数值 |
| enum | 原子数字沿 Double 比较；对象/数组沿对应 JSON 端口的序列化 wire 比较 |
| allOf/anyOf/oneOf | 先 enum，再 allOf，再 anyOf/oneOf；两者同时存在时 anyOf 优先；成功后返回，原同层其他约束继续不执行 |
| 递归 | 实际访问的 schema 深度最多 8；raw JSON 语法深度 32 是另一个边界 |

缺少 type 时不推导类型；未知非空 type 和 type 数组拒绝。未知 schema 关键字保留但不执行，没有 `$ref`、format、exclusive bounds、uniqueItems 或完整 JSON Schema 支持。上述组合与 additionalProperties 行为是历史有界实现，不能推导标准符合性或把“校验通过”当成远端提交/验证成功。

复合 enum 刻意保留历史 wire 比较。portable codec 保留对象顺序及 `1.0 / 1 / 1e0` 词法；Android JSONObject 可能重排或规范化这些表示，因此同一复合 enum 在两种 parser 下可能得到不同接受结果。原子数字的 Double 比较也会在超出精确整数区间时合并数值。固定 common 向量不代表任意 schema 跨 parser 语义完全一致；Regex 仍由各平台 Kotlin 提供，未承诺所有方言特性对齐。

## 验证与能力矩阵

| 能力 | Android | iOS |
| --- | --- | --- |
| descriptor/有界参数 validator | shared 算法，原 JSON 兼容端口生产接入 | commonMain/Simulator 固定向量 |
| 严格 raw schema/arguments codec | shared 可用；原 client parser 保留 | commonMain/Simulator codec 测试 |
| MCP Session Registry、HTTP/SSE、配置密文、Run 权限 | 原生产实现与门禁 | 尚无 actual 或产品 MCP 入口 |

common 测试覆盖固定 wire、未知类型、重复键、Unicode、schema 512 KiB 精确 ASCII 边界、arguments 32 KiB 精确 ASCII/多字节/转义边界、有限整数、组合、递归/属性/数组限额和 wire enum。JVM/Redmi 共用兼容夹具，以原 JSONObject/JSONArray 序列化作独立参考，并复验 coercion、组合历史行为、原 schema 文本/说明/hints 漂移，以及非法参数远端调用零次、合法参数仅一次。

既有 Session/SSE/HTTP/Resources/Prompts 与 Run Registry 过滤回归使用内存配置和 loopback socket；没有读取正式配置或调用真实 Provider/公网 MCP。实际计数、构建、Redmi、语料与保护审计见[验证报告](verification-report.md)。本片不交付 iOS actual，下一片为 Remote Channel wire，再继续完整 Tool result codec 与平台 actual。

官方正文：[MCP Tools 2025-03-26](https://modelcontextprotocol.io/specification/2025-03-26/server/tools) 将 inputSchema 定义为工具参数 JSON Schema，并明确 annotations 不可信；[Kotlin JsonPrimitive](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-primitive/) 定义 string/number/boolean/null 与内容访问。抓取页的部分代码样例存在排版重复，本片只引用字段与安全说明正文，不采用损坏样例作为 JSON golden。
