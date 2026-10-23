# ShopSage Micro Platform：项目记忆

[English](memory.md) | 简体中文

**最后更新：** 2026-10-23 · **配合阅读：** [plan.zh-CN.md](plan.zh-CN.md)（接下来做什么）

这是给下一个接手本仓库的人（作者本人或 AI 编程助手）的交接笔记。先读这个文件，再读 `plan.zh-CN.md`。每个会话结束时，中英文两个版本都要更新（见第 11 节）。

## 1. 当前快照（2026-10-23）

| 项目 | 状态 |
|---|---|
| 进度 | 11 天中的 Day 1 已完成（2026-10-18）。Day 2-11 和可选的云端阶段都还没开始。 |
| Git | 分支 `main`：Day 1 的提交 `84aa6d5`，以及 2026-10-23 的一个文档提交，它添加了这些计划文件，并在 README 和设计文档中加上了指向它们的链接。尚无 remote；由作者本人推送到 `createrW/shopsage-micro-platform`。 |
| 构建 | 2026-10-18 干净克隆后 `./mvnw verify` 通过（27 个单元测试和 15 个集成测试，没有跳过）。2026-10-23 离线重跑单元测试，全部通过。 |
| 测量数据 | 还没有。第一批运行在 Day 7。 |
| 云端 | 什么都还没创建。Oracle 在 2026 年 6 月把免费 ARM 额度降到 2 OCPU / 12 GB，OKE 很可能需要升级为 Pay As You Go 或利用试用期；作者在步骤 C0 选定做法（`plan.zh-CN.md` 5.2-5.3）。 |

## 2. 已有的内容

| 方面 | 内容 | 路径 |
|---|---|---|
| 构建 | 基于 Spring Boot 3.2.12 的 Maven 父工程，含 Spring Cloud 2023.0.6、CVE 版本覆盖、enforcer、JaCoCo 和 Failsafe；Maven Wrapper 3.3.4（固定 Maven 3.9.16，校验 SHA-256） | `pom.xml`、`mvnw`、`.mvn/wrapper/` |
| 公共库 | `DomainEvent`、`Topics`、`GlobalExceptionHandler`（RFC 7807）、`ResourceNotFoundException`（404）、`BusinessRuleException`（409）、web 与可观测性自动配置；27 个单元测试 | `common/` |
| 服务 | user 8081、inventory 8082、order 8083、payment 8084；每个都有启动类、`application.yml`、空的 `db/migration/` 和一个冒烟集成测试 | `services/*-service/` |
| 本地环境 | Postgres 16（四个数据库及角色）、Redis 7、KRaft 模式的 Kafka 3.9.2（`kafka-init` 创建 20 个 topic）、Prometheus、Grafana、Jaeger 2.20.0；profile `tools` 中的 kafka-ui；profile `app` 中的四个服务 | `docker-compose.yml`、`configs/`、`.env.example` |
| 镜像 | 一个 Dockerfile，用 `ARG SERVICE` 选择服务，支持多架构构建，以 uid 1001 运行 | `Dockerfile`、`.dockerignore` |
| 脚本 | 启动环境、停止环境、配置 shell | `scripts/dev-up.sh`、`scripts/dev-down.sh`、`scripts/dev-env.sh` |
| 文档 | README、设计文档（16 节）、API 规范、实验记录模板、ADR-0001 至 ADR-0005、后续各天的占位 README | `README.md`、`docs/`、`tests/load/`、`tests/chaos/`、`data/samples/`、`configs/deploy/`、`configs/grafana/dashboards/` |
| 计划 | 本文件和计划文件，中英文各一份 | `plan.md`、`plan.zh-CN.md`、`memory.md`、`memory.zh-CN.md` |

## 3. 还没有的内容

| 缺少的部分 | 计划在 |
|---|---|
| 所有服务的业务代码：controller、实体、repository、MapStruct mapper、Flyway 迁移 | Day 2-5 |
| Spring Security、JWT 签发与密钥处理、JWKS 端点、ADMIN 初始化、resource server 配置 | Day 2 |
| Redis 缓存代码；`data/samples/` 中的种子商品数据 | Day 3 |
| `POST /api/v1/orders`；`outbox` 和 `processed_events` 表、relay、死信处理 | Day 4 |
| Saga 消费者、订单状态机、超时清扫器、mock 支付、故障注入率参数 | Day 5 |
| `services/gateway/` | Day 6 |
| k6 脚本、Toxiproxy 服务（profile `chaos`）、混沌场景、任何测量数据 | Day 7 |
| Grafana 仪表盘、告警规则、SLO、采样预设、`X-Trace-Id` 响应头 | Day 8 |
| `.github/`（CI 工作流、Dependabot）、`.trivyignore.yaml`、GHCR 上的镜像 | Day 9 |
| `deploy/helm/shopsage/`、`configs/deploy/values-minikube.yaml`、`configs/deploy/values-oke.yaml` 初稿（步骤 C2 定稿） | Day 10 |
| 演示 GIF、演示视频、README 最终数字 | Day 11 |
| Oracle Cloud 账号和任何云资源 | `plan.zh-CN.md` 第 5 节 |

