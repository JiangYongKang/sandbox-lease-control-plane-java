# 临时环境租约控制平面（进程内模拟）

面向内部开发平台的临时沙箱环境租约控制平面。资源池、租约存储与底层“沙箱供应”
全部在**单个进程内**模拟：不依赖任何外部服务、真实凭据或付费组件。重点是让
**并发申请、超时回收与失败补偿在结果上保持一致且可复现**。

基于 Spring Boot 3.4 / Java 21，沿用工程既有的 Maven 与 JUnit 5 约定。

## 1. 能力总览

| 操作 | HTTP | 语义 |
| ---- | ---- | ---- |
| 申请 | `POST /api/leases` | 预留整池+团队两层配额 → 准备沙箱 → 生效；按 `requestId` 幂等 |
| 续约 | `POST /api/leases/{leaseId}/renew` | 仅 `ACTIVE` 可续约，顺延到期时间；按 `requestId` 幂等 |
| 释放 | `POST /api/leases/{leaseId}/release` | 主动释放；对终态租约重复释放返回同一终态结论 |
| 查询 | `GET /api/leases/{leaseId}` | 重复查询结论可复现；到期会被惰性回收后返回 `EXPIRED` |
| 整池配额 | `GET /api/leases/quota` | 返回 `capacity / available / leased` 快照 |
| 团队额度 | `GET /api/teams/{tenantId}/quota` | 返回团队 `limit / leased / remaining` 与同现场的整池快照 |
| 团队租约 | `GET /api/teams/{tenantId}/leases` | 返回该团队当前非终态租约清单（数=团队占用） |
| 调整团队额度 | `PUT /api/teams/{tenantId}/quota` | 在线调整；`{"limit":N}` 立即生效，`{"limit":null}` 移除配置 |

请求示例：

```bash
curl -s -X POST localhost:8080/api/leases \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"req-001","tenantId":"team-a","ttlSeconds":120}'

curl -s localhost:8080/api/leases/quota
curl -s localhost:8080/api/teams/team-a/quota
curl -s localhost:8080/api/teams/team-a/leases
# 在线把 team-a 的额度上限改为 3（null 表示移除配置，回到整池共享）
curl -s -X PUT localhost:8080/api/teams/team-a/quota \
  -H 'Content-Type: application/json' -d '{"limit":3}'
curl -s -X POST localhost:8080/api/leases/<leaseId>/release \
  -H 'Content-Type: application/json' -d '{"requestId":"rel-001"}'
```

## 2. 租约状态机

```text
                 申请成功(资源就绪)
   (申请创建) ┌──────────────────┐
 null ─────► PENDING ──────────► ACTIVE ───────────► RELEASED
               │                   │  ▲                 (终态)
               │ 申请失败并补偿     │  │ 主动释放
               ▼                   │  │ (经 RELEASING)
             FAILED                ▼  │
             (终态)             RELEASING
                                 │
                                 ▼
                              RELEASED
              ACTIVE 到期(定时回收/惰性到期) ──► EXPIRED (终态)
```

- 合法迁移：`PENDING→ACTIVE / PENDING→FAILED / ACTIVE→RELEASING /
  RELEASING→RELEASED / ACTIVE→EXPIRED`；
  `RELEASED / EXPIRED / FAILED` 为终态，不可再变更。
- 状态迁移只能经 `Lease#transitionTo`，非法迁移直接抛错，
  保证同一租约不会同时处于相互冲突的状态。
- **自动回收与主动释放竞争**：二者共用服务内同一把公平锁串行执行。
  谁先拿到锁谁终结租约；后到者看到 `EXPIRED`/`RELEASED` 终态后返回同一结论。
  释放一个已到期租约得到 `EXPIRED`（到期先生效），不会再做第二次配额归还。

## 3. 配额核算规则

- 资源池 `ResourcePool` 以 CAS 计数，容量由 `lease.pool-capacity` 配置（默认 4）。
  并发抢占下 `leased <= capacity` 恒成立；容量不足时申请被明确拒绝（`QUOTA_EXHAUSTED`）。
- 团队额度由 `TeamQuotaManager` 按 tenantId 记账（`leased/limit`），与资源池在
  `LeaseService` 的**同一把公平锁内**成对预留、成对归还。
- 配额变化点严格配平（整池与团队两层一起动）：
  - 申请先做团队准入判定，再 `tryAcquire` 整池、随后团队占用 +1，成功后立即创建
    `PENDING` 租约落库（不存在“无主”资源）；
  - 主动释放 / 自动回收 / 惰性到期各只归还一次（整池 -1、团队 -1）；
  - provision 失败补偿时两层额度一起还；
  - 归还侧对超额归还都做下限保护并告警，防止余额被破坏。
- **恒等不变量**：任意时刻（锁边界上）`Σ 各团队 leased == 资源池 leased`。
- 续约不改变配额，只顺延 `expiresAt`（且不会早于原到期时间）。

### 3.1 团队额度控制（team quota）

在整池容量之上叠加的一层**按团队**的额度上限，解决多个团队共享一个资源池时
“手快的团队把池子占满、其它团队只能干等”的问题。

