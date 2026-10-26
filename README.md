# SellerPilot v2

面向小型卖家的经营管理与智能运营应用：查询店铺事实，检索规则和商品资料，生成待确认的标题草稿，确认时校验版本并记录执行回执。

本仓库在独立目录升级原 SellerPilot，保留 Python 实现作为独立参考；当前新增 Java 业务后端与对应前端模式。Java 使用独立数据库、Compose 项目和端口。

Java 版本以经营查询性能、并发审批正确性和订单导入为主线，已实现账号/权限、商品、导入、QuerySpec、版本缓存及审批事务。[Java 运行与架构说明](backend-java/README.md)包含启动、测试和边界；[本次实施与实测记录](docs/Java改造实施记录-2026-10-25.md)记录验证结果及失败案例。[Java 原改造计划](docs/Java后端改造计划-2026-10-23.md)仍保留规划时的目标，不把全部目标视为已完成。

2026-10-26 归档：[按目录整理的改造进度](docs/progress/2026-10-26-java-migration.md)，列明各目录改动、已有验证和下一步事项。

推荐先运行 Java 业务版：

```powershell
./backend-java/scripts/setup-env.ps1
docker compose --env-file .env.java -f compose.java.yaml --profile app up -d --build
```

访问 http://127.0.0.1:5191，API http://127.0.0.1:8081。前端仅启用经营分析、商品、导入、审批与团队权限；Java 当前未迁移 Python RAG/Agent/异步任务。下列既有功能和运行方式属于 Python 版本，[Python v2 方案](SellerPilot-v2方案-v2.0-2026-10-22.md)保留为历史参考。

## Python 版本已有代码

- 账号、店铺、商品、订单与明细 CSV 接入、经营统计，保留原业务入口。
- 店铺成员、owner/operator/viewer 权限、店铺内唯一 SKU、商品版本和数据版本。
- QuerySpec 参数化经营查询、独立币种与时间口径、FactCard、版本缓存。
- MySQL job/outbox、Redis Streams、租约、fencing token、回收对账、取消和持久化事件。
- 文档上传、文本解析分块、不可变 build、发布与禁用，BM25/向量/RRF/可选重排和引用原文。
- AgentScope Toolkit 工具注册与执行，应用层有界运行、身份注入、调用预算、真实模型 HTTP 接入。
- 标题草稿、人工确认、版本冲突、幂等回执和审计；模型没有确认执行工具。
- React 知识中心、任务中心、运营助手、操作审批和成员维护；SSE 游标重连。
- HTTP/任务状态指标、可选 OpenTelemetry、Compose、CI、造数、负载和检索评测脚本。

## 安装与容器运行

