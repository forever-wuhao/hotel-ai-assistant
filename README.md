# 酒店 AI 客服与查询助手

基于 Spring AI 的只读酒店咨询系统：按城市和预算找酒店、查询日期房态、检索入住/退订政策、调用高德步行路线，并在页面展示本轮工具记录和资料来源。

在 [Ryan-wu-web/XieChengBigHomeworkNjupt](https://github.com/Ryan-wu-web/XieChengBigHomeworkNjupt) 的酒店管理项目上扩展。上游基线为 `01d00e84d5e3952837ec871de2887b173656b397`；原 Android、酒店管理页面及业务代码保留来源。本轮新增 Agent、RAG、日期库存查询、Web 咨询页及评测流程。

## 能力与实现

| 能力 | 实现 |
| --- | --- |
| 多步骤查询 | Spring AI `ChatModel` + 类型化 `FunctionToolCallback`；显式执行工具并回传观察结果，最多 6 轮、每轮最多 4 个工具 |
| 酒店筛选 | 参数化 SQL；仅查询已发布酒店，按城市和每晚预算上限过滤，不自动扩大预算 |
| 日期房态 | 逐晚核对 `room_inventory_daily`；退房日不计住宿；缺失任何一天返回 `UNKNOWN`，不拿总库存替代 |
| RAG | Markdown 按标题及窗口切片 → Embedding → `SimpleVectorStore` → 酒店 ID 过滤 → Top 4 片段与出处 |
| 多轮会话 | 服务端随机会话标识、最近 6 轮上下文、30 分钟闲置过期、同会话串行处理 |
| 可靠性 | 参数校验、工具白名单、短暂模型故障最多尝试 2 次、引用 ID 校验、失败不写入会话历史 |
| 可解释界面 | Vue 3 咨询页展示工具执行状态、来源片段、耗时和独立错误提示；支持刷新恢复本标签页历史 |

技术栈：Java 17、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus、MySQL/H2、Vue 3、TypeScript、Vite、JUnit 5、Playwright。

**默认是离线演示**：H2 中的酒店和政策为虚构数据；`DemoChatModel` 是有限规则脚本，Embedding 是确定性字符哈希。它们用于验证工程流程，不代表真实 LLM 的理解、推理或检索能力。真实模式通过 Spring AI 接入 GLM 和 Embedding API，没有训练或微调模型。

## 本地启动

需要 JDK 17、Maven 3.9+ 或 4、Node.js 22+。两个终端分别运行：

```powershell
# 终端 1：在项目目录
cd server
mvn spring-boot:run
```

```powershell
# 终端 2：在项目目录
cd vue-admin
npm ci
npm run dev -- --host 127.0.0.1 --port 5173
```

打开 [酒店咨询页](http://127.0.0.1:5173/assistant)。后端默认仅绑定 `127.0.0.1:9090`。Windows 有多套 JDK 时，在运行 Maven 的终端设置 `JAVA_HOME` 为 JDK 17，并将其 `bin` 放到 `Path` 最前。

可直接尝试：

- `推荐南京酒店，每晚预算400元` → `预算改为300元`
- `酒店1取消退订政策` → `酒店2取消退订政策`
- `查询酒店1在YYYY-MM-DD至YYYY-MM-DD的房态`：换成实际未来日期。
- `酒店1到南京站的路线`：未配置高德服务密钥时明确告知无法核实。

演示库存覆盖启动当天起 14 天；酒店 1 的双床房在启动后第 2 天售罄。选择范围外日期会返回库存不完整。每次重启会重建演示数据、清空服务端会话。

## 接入真实模型与业务数据

配置参数见 [.env.example](.env.example)。**Spring Boot 不自动加载 `.env` 文件**，请通过当前终端环境变量或部署平台的密钥管理设置自己的值。不要复用上游配置中的凭据。

### 先用虚构酒店联调真实 GLM

保留默认 `demo` 数据配置，仅设置 `HOTEL_AI_MODE=live`、`ZHIPU_API_KEY`，然后启动后端。此时聊天和向量使用真实服务，但页面仍标注酒店资料为演示数据；调用可能产生服务费用。

默认服务参数：`ZHIPU_BASE_URL=https://open.bigmodel.cn/api/paas`、`ZHIPU_CHAT_MODEL=glm-4.7`、`ZHIPU_EMBEDDING_MODEL=embedding-3`。使用 OpenAI 兼容协议，实际路径为 `/v4/chat/completions` 和 `/v4/embeddings`。供应商可用模型、账户权限和真实回复格式须在自己的账户验证。

### 使用自己的酒店数据库

1. 准备与原项目实体一致的 MySQL 8 数据库；`server/schema-mysql.sql` 保留上游表结构并移除了账号及业务记录；先审阅，再与 `server/update_db.sql` 一起在独立数据库导入。本项目不会自动运行它们。
2. 执行新增迁移 [room-inventory-mysql.sql](server/src/main/resources/db/room-inventory-mysql.sql)，由自己的库存来源填充日期库存和价格。没有库存同步、锁房或订单写入功能。
3. 设置 `SPRING_PROFILES_ACTIVE=live`、`DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD` 及模型密钥。
4. 设置 `KNOWLEDGE_MANIFEST=file:E:/your-data/hotel-knowledge/manifest.json`；业务模式不会自动使用演示政策。
5. 若需要路线，设置自己的 `AMAP_WEB_SERVICE_KEY`（高德 Web 服务类型）。定位结果多义时会要求进一步明确地点。

### 政策知识库格式

`manifest.json` 与对应 Markdown 文件置于同一目录：

```json
{
  "dataKind": "live",
  "documents": [
    {
      "id": "hotel-101-policy-v1",
      "hotelId": 101,
      "title": "酒店101入住与取消政策",
      "file": "hotel-101.md",
      "source": "酒店提供的政策文档 / 版本日期及原始出处"
    }
  ]
}
```

酒店 ID 必须对应自己的数据库；`0` 仅用于明确适用于所有酒店的通用说明。缺失 ID、标题或出处的资料会被拒绝。按酒店过滤，避免把另一家酒店的取消条件套到当前酒店。

索引首次检索时建立，写入 `server/runtime/knowledge/`；缓存指纹包含清单、文件内容、模型及切片版本。修改资料后重启服务，自动生成新指纹缓存；当前运行进程不会热加载文件。默认最多 2,000 个片段，内存向量存储适用于此项目的有限知识规模；没有实现分布式向量库。

## 接口与会话

- `GET /api/ai/agent/status`：模式、数据类型、是否启用、会话 TTL。
- `POST /api/ai/agent/chat`：输入 `message`、客户端生成的 `requestId`、可选 `sessionId`；返回 `answer`、`citations`、`tools`、耗时及数据标记。
- 原 `POST /api/ai/chat` 保留 `Result<String>` 包装，以兼容旧 Android 客户端；它每次是单轮，不支持新来源卡片。Android 原有服务器地址需要自行配置，本轮未做真机验证。

```json
{"message":"推荐南京酒店，每晚预算400元","requestId":"c55c1d98-4c26-4d10-9976-602829cd77ce","sessionId":null}
```

同一会话内最近 12 个已完成的 `requestId` 可重放缓存；同 ID 换消息返回 409。第一次请求若响应丢失，客户端尚未收到服务端会话 ID，重试可能建立新会话；所有工具只读，不会产生重复订单。

咨询页是匿名只读功能，不使用管理员 Token。会话 ID 是访问该咨询上下文的随机凭证，仅保存在当前标签页 `sessionStorage`，不要公开分享。过期返回 `SESSION_EXPIRED`，界面要求显式开始新咨询，不把错误当作模型回复。最多保留 500 个会话、全局 4 个并发请求；进程重启会丢失会话。60 秒预算在调用边界检查，正在进行的网络请求还受各自超时限制，不是严格的全局中断定时器。

原酒店管理端的账号、权限、JWT 与上传流程不在本轮重构范围，H2 演示也不覆盖这些完整业务表。本地咨询验证通过不代表整套旧管理系统适合直接暴露公网。

## 测试与评测

```powershell
# 后端：真实 Spring AI 协议使用本地伪服务验证，不需要外部密钥
cd server
mvn test

# 前端：先启动演示后端
cd ../vue-admin
npm run build
npx playwright install chromium
npm run test:e2e

# 场景评测：在仓库根目录，保持演示后端运行
python evaluation/evaluate.py
```

[评测用例](evaluation/cases.json) 覆盖 20 个场景，包括多轮改预算、政策隔离、房态缺失、地图未配置、只读操作边界。输出逐轮记录、任务断言通过率、工具结果计数、P50/P95 延迟。

默认评测只接受离线演示。若明确需要测试自己的付费模型，可使用 `--allow-live --output evaluation/live-result.json`，但仍要求演示酒店数据。该固定用例集包含脚本词句断言，真实模型结果须结合人工核验，不能直接当作泛化问答准确率。

实际验证结果见 [验证报告](docs/verification.md)。没有实测真实 GLM、Embedding、高德或生产 MySQL，也没有填写真实模型准确率与生产延迟。

## 代码入口

- `server/src/main/java/com/yisu/server/ai/agent/`：Agent、工具、检索、会话及演示适配器。
- `server/src/test/java/com/yisu/server/ai/agent/`：集成、边界、缓存与协议测试。
- `vue-admin/src/views/Assistant.vue`：咨询页面。
- `vue-admin/tests/assistant.spec.ts`：真实页面到本地后端的浏览器验证。
- `evaluation/`：用例、评测脚本与可复核的演示结果。

当前新增能力及验证边界以本 README 和代码为准。发布快照不包含上游历史密钥、个人简历或数据库记录；上游来源保留于本文。

管理端地图可选配置：在 `vue-admin/.env.local` 设置自己的 `VITE_AMAP_JS_KEY` 和 `VITE_AMAP_SECURITY_PROXY`（已配置的安全代理地址）。Android 地图使用 `AMAP_ANDROID_KEY` 环境变量，未配置时地图功能不可用；新酒店咨询后端使用独立的 `AMAP_WEB_SERVICE_KEY`。
