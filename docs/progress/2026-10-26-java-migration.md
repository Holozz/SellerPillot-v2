# SellerPilot Java 改造进度（按目录）

整理日期：2026-10-26。汇总 Java 改造实现、验证和续做结果，详列待完成项。

## 目录总览

| 目录 / 文件 | 本轮完成 | 验证与边界 |
| --- | --- | --- |
| `backend-java/src/main/java/com/sellerpilot/` | Spring Boot / Security / MyBatis 业务模块：账号、店铺成员、商品、导入、经营查询、审批、回执与审计 | 主链路已有真实服务测试；尚未迁移 Python AI 功能 |
| `backend-java/src/main/resources/` | 应用配置、Flyway V1–V4：业务表、审批表、覆盖索引及索引前缀修正 | 独立 MySQL 库迁移成功；保留索引候选与迁移失败记录 |
| `backend-java/src/test/` | QuerySpec、CSV、真实 MySQL/Redis 集成测试 | 18 项通过，0 失败/错误/跳过；本次核对归档报告，未重新运行 |
| `backend-java/scripts/` | 环境生成、环境加载、本地启动和测试 PowerShell 脚本 | 不覆盖现有密钥；测试限定独立 Java 测试库 |
| `backend-java/README.md`、`pom.xml`、`Dockerfile` | 技术选型、API/CSV 合同、运行说明及构建定义 | 本地 Maven 构建已通过；Docker Hub OAuth 连接被重置，完整应用镜像未构建 |
| `frontend/src/` | 显式 Java 模式，经营分析、商品、导入、审批、团队权限；复用基础 UI | 较早版本构建和浏览器链路通过；最新表格列调整尚未复验 |
| `frontend/e2e/`、`playwright.config.ts` | Java 浏览器业务链路与模式选择 | 10-26 最新 UI 调整后重跑，注册→建店→商品→导入→38.00 净额→审批→审计通过，1 passed |
| `frontend/Dockerfile`、`.dockerignore`、`vite.config.ts` | 构建模式参数、排除依赖/临时文件、Java API 代理 | Java 完整容器部署仍待验证 |
| `experiments/java-*` | 百万造数、与 API 同源 SQL 执行计划、k6 负载、证据归档、双进程/Redis 故障脚本 | 造数、计划、负载和归档已执行；双 JVM 与 Redis 中断脚本未执行 |
| `deploy/java/`、`compose.java.yaml` | 独立 MySQL/Redis、测试库初始化、Java API 与 Nginx 前端配置 | Compose 配置静态检查通过；全套 API/前端镜像受 Docker Hub 网络阻塞，未验证 |
| `docs/verification/java/` | JUnit、浏览器截图、SQL 计划、负载 JSON、环境、源码 SHA 和失败记录 | 同时保留成功与失败结果；模拟数据不代表生产容量 |
| `docs/` | 改造计划补充、实施记录、简历目标稿与本目录进度汇总 | 简历稿已用实测数字更新，标明合成数据、单机负载和生产边界 |
| `.github/workflows/verify.yml` | Java MySQL/Redis 测试 job、Java 模式前端构建 | 仅配置已写，未推送或运行远程 Actions |
| 根目录 `README.md`、`.env.java.example`、`.gitignore` | Java 运行入口、环境模板、密钥与构建产物忽略规则 | `.env.java`、模拟账号凭据、数据库数据与构建缓存不提交 |
| 原 `backend/` | 保留 Python 业务与 AI 参考实现 | 已有 SHA 核对记录显示 61 个 Python 后端文件未改变；Python 证据与 Java 证据分别解释 |

## 后端核心机制

- 经营查询：白名单 QuerySpec、参数绑定、UTC 半开区间、币种分开、订单 distinct、退款扣减。
- 缓存：业务写入与版本递增同事务，版本与数据同 RR 快照；版本键隔离旧结果，命中前仍校验权限/版本。
- 回源保护：进程内合并、Redis 租约、二次检查、标识校验释放、有限等待、每实例聚合并发上限；允许有界拒绝。
- 审批：固定锁顺序、商品版本与 ABA 校验、payload hash、幂等键及唯一执行约束；商品、版本、回执和审计同事务。
- 导入：事务外解析与校验，文件哈希去重、店铺业务键更新、500 行批次、单文件原子提交；订单与明细文件分别提交。