要求 Python 3.12、Node.js 24 和可运行的 Docker Linux 引擎。在项目根目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend/requirements.lock
.\.venv\Scripts\python.exe backend/scripts/setup_env.py
docker compose up --build -d
```

`setup_env.py` 不覆盖已有 .env。首次需配置 LLM_BASE_URL、LLM_MODEL、LLM_API_KEY，以及 EMBEDDING_BASE_URL、EMBEDDING_MODEL、EMBEDDING_API_KEY。不要把密钥提交到仓库。

前端 http://127.0.0.1:5190 ，后端文档 http://127.0.0.1:8002/docs 。首次注册账号并创建店铺。API 容器先执行 Alembic 迁移，再提供 HTTP 服务。

一套代码按入口启动 API、Agent worker、文档 worker 和 control，MySQL/Redis/Qdrant/对象存储为独立容器；使用一个 Docker 引擎。v2 固定 Compose 项目名 sellerpilot-v2、独立卷，MySQL 映射3309，避免连接 v1 的3307。对象存储端口9002、控制台9003，Qdrant6335，队列Redis6381、缓存Redis6382。

```powershell
docker compose logs -f api control document-worker agent-worker
docker compose --profile observability up -d
```

Prometheus 为 http://127.0.0.1:9092，Grafana 为 http://127.0.0.1:3002。当前指标覆盖 HTTP 和数据库任务状态；完整 provider 成本账本和所有业务指标尚未实现。`OTEL_ENABLED=1` 可将 HTTP/SQL/worker 关联 span 输出到日志，队列 trace context 保存于任务表。

## 不依赖 Docker 的本地预览

这是明确的单进程演示环境：SQLite、本地对象文件、本地 Qdrant、词特征 hash 向量、数据库轮询 worker。用于查看页面和业务合同，不代表 MySQL 锁、Redis 恢复、语义检索或性能结论。

```powershell
$env:DEMO_PASSWORD='自行设置至少8字符的演示密码'
.\.venv\Scripts\python.exe backend/scripts/local_preview.py
```

另开终端：

```powershell
cd frontend
npm ci
npm run dev
```

访问 http://127.0.0.1:5190，用户名 demo，使用首次启动时设置的密码。预览数据位于 `.data/preview`，重复启动保留数据和原密码。运营助手选“仅检查近七天数据查询”可不调用模型；资料上传可验证构建与检索流程，界面会明确提示 hash 向量不能用于语义效果评测。

## 三条验收流程

1. 经营查询：导入订单/明细，按区间与币种核对统计；选择商品、查看库存；查询结果应与手算样本一致。
2. 知识运营：创建知识库，上传文本资料，任务完成后测试检索并下载对应版本原文；替换/禁用资料后核对可见性。
3. 建议执行：手动或由 Agent 建标题草稿，核对原值与建议后确认；修改商品版本后旧草稿返回冲突，重复确认返回原回执。

## 测试与面试证据

```powershell
.\.venv\Scripts\python.exe -m pytest backend/tests_v2 -q --junitxml=docs/verification/unit-tests.xml
cd frontend
npm run build
npx playwright test
```

浏览器验收要求上述本地预览和前端已启动，设置与预览相同的 DEMO_PASSWORD。可通过 CHROME_PATH 指向本机 Chrome；未设置则先用 `npx playwright install chromium` 安装浏览器。验收数据写入独立预览店铺，不使用个人真实经营资料。

真实服务实验需显式指定独立测试库，脚本拒绝其他数据库名；测试会清空该库的应用表：

```powershell
$env:SP_TEST_DATABASE_URL='mysql+pymysql://测试账号:测试密码@127.0.0.1:3309/sellerpilot_v2_test'
$env:SP_TEST_REDIS_URL='redis://127.0.0.1:6381/9'
.\.venv\Scripts\python.exe -m pytest backend/tests_v2 -q --junitxml=docs/verification/mysql-tests.xml
```

先由数据库管理员创建专用 sellerpilot_v2_test 并授予测试账号权限，不能指向 sellerpilot_v2 或原 sellerpilot_test。默认不执行这些测试。GitHub Actions 配置包含独立 MySQL/Redis 服务，但尚未推送/运行，不把配置存在当作 CI 通过。

[面试证据与实验规范](docs/面试证据与实验规范.md) 定义五个重点案例。目标是问题、备选、取舍、复现、对照与边界，不追求测试条数。当前报告在 `docs/verification`，以报告实际环境为准。

## 百万订单与性能实验

在空闲的独立 v2 数据库、完成迁移后执行。脚本新建模拟店铺，不删除原数据；重复运行会增加数据，请先规划磁盘和测试库。

```powershell
$env:DEMO_PASSWORD='自行设置至少8字符的演示密码'
.\.venv\Scripts\python.exe backend/scripts/seed_v2.py --orders 1000000 --seed 42 --output docs/verification/million-orders.json
```

该脚本生成关联订单与多行明细、倾斜店铺与商品分布，不调用模型。输出种子、日期和实际规模；造数成功不等于完成性能优化。`experiments/query-plan.py` 记录 MySQL 计划，`experiments/query-load.js` 用 k6 测真实经营接口。TOKEN、STORE_ID、AS_OF 通过环境指定，冷暖缓存分别报告，不预填延迟或提升比例。

## 检索评测

发布真实资料后，从分块接口取得来源 ID，人工标注 JSONL 的 id/question/relevant_chunk_ids/answerable/split。使用 `evals/retrieval.py` 在同一构建上对比四种策略，开发集调参、保留集验收，保存降级、实际重排、耗时和原始排名。hash 模式的报告不允许作为语义质量证据。

## 迁移与限制

v2 迁移保留 v1 表，改为店铺内唯一 SKU，新增商品版本与13张业务表。旧版待审核草稿没有历史版本，迁移后必须重新生成；已执行记录保留。直接在原库升级前需要备份并核对重复/孤立数据，本次未对原库执行迁移。

当前不支持扫描文档 OCR、自由 Text-to-SQL、自动采购、外部平台写回、订阅计费或高可用集群。外部模型请求可能重复计费，本地事务不能保证远程操作恰好一次。取消不能撤回已发送给模型的数据。SSE 和对象存储产物的保留/清理策略、完整备份恢复、人工题集与全部14类故障矩阵尚待完成；这些属于原 Python v2 目标的剩余范围，本轮 Java 改造范围与验收按 [Java 后端改造计划](docs/Java后端改造计划-2026-10-23.md)执行。
