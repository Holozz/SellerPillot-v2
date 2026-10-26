# SellerPilot Java 业务后端

卖家的订单散落在导出文件中，经营统计容易混用币种、重复计算订单；多人确认同一修改还可能覆盖已更新的商品。本模块用统一查询口径、版本缓存和数据库事务解决这三条主链路。

当前 Java 范围：账号、店铺与成员权限、商品、订单/明细导入、经营查询、人工审批、执行回执和审计。当前 Java 版本不包含 Agent/RAG 和异步任务。这里不代表电商平台连接器或生产运营系统已经上线。

## 技术与边界

- Java 21、Spring Boot 4.0.9、Spring Security、MyBatis 4、MySQL 8.4、Flyway、HikariCP、Redis 7.4、Micrometer；React / TypeScript / Vite 前端。
- MySQL 是业务事实源。写入采用固定锁顺序：店铺 → 当前成员 → 草稿 → 商品。店铺锁让同店铺写操作串行，简化版本与权限一致性；这是明确的吞吐取舍。
- QuerySpec 只接受白名单指标、分组和绑定参数；UTC 半开区间，币种分别聚合，订单按 distinct 计数。销售额为成交金额减退款。
- 数据与 `data_version` 在同一个只读 RR 快照中读取；写入与版本递增同事务提交。缓存键包含店铺、版本、规范化查询和 schema 版本，旧请求只能回填它实际读取的版本键。
- 缓存重建使用进程内合并、Redis SET NX 租约和 owner 校验删除；每实例最多 2 个聚合，等待有上限。Redis 故障时允许有界回源或 503，不承诺无限可用、严格单次重建或跨外部系统 exactly-once。
- 审批校验商品版本（包含 ABA）、payload hash 和幂等键；商品、店铺版本、回执和审计同事务提交。重复确认返回原回执。旧 title-draft 写入口返回 410，避免双实现。
- 导入在事务外完成 UTF-8/CSV/字段校验；事务内以 500 行 SQL 批次执行，单文件原子提交。文件哈希去重与订单业务键 upsert 分别处理。订单文件和明细文件是两个事务。
- JWT 有效期 1 小时，Argon2 密码哈希，权限每次从数据库读取。登录失败限速为单进程策略；尚无全局风控和账号恢复功能。

## 本地启动

要求 JDK 21、Maven 3.9、Node.js 24、PowerShell 7 和 Docker Linux 引擎。在仓库根目录执行：

```powershell
./backend-java/scripts/setup-env.ps1
docker compose --env-file .env.java -f compose.java.yaml up -d --wait mysql redis
./backend-java/scripts/run.ps1
```

另开终端启动 Java 模式前端：

```powershell
cd frontend
npm ci
npm run dev -- --port 5191
```

访问 http://127.0.0.1:5191，自行注册并创建店铺。API 为 http://127.0.0.1:8081，健康检查 `/actuator/health`。运行脚本读取被 Git 忽略的 `.env.java`，不会覆盖已有密钥。打包前先停止正在使用同名 jar 的本地 API，否则 Windows 会因文件锁而无法重新打包。

Java 使用独立 Compose 项目、MySQL 3310 / `sellerpilot_java`、Redis 6383；测试库为 `sellerpilot_java_test`、Redis DB9。Java 使用独立的数据库 schema，不会触碰其他现存数据库数据。默认初始化脚本按账号 `sellerpilot` 授权；自定义账号时需同步修改初始化授权。

## 主要 API

除注册/登录与公开健康指标外，请求携带 `Authorization: Bearer <access_token>`。前端通过 Nginx/Vite 代理访问同源接口，不需要开放跨域。

| 方法与路径 | 用途 |
| --- | --- |
| `POST /auth/register`、`POST /auth/login`、`GET /auth/me` | 注册、登录与当前用户 |
| `GET/POST /stores` | 可见店铺列表、创建店铺 |
| `GET/PUT /api/v2/stores/{sid}/members` | 所有者维护成员 |
| `POST /products`、`GET /products?store_id={sid}` | 商品创建与列表，创建 body 包含 store_id |
| `PUT /api/v2/stores/{sid}/products/{id}` | 带 version 的商品修改 |
| `POST /stores/{sid}/imports/orders` | multipart 字段 file，订单 CSV |
| `POST /stores/{sid}/imports/order-items` | multipart 字段 file，明细 CSV |
| `POST /api/v2/stores/{sid}/metrics/query` | 受约束经营查询 |
| `POST/GET /api/v2/stores/{sid}/proposals` | 创建/查看待确认草稿 |
| `POST /api/v2/stores/{sid}/proposals/{id}/confirm` | confirmed=true、payload_hash、idempotency_key |
| `POST /api/v2/stores/{sid}/proposals/{id}/reject`、`GET /api/v2/stores/{sid}/audit` | 拒绝草稿、审计记录 |

