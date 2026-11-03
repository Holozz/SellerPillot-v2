# SellerPilot

面向小型卖家的多店铺运营管理项目，当前主线为 Java 后端和 React 前端。用户可管理商品、导入订单、核对经营统计，并通过人工审批确认标题修改。

当前版本支持跨店商品归并、采购批次成本与人民币毛利、历史报表和后台重算。运行边界见 [Java 模块说明](backend-java/README.md)，测试结果和复现条件见 [实施方案](docs/实施方案.md)。AI 经营助手暂缓。

## 技术栈

Java 21、Spring Boot、Spring Security、MyBatis、MySQL、Redis、Flyway、HikariCP、Micrometer；前端使用 React、TypeScript、Vite；Docker Compose 提供本地运行环境。

Python 业务后端已从当前工作树退役。历史 Python 设计与验证记录仅作为迁移前材料；Java 项目不包含 Agent/RAG 功能。`experiments/java-*.py` 是用于 Java 合成数据、缓存故障与证据采集的独立工具，不属于在线业务服务。

## 启动

要求 Docker Desktop/Linux 容器引擎。首次启动会在仓库根目录生成 `.env.java`：

```powershell
cd D:\Project\SellerPilot-v2
./backend-java/scripts/setup-env.ps1
docker compose --env-file .env.java -f compose.java.yaml --profile app up -d --build
```

打开 http://127.0.0.1:5191；API 健康检查为 http://127.0.0.1:8081/actuator/health。停止服务：

```powershell
docker compose --env-file .env.java -f compose.java.yaml --profile app stop
```

仅启动 MySQL/Redis 并在宿主机运行 API 或前端的方式、CSV 合同、实验步骤和边界说明见 [backend-java/README.md](backend-java/README.md)。

## 结构

- `backend-java/`：Java API、数据库迁移、集成测试及 PowerShell 脚本。
- `frontend/`：唯一的 Java 管理工作台和浏览器流程。
- `compose.java.yaml`、`deploy/java/`：当前 Docker Compose 服务定义。
- `experiments/java-*`：合成数据、负载、双 JVM/Redis 故障和证据采集工具。
- `docs/verification/java/`：本机验证材料，模拟负载不代表生产 SLA。
- `docs/实施方案.md`：唯一更新方案、任务清单与验收入口。
- `docs/verification/`：历史和后续验证证据；旧实验不能作为新功能的验收结果。

Java CI 配置见 `.github/workflows/verify.yml`。本地验证结果和远程 GitHub Actions 结果分别记录。