**准入与拒绝原因（可区分）**

1. 团队已配置额度且当前占用已到上限 → `TEAM_QUOTA_EXHAUSTED`（HTTP 503）；
2. 否则整池已满 → `QUOTA_EXHAUSTED`（HTTP 503）；
3. 参数不合法 → `INVALID_REQUEST`（HTTP 400）。

两类“额度满”虽同为 503，但错误码不同，调用方可据此区分“自家额度用完”还是
“整池用完”。团队判定先于整池：池里还有空位但自家额度满时，得到的是
`TEAM_QUOTA_EXHAUSTED`。

**未配置团队的兼容行为**

- 只有显式配置了额度的团队才受限；未配置团队的 `limit/remaining` 为 `null`，
  行为与引入团队额度之前**完全一致**，只受整池容量约束，不会被额外卡住。

**记账对齐与并发**

- 团队占用与整池占用在同一把全局锁内串行变更，配平规则见第 3 节；
- 多团队并发抢时，既不突破各自团队上限，也不突破整池上限
  （成功数恰为 `min(各团队剩余, 整池剩余)` 的竞争结果）；
- 主动释放、定时回收、惰性到期（含团队额度/租约查询触发）、provision 失败回滚，
  都会把团队与整池额度各还一次，不重复计；
- 团队视图里的 `poolCapacity/poolLeased/poolAvailable` 与 `GET /api/leases/quota`
  在同一调用现场取快照，两边说法一致。

**被拒请求的重复提交一致性**

- “未占用即拒绝”（团队满 / 整池满）也会按 `requestId` 记录**首次拒绝结论**；
  之后哪怕额度被释放、空出来了，同一 `requestId` 重复提交仍返回**同一个拒绝码**，
  绝不会在第二次悄悄申请成功。新的 `requestId` 才能使用空出的额度。
- 申请成功 / `FAILED` 的幂等结论沿用原机制（第 5 节）。

**在线调整**

- `PUT /api/teams/{tenantId}/quota` 立即对**后续申请**生效，无需重启；
- 只改上限、不碰占用：调到低于当前占用（甚至 0）时，已开出租约**不会被强拆**，
  仅表现为 `remaining=0`、后续申请继续被拒，直到占用自然释放回上限以内；
- `limit` 设为 `null` 移除该团队配置，回到整池共享，存量租约不变；
- 团队上限允许配得比整池容量大（会告警），实际吞吐仍受整池容量约束。

## 4. 申请链路的失败补偿与错误归类

申请流程：`幂等检查 → 团队额度准入 → 预留整池配额 → 团队占用记账 → 落 PENDING 租约 → provision → ACTIVE`。

- `provision` 抛出的任何底层异常都**不会原样透出**，而是：
  1. 先归还配额（补偿）；
  2. 将租约从 `PENDING` 迁移到 `FAILED` 并记录失败原因；
  3. 落幂等结论后向调用方抛出归类后的 `PROVISION_FAILED`（或意外异常的 `INTERNAL_ERROR`）。
- 因此失败路径**不泄漏配额（团队与整池两层都不泄漏）、不遗留悬挂租约**；同一 `requestId` 再次提交得到
  **同一失败结论**，不会在第二次悄悄成功。
- 团队额度满 / 整池满的拒绝发生在任何资源预留**之前**，不产生占用；首次拒绝也按
  `requestId` 落库，重复提交复现同一拒绝码（见 3.1）。
- 本地供应器 `LocalSandboxProvisioner` 提供确定性失败注入，便于可复现测试：
  - `tenantId` 以 `fail:` 开头：始终失败（确定性下游不可用）；
  - `tenantId` 以 `fail-once:` 开头：该租户首次申请抛瞬时抖动；
  - 其它：成功分配本地资源标识。

可区分的对外错误码（`GlobalExceptionHandler` 统一翻译，不回传底层堆栈）：

| 错误码 | HTTP | 触发场景 |
| ---- | ---- | ---- |
| `INVALID_REQUEST` | 400 | 缺少 requestId/tenantId、ttl 非法、团队额度调整为负数、报文格式错 |
| `LEASE_NOT_FOUND` | 404 | 租约不存在 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | requestId 已被另一个租约/被拒申请使用 |
| `LEASE_EXPIRED` | 409 | 对已到期租约续约 |
| `LEASE_NOT_ACTIVE` | 409 | 非 ACTIVE 租约续约、释放 FAILED 租约等 |
| `QUOTA_EXHAUSTED` | 503 | 资源池已满（团队自身额度未必满） |
| `TEAM_QUOTA_EXHAUSTED` | 503 | 该团队占用已达到其单独配置的额度上限 |
| `PROVISION_FAILED` | 502 | 底层沙箱准备失败（已补偿两层配额） |
| `INTERNAL_ERROR` | 500 | 未预期异常（细节不外泄） |

## 5. 幂等与一致性

- 申请：`LeaseStore` 用 `requestId -> 首次结论` 原子去重；重复提交返回同一
  `leaseId/resourceId/status`，只占用一份（团队 + 整池）配额。
- 申请被拒（团队满 / 整池满）：同样用 `requestId -> 首次拒绝原因` 去重，
  重复提交复现同一拒绝码，与之后额度是否空出无关。