## 已取得的证据

以下为上次运行的归档结果，本次整理没有重跑测试或压测。

| 验证 | 结果 | 原始记录 |
| --- | --- | --- |
| Maven / JUnit | 18 项通过，其中 13 项真实 MySQL/Redis、5 项参数/解析测试 | [JUnit](../verification/java/junit) |
| 并发审批 | 100 请求均 200，仅 1 条执行、1 条审计，回执一致 | [争用报告](../verification/java/approval-contention.json) |
| 快照隔离对照 | 相同交错下 RR 保持旧版本/旧结果；RC 可混合旧版本和新结果 | [RR/RC 记录](../verification/java/mysql-snapshot.json) |
| 导入与回滚 | 文件去重/更新；501 行在第二批故障后整文件与版本回滚 | [业务集成测试](../verification/java/junit/com.sellerpilot.CommerceIntegrationTest.txt) |
| 模拟数据规模 | 1,000,000 订单，2,499,285 明细，热点店铺约占 80% | [数据分布](../verification/java/seed-manifest.json) |
| 首轮 200 req/s、5 分钟 | 已发送的 59,870 请求均正确，p95 约 13ms，但 dropped=131，门禁失败 | [失败轮次](../verification/java/J2J3-200rps-load.json) |
| 300 预分配 VU 重跑 | 200 req/s、5 分钟，60,001 请求，0 业务错误、0 dropped，p95 约 12.4ms | [通过轮次](../verification/java/J2J3-200rps-preallocated-load.json) |
| 双 JVM 冷键合并 | 8081/8082 两个真实进程，40 请求均 200 且结果正确，仅 1 次合计聚合 | [报告](../verification/java/two-jvm-rebuild.json) |
| Redis 停止/恢复 | 40 请求全部正确 200，停机时两个实例各回源 1 次，恢复查询正确 | [报告](../verification/java/redis-outage.json) |
| 最新前端验收 | Java 构建通过，Playwright 1 passed，截图已更新 | [浏览器报告](../verification/java/browser-results.json) |

J0/J1 无缓存基线均未达标，失败结果也已保存。当前热点查询仅覆盖最近 7 天，实际匹配 26,452 个订单和 65,984 条明细，不能表述为每次扫描百万单；缓存与重建保护一起启用，尚无两者独立消融。环境为本机 Windows/Docker、MySQL buffer pool 128MiB，非生产 SLA。

## 保留的失败案例

1. `store_id` 开头的明细覆盖索引引起商品优先连接与反复扫描；保留失败计划，改为 `order_id` 前缀后验证恢复订单过滤优先的计划。
2. InnoDB 复用覆盖索引支持外键，直接删除报 1553；修正为先建立窄外键索引，再替换覆盖索引。
3. 100 客户端同时建连出现一次 ConnectException；调整有界 backlog 后测试通过，未隔离确认唯一起因。
4. 审计故障与第二批导入故障验证整个事务回滚；压测首轮 dropped 不达标也保留，未覆盖成成功记录。

详见 [完整实施记录](../Java改造实施记录-2026-10-25.md)。

## 下次继续的位置

1. Docker Hub OAuth 连接恢复后，重试完整 Java Compose 镜像构建与启动；配置静态检查已通过。
2. 运行验证已于 2026-10-26 完成。后续若有代码改动，再按影响面重跑验证。
3. 远程 CI 尚未推送运行；简历目标稿已根据本地模拟负载与故障验证更新。

Java 预设端口：API 8081、前端 5191、MySQL 3310、Redis 6383；测试库 `sellerpilot_java_test` / Redis DB9。服务状态下次重新检查。本次提交不包含密钥、模拟账号密码、百万订单数据、依赖缓存或构建产物，也不执行 push。