## 4. 必须保持的决策

后续每一天都建立在这些名字之上。要改的话，只能通过新的 ADR。

| 主题 | 决策 | 出处 |
|---|---|---|
| 数据库 | 一个 PostgreSQL 16 实例，四个数据库及角色：`shopsage_user`、`shopsage_inventory`、`shopsage_order`、`shopsage_payment`；对 PUBLIC 撤销 CONNECT | ADR-0001 |
| Saga | 协同式（choreography），不是编排式（orchestration）。订单状态 `PENDING → RESERVED → PAID → COMPLETED`；失败时进入 `COMPENSATING`，收齐补偿确认后才进入 `CANCELLED(reason)`；原因为 `OUT_OF_STOCK`、`PAYMENT_FAILED`、`SHIPPING_FAILED`、`TIMEOUT`；30 秒清扫器（`SAGA_TIMEOUT_SECONDS`） | ADR-0002 |
| 价格 | 价格归 inventory-service 所有；客户端从不传价格 | ADR-0003（design §3、§5.3） |
| Topic | `order.created`、`order.cancelled`、`inventory.reserved`、`inventory.rejected`、`inventory.released`、`payment.captured`、`payment.failed`、`payment.refunded`、`shipping.dispatched`、`shipping.failed`，每个都有对应的 `.DLT` 死信 topic：共 20 个，显式创建（broker 自动创建已关闭） | ADR-0003 |
| 消息 | key = orderId；value = JSON 格式的 `DomainEvent` 信封（String 序列化）；header 为 `eventId`、`eventType`、`schemaVersion`、`traceparent`；事务性 outbox 加 `processed_events(consumer, event_id)` 去重表 | ADR-0003 |
| 金额 | 以最小货币单位存整数，加 ISO-4217 币种；字段名以 `Minor` 结尾 | ADR-0003（`docs/api_spec.md` 约定） |
| 认证 | user-service 签发 RS256 JWT，公钥通过 JWKS 发布；每个服务自己校验 token；`X-User-Id` 从不用于授权 | design §9 |
| 端口 | gateway 8080、user 8081、inventory 8082、order 8083、payment 8084；Kafka 在宿主机上是 `localhost:9092`，compose 内部是 `kafka:29092`；为 Toxiproxy 预留 8474、15432、16379、39092 | design §3、§11、§12；README "Services and ports" |
| 版本 | Spring Boot 3.2.12 加 CVE 版本覆盖；Jaeger 固定 2.20.0；只用多架构镜像 | ADR-0004、ADR-0005 |
| user-service | 只有 HTTP，没有 Kafka 客户端 | ADR-0003（design §3） |

## 5. 每个会话的工作规则

- 只做作者指定的那一天。不替换技术栈。从不推送，由作者本人推送。
- 决策有变时写一个新的 ADR，不改写已接受的 ADR。
- `Topics.java`、compose 中 `kafka-init` 服务的 `TOPICS` 列表和 `scripts/dev-up.sh` 中的 `BASE_TOPICS` 必须保持一致；不一致时 `TopicsTest` 会失败。
- 数字只有记入 `docs/experiment_log.md` 之后才能写进 README。
- 只用同时发布 `linux/amd64` 和 `linux/arm64` 的镜像。绝不用 `eclipse-temurin:17-jre-alpine`（只有 amd64），也不用 Bitnami 镜像。
- 没有 Docker 时集成测试要失败，而不是跳过。需要跳过时显式加 `-DskipITs`。行为变化时修改测试断言，绝不为了通过而删测试。
- 绝不提交密钥或私钥：`.env`、`**/secrets/`、`*.pem` 和 `*.key` 都被 gitignore 忽略，所以测试用的密钥要在代码里生成。
- 每个会话结束时：勾选 README 路线图，更新 `plan.md` 和本文件的中英文版本，并在本地提交。

## 6. 如何接续

作者的 Mac（Apple Silicon，16 GB）通过 colima 运行 Docker。JDK 17 来自 Homebrew（`openjdk@17`）。没有全局安装 Maven，请用 `./mvnw`。

```bash
colima start --cpu 6 --memory 8           # Docker 虚拟机；同时把 docker context 切到 colima
source scripts/dev-env.sh                 # JAVA_HOME（JDK 17），以及 DOCKER_HOST 和 Testcontainers socket 覆盖
./scripts/dev-up.sh                       # Postgres、Redis、Kafka（+ 20 个 topic）、Prometheus、Grafana、Jaeger
./mvnw -q -DskipTests install             # 先执行一次；之后每次修改 common/ 都要重新执行
./mvnw verify                             # 单元测试 + Testcontainers 集成测试；加 -DskipITs 只跑单元测试
./mvnw -pl services/user-service spring-boot:run   # 在宿主机上运行单个服务
./scripts/dev-up.sh --with-apps --build   # 或者以容器方式运行四个服务
./scripts/dev-down.sh                     # 停止；加 -v 会在确认后删除数据
colima stop                               # 释放 8 GB 内存
```

本地地址：Grafana <http://localhost:3000>、Prometheus <http://localhost:9090>、Jaeger <http://localhost:16686>、kafka-ui <http://localhost:8089>（需加 `--tools`），各服务在 8081-8084 端口。