- 续约/释放：按各自 `requestId` 记录首次操作结论，重复操作返回同一结论。
- 所有写操作在 `LeaseService` 的同一把公平锁内串行；配合资源池 CAS 与团队额度记账，
  保证并发申请不超卖（团队、整池两层都不超）、重复释放不重复归还、回收/释放竞争结果唯一。
- 到期有两个入口（定时扫描 `LeaseReaper` 与各操作中的惰性判定，含团队额度/租约查询），
  但终结逻辑唯一且受锁保护。

## 6. 配置（src/main/resources/application.properties）

```properties
lease.pool-capacity=4               # 资源池容量
lease.default-ttl-seconds=60        # ttlSeconds=0 时使用的默认 TTL
lease.max-ttl-seconds=3600          # 允许的最大 TTL
lease.reaper-interval-millis=2000   # 定时回收扫描间隔

# 按团队配置初始额度上限（启动时加载；运行期可用 PUT API 在线调整）
lease.team-limits.team-a=2          # team-a 最多同时持有 2 个
lease.team-limits.team-b=1          # 团队名含特殊字符用方括号写法：
                                    # lease.team-limits[my-team]=2
```

- 只列出的团队受限；未列出团队不配置额度，仅受 `lease.pool-capacity` 约束。
- 初始配置只是“启动默认值”，运行期一切调整以 `PUT /api/teams/{tenantId}/quota` 为准
  （进程内模拟，重启后回到配置文件值）。
- 团队视图 `GET /api/teams/{tenantId}/quota` 返回示例：

```json
{
  "tenantId": "team-a",
  "limit": 2,
  "leased": 1,
  "remaining": 1,
  "poolCapacity": 4,
  "poolLeased": 3,
  "poolAvailable": 1
}
```

未配置团队的 `limit` 与 `remaining` 为 `null`（JSON 中字段缺席）。

## 7. 本地验证（无需联网 / 外部服务）

```bash
# 全量测试（27 个用例）
mvn test

# 只跑某一类场景
mvn test -Dtest=LeaseServiceLifecycleTest                    # 生命周期 / 配额 / 申请幂等
mvn test -Dtest=LeaseServiceCompensationTest                 # 失败补偿 / 超时回收 / 双重释放
mvn test -Dtest=LeaseServiceConcurrencyTest                  # 并发抢占 / 提交竞争 / 释放-回收竞争
mvn test -Dtest=LeaseServiceGuardTest                        # 查询可复现 / 状态守卫 / 标识冲突
mvn test -Dtest=LeaseTeamQuotaTest                           # 团队额度准入/拒绝码/跨团队并发/
                                                             #   被拒重复提交/归还/在线调整/一致性
mvn test -Dtest=TeamQuotaControllerIntegrationTest           # 团队额度 HTTP 端到端
mvn test -Dtest=LeaseControllerIntegrationTest               # HTTP 状态码与错误码端到端

# 启动后手工验证
mvn spring-boot:run
```

手工冒烟（默认 team-a 上限 2、整池容量 4）：

```bash
# team-a 连开 2 个成功，第 3 个 TEAM_QUOTA_EXHAUSTED（此时整池只用了 2/4）
for i in 1 2 3; do
  curl -s -o /dev/null -w "smoke-$i -> %{http_code}\n" -X POST localhost:8080/api/leases \
    -H 'Content-Type: application/json' \
    -d "{\"requestId\":\"smoke-$i\",\"tenantId\":\"team-a\",\"ttlSeconds\":60}"
done
curl -s localhost:8080/api/teams/team-a/quota       # leased=2 remaining=0，poolLeased=2/4
curl -s -X PUT localhost:8080/api/teams/team-a/quota \
  -H 'Content-Type: application/json' -d '{"limit":5}'   # 在线放宽
```

并发与超时测试通过注入可控 `Clock`（`MutableClock`）驱动时间，结果确定可复现。
单测日志统一打印 **用例名、requestId、tenantId、团队额度与整池配额变化（leased/available）
及状态迁移**，例如：

```text
========== [test-case] crossTeamConcurrentContention_respectsBothLimits | 跨团队并发抢占... ==========
team quota acquire: tenantId=team-a, leased 0 -> 1, limit=2
quota acquire: leased 0 -> 1, available=3
state transition: requestId=race-a-0, leaseId=..., tenantId=team-a, status null -> PENDING, teamLeased=1
state transition: ... status PENDING -> ACTIVE, teamLeased=1, poolAvailable=3
apply rejected: requestId=race-a-3, code=TEAM_QUOTA_EXHAUSTED, poolLeased=6/4, teamQuotasTotal=6
[test] cross-team result success={team-c=2} rejects={team-a={TEAM_QUOTA_EXHAUSTED=10}, ...} poolLeased=6/6
state transition (auto-reap): leaseId=..., tenantId=team-exp, status ACTIVE -> EXPIRED, teamLeased=0, poolAvailable=4
compensation done: requestId=ret-fail, tenantId=fail:broken, status PENDING -> FAILED, teamLeased=0, poolAvailable=4
```