查询请求：

```json
{"metric":"sales","group_by":"product","start":"2026-10-18T00:00:00Z","end":"2026-10-25T00:00:00Z","statuses":["paid","shipped"],"limit":20}
```

订单 CSV 表头为 `external_order_id,ordered_at,status,currency`；明细 CSV 表头为 `external_order_id,line_no,sku,quantity,unit_price,refund_amount`。先创建商品、导入订单，再导入明细；字段顺序严格，日期需带时区，文件内业务键不可重复。前端提供模板和错误反馈。

400/422 表示请求不合法，401 未登录，403 无操作能力，404 不可见或不存在，409 版本/幂等/约束冲突，410 旧审批入口退役，500 数据库操作失败，503 聚合/重建繁忙。旧 Python 业务后端已从当前工作树退役；此处只说明 Java 当前提供的路由。

也可以将前后端一并容器化（先停止本地 8081/5191 进程）：

```powershell
docker compose --env-file .env.java -f compose.java.yaml --profile app up -d --build
```

若构建在 `auth.docker.io/token` 报连接重置，可先确认本地代理可用，再仅为当前终端设置代理后重试。例如本机代理监听 `127.0.0.1:7897`：

```powershell
$env:HTTP_PROXY='http://127.0.0.1:7897'
$env:HTTPS_PROXY='http://127.0.0.1:7897'
$env:NO_PROXY='127.0.0.1,localhost'
docker compose --env-file .env.java -f compose.java.yaml --profile app up -d --build
```

代理端口按实际配置填写；这些变量只影响当前终端及其子进程。

## Java 实验 Python 工具

在线业务后端为 Java。仅当运行 `experiments/java-*.py` 的合成数据、双 JVM/Redis 故障或证据采集实验时，需要 Python；依赖与业务代码隔离：

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r experiments/requirements.txt
```

脚本依赖 `.env.java` 和 `.data/java-bench/` 中本地生成的模拟凭据。它们不提供 Python HTTP 服务，也不连接外部商家平台。

## 可复现验证

```powershell
# 会清空独立 Java 测试库的应用表，不接受其他数据库名
./backend-java/scripts/test.ps1
cd frontend
$env:PLAYWRIGHT_BASE_URL='http://127.0.0.1:5191'
$env:CHROME_PATH='C:\Program Files\Google\Chrome\Application\chrome.exe'
npx playwright test
```

不设置 `JAVA_IT_MYSQL_URL` 时，Maven 会跳过真实服务测试；不能把这种运行当作完整验收。CI 配置包含 MySQL/Redis Java job，但远程 Actions 是否通过需以实际运行记录为准。

性能实验另见 [实验记录](../docs/Java改造实施记录-2026-10-25.md)。百万订单由 `experiments/java-seed.py --orders 1000000 --seed 42` 生成，凭据仅写入 `.data/java-bench/`。不要对个人真实数据执行实验。造数脚本每次创建新的模拟账号/店铺，不会自动删除旧实验；重复执行会继续占用空间。

负载脚本 `experiments/java-load.ps1` 只向官方 k6 镜像提供指定模拟凭据与查询文件的只读挂载，不挂载仓库或 `.env.java`。k6 容器连接 `host.docker.internal:8081`，本机 API 在这类实验期间需显式设置 `JAVA_BIND_ADDRESS=0.0.0.0`；日常本地启动默认仅监听回环地址。J0/J1 不达标退出码保留为失败，不能只看成功响应的延迟。

两 JVM 实验要求缓存开启、8081/8082 使用相同 JWT 密钥和数据库。`experiments/java-cache-fault.py` 验证真实双进程重建；`--outage` 只停止本项目 Redis，并在 finally 恢复。该实验是并发争用检查，不是生产高可用证明。