## 7. Day 1 的经验教训

| 问题 | 解决办法 |
|---|---|
| 在 colima 下 Testcontainers 测试被悄悄跳过 | 去掉 `disabledWithoutDocker`；`scripts/dev-env.sh` 导出 `DOCKER_HOST` 和 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` |
| `@SpringBootTest` 中 `/actuator/prometheus` 返回 404 或 500 | 在测试上加 `@AutoConfigureObservability(tracing = false)` |
| Grafana 查询不了 Jaeger 2.22 | Jaeger 固定为 2.20.0，因为 2.21 删除了 Grafana 13.2 仍在调用的 v1 查询 API |
| 镜像构建时 Maven 校验和检查失败 | 构建阶段安装 `unzip`；没有它，wrapper 会下载 `.tar.gz`，哈希对不上 |
| 直接执行 `docker compose up --wait` 会把一次性的 `kafka-init`（它会退出）当成失败 | `dev-up.sh` 在 `up --wait` 中只列出六个基础设施服务，然后启动 `kafka-init`，执行 `docker compose wait kafka-init`，再检查 20 个 topic 都已存在 |
| init 脚本还在运行时 Postgres 就报告 healthy | 健康检查通过 TCP 执行 `pg_isready -h 127.0.0.1` |
| `.dockerignore` 中的 `target/` 只匹配根目录 | 改为 `**/target/`，并用 `finalName` 固定 jar 名 |
| 两个 Prometheus job 会重复计数，或出现永远 down 的 job | 只用一个 `file_sd` job，由 `PROMETHEUS_TARGETS` 选择 `host` 或 `docker` |
| 日志行把应用名打印了两遍 | correlation pattern 只添加 `[traceId,spanId]` |

## 8. 未决事项

- **推送：** 还没有 remote。一种做法：`gh repo create createrW/shopsage-micro-platform --public --source=. --push`。现有提交里是作者的学校邮箱；`plan.zh-CN.md` 第 7 节说明了如何在第一次推送前改用 GitHub 的 no-reply 地址。
- **Oracle Cloud：** 注册状态未知。云托管方案把这个账号视为整个作品集的必需项，而且激活可能需要 1-3 天。方案中的 4 OCPU / 24 GB 已经过时：Oracle 现在写明免费额度是 2 OCPU / 12 GB，OKE 很可能需要 Pay As You Go 或试用期。由作者在做法 A、B、C 中选择（`plan.zh-CN.md` 5.3）；做法 B 要在注册之前决定，因为注册就会开始计算 30 天试用期。
- **Day 2 提醒：** 服务变成 resource server 之后，匿名访问 `/actuator/metrics` 和未知路由会返回 401，所以 Day 1 的冒烟测试需要更新断言（见 `plan.zh-CN.md` 的 Day 2）。放行列表必须覆盖 `/actuator/health/readiness` 和 `/liveness`，因为 compose 健康检查和 Kubernetes 探针都不带 token 调用它们。
- **待定的设计问题：** 见 `plan.zh-CN.md` 第 8 节。
- **Spring Boot 3.2 已过开源支持期。** Day 9 的 Trivy 可能报出新的 CVE；按 ADR-0005 的方式处理。

## 9. 仓库外的参考文件

以下是作者的私人计划文件，不在仓库中；`plan.zh-CN.md` 已复述其中用到的内容。

- `master_plan_2026-10-17.md`：原始 brief、11 天计划、指标目标（包括 CI ≤ 5 分钟）和 GitHub 用户名。
- `cloud_hosting_plan_2026-10-17.md`：作者全部作品集项目的托管选型；`plan.zh-CN.md` 第 5 节遵循它，并记录了 Oracle 现行条款与它不一致的地方。
- `code_standard.md`：作者的仓库目录规范；README 说明了它如何映射到本 Maven 仓库。

## 10. 会话日志

| 日期 | 会话 | 结果 |
|---|---|---|
| 2026-10-18 | Day 1 | Maven monorepo、compose 环境、脚本、设计文档和 ADR-0001 至 ADR-0005；从干净克隆验证通过；提交 `84aa6d5` |
| 2026-10-23 | 规划 | 新增中英文 `plan.md` 和 `memory.md`。核实了 Oracle 当前的免费额度（2026 年 6 月 A1 减半为 2 OCPU / 12 GB；OKE 很可能需要 Pay As You Go 或试用期），并据此调整云端计划。两轮独立审查（先五路，再三路）并完成修正。在 API 规范、design §3 和 §5.3、ADR-0003 的状态行以及 README 中把 `POST /api/v1/orders` 移到 Day 4。单元测试重跑通过。 |

## 11. 如何更新本文件

每个会话结束时：

1. 更新快照（第 1 节）和"最后更新"日期。
2. 把完成的项目从第 3 节移到第 2 节。
3. 新决策连同对应 ADR 加入第 4 节，来之不易的修复加入第 7 节。
4. 在会话日志中加一行。
5. 在 `memory.md` 中做同样的修改，并更新两个计划文件中的状态列。
