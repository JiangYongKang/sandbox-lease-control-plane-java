# 临时环境租约控制平面（进程内模拟）

面向内部开发平台的临时沙箱环境租约控制平面。资源池、租约存储与底层“沙箱供应”
全部在**单个进程内**模拟：不依赖任何外部服务、真实凭据或付费组件。重点是让
**并发申请、超时回收与失败补偿在结果上保持一致且可复现**。

基于 Spring Boot 3.4 / Java 21，沿用工程既有的 Maven 与 JUnit 5 约定。

## 1. 能力总览

| 操作 | HTTP | 语义 |
| ---- | ---- | ---- |
| 申请 | `POST /api/leases` | 团队额度准入 → 预留整池配额 → 准备沙箱 → 生效；按 `requestId` 幂等 |
| 续约 | `POST /api/leases/{leaseId}/renew` | 仅 `ACTIVE` 可续约，顺延到期时间；按 `requestId` 幂等 |
| 释放 | `POST /api/leases/{leaseId}/release` | 主动释放；对终态租约重复释放返回同一终态结论 |
| 查询 | `GET /api/leases/{leaseId}` | 重复查询结论可复现；到期会被惰性回收后返回 `EXPIRED` |
| 配额 | `GET /api/leases/quota` | 返回 `capacity / available / leased` 快照 |
| 团队额度查询 | `GET /api/leases/teams/{teamId}/quota` | 返回该团队 `limit / used / remaining`（未配置上限时 `limit/remaining` 为 null） |
| 团队租约列表 | `GET /api/leases/teams/{teamId}/leases` | 该团队当前持有的非终态租约 |
| 团队额度调整 | `PUT /api/leases/teams/{teamId}/quota` | 在线设置上限 `{"limit": n}`，对后续申请立即生效，不拆存量 |
| 团队额度清除 | `DELETE /api/leases/teams/{teamId}/quota` | 清除上限，恢复整池共享约束 |

请求示例：

```bash
curl -s -X POST localhost:8080/api/leases \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"req-001","tenantId":"team-a","ttlSeconds":120}'

curl -s localhost:8080/api/leases/quota
curl -s -X POST localhost:8080/api/leases/<leaseId>/release \
  -H 'Content-Type: application/json' -d '{"requestId":"rel-001"}'

# 团队额度：在线设置上限 -> 查询使用情况与挂着哪些租约 -> 清除上限
curl -s -X PUT localhost:8080/api/leases/teams/team-a/quota \
  -H 'Content-Type: application/json' -d '{"limit":2}'
curl -s localhost:8080/api/leases/teams/team-a/quota
curl -s localhost:8080/api/leases/teams/team-a/leases
curl -s -X DELETE localhost:8080/api/leases/teams/team-a/quota
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
- 配额变化点严格配平：
  - 申请先 `tryAcquire` 预留，成功后立即创建 `PENDING` 租约落库（不存在“无主”资源）；
  - 主动释放 / 自动回收 / 惰性到期各只 `release` 一次；
  - 归还侧对超额归还做下限保护并告警，防止余额被破坏。
- 续约不改变配额，只顺延 `expiresAt`（且不会早于原到期时间）。

### 3.1 团队额度

在整池容量之上叠加一层按团队（`tenantId`）的额度控制，由 `TeamQuotaRegistry` 记账：

- **准入顺序**：申请先过团队额度、再预留整池配额。团队占用已达其上限时，
  申请被明确拒绝并返回 `TEAM_QUOTA_EXCEEDED`（HTTP 429），与整池已满的
  `QUOTA_EXHAUSTED`（HTTP 503）、参数非法的 `INVALID_REQUEST`（HTTP 400）可区分。
- **未配置团队的兼容行为**：没有配上限的团队不受任何额外约束，照旧只受整池容量
  限制；但系统仍为其记账（用于查询与全局对账），行为与加团队额度之前完全一致。
- **记账对齐**：团队占用与整池配额在同一把锁内成对 acquire/release——主动释放、
  到期自动回收、申请失败回滚三条路径都把团队额度与整池配额一次性还回，不重复计；
  任意时刻 **所有团队占用之和 == 整池 leased**。并发抢占时既不会突破团队上限，
  也不会突破整池上限。
- **在线调整**：`PUT /api/leases/teams/{teamId}/quota` 立即生效于后续申请；
  存量租约不受影响——即使把上限调到比当前占用还低，也只是让后续申请继续被拒
  （此时 `remaining` 为 0），已开出的环境不会被强拆。`DELETE` 清除上限后恢复整池共享。
- **查询一致性**：`GET .../teams/{teamId}/quota` 与 `GET .../quota` 在同一锁内取快照，
  团队视图的 `used` 之和始终等于整池视图的 `leased`；`GET .../teams/{teamId}/leases`
  返回该团队当前持有的非终态租约（查询前先做惰性到期判定，口径与其它入口一致）。

## 4. 申请链路的失败补偿与错误归类

申请流程：`幂等检查 → 预留配额 → 落 PENDING 租约 → provision → ACTIVE`。

- `provision` 抛出的任何底层异常都**不会原样透出**，而是：
  1. 先归还配额（补偿）；
  2. 将租约从 `PENDING` 迁移到 `FAILED` 并记录失败原因；
  3. 落幂等结论后向调用方抛出归类后的 `PROVISION_FAILED`（或意外异常的 `INTERNAL_ERROR`）。
- 因此失败路径**不泄漏配额、不遗留悬挂租约**；同一 `requestId` 再次提交得到
  **同一失败结论**，不会在第二次悄悄成功。
- 本地供应器 `LocalSandboxProvisioner` 提供确定性失败注入，便于可复现测试：
  - `tenantId` 以 `fail:` 开头：始终失败（确定性下游不可用）；
  - `tenantId` 以 `fail-once:` 开头：该租户首次申请抛瞬时抖动；
  - 其它：成功分配本地资源标识。

可区分的对外错误码（`GlobalExceptionHandler` 统一翻译，不回传底层堆栈）：

| 错误码 | HTTP | 触发场景 |
| ---- | ---- | ---- |
| `INVALID_REQUEST` | 400 | 缺少 requestId/tenantId、ttl 非法、报文格式错 |
| `LEASE_NOT_FOUND` | 404 | 租约不存在 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | requestId 已被另一个租约使用；首次申请被拒/失败的标识被复用于续约或释放；续约/释放标识反向复用于申请 |
| `LEASE_EXPIRED` | 409 | 对已到期租约续约 |
| `LEASE_NOT_ACTIVE` | 409 | 非 ACTIVE 租约续约、释放 FAILED 租约等 |
| `QUOTA_EXHAUSTED` | 503 | 资源池已满 |
| `TEAM_QUOTA_EXCEEDED` | 429 | 团队额度已达上限（与整池已满可区分） |
| `PROVISION_FAILED` | 502 | 底层沙箱准备失败（已补偿配额） |
| `INTERNAL_ERROR` | 500 | 未预期异常（细节不外泄） |

## 5. 幂等与一致性

- 申请：`LeaseStore` 用 `requestId -> 首次结论` 原子去重；重复提交返回同一
  `leaseId/resourceId/status`，只占用一份配额。**准入被拒（团队额度超限 /
  整池已满）的结论同样落库**：同一 `requestId` 重复提交，不管首次是成功还是被拒，
  都拿到一致结论——不会因为中间额度空出来了，第二次提交就悄悄变成申请成功。
- 续约/释放：按各自 `requestId` 记录首次操作结论，重复操作返回同一结论。
- 所有写操作在 `LeaseService` 的同一把公平锁内串行；配合资源池 CAS，
  保证并发申请不超卖、重复释放不重复归还、回收/释放竞争结果唯一。
- 到期有两个入口（定时扫描 `LeaseReaper` 与各操作中的惰性判定），
  但终结逻辑唯一且受锁保护。

### 5.1 请求标识在申请 / 续约 / 释放之间的复用与冲突规则

一个 `requestId` 第一次使用后即“钉住”自己的首次结论与归属租约，之后在
申请、续约、释放三个操作之间复用时结论确定、可区分，且**任何复用路径都不会
退化成 500 内部错误，也不会多占额度或改动整池占用**：

| 首次结论 | 再用于申请 | 再用于续约/释放（同一租约） | 再用于续约/释放（其它租约） |
| ---- | ---- | ---- | ---- |
| 团队额度被拒 `TEAM_QUOTA_EXCEEDED`(429) | 复现 429 | `IDEMPOTENCY_KEY_CONFLICT`(409)，文案带首次拒绝码 | 409 |
| 整池已满被拒 `QUOTA_EXHAUSTED`(503) | 复现 503（即使后来池位空出） | 409，文案带首次拒绝码 | 409 |
| 供应失败 `PROVISION_FAILED`(502)，已补偿 | 复现 502 | 409（该租约为 FAILED 终态） | 409 |
| 成功（租约 ACTIVE/已终结） | 返回同一租约视图 | 允许：续约顺延到期；释放按当前终态结论返回 | 409 |

补充约定：

- **先判首次申请结论，再判操作幂等**：续约/释放入口先核对该标识是否属于本次
  目标租约、首次是否被拒，再查续约/释放的操作幂等记录；避免一个标识先在别的
  租约上留下操作结论后，把不相干租约的结论“劫”给本次操作。
- **反向复用同样拦截**：一个已被续约/释放占用的 `requestId` 不能再拿去发起
  申请，同样返回 409。
- 续约与释放的操作幂等各用自己的 `requestId`（实践中为每次操作分配独立标识）；
- 被拒标识的冲突路径是纯判定，不执行任何 acquire/release，因此首次被拒不会
  因为后续复用而多占额度，“各团队占用之和 == 整池 leased”始终成立。

> 历史坑位：首次申请被拒的记录没有 `leaseId`，续约路径曾直接对其调用
> `leaseId.equals(...)` 触发 NPE，把一个确定性的标识冲突透传成了 500。
> 现由 `LeaseService#conflictRejectedApply` 统一归类为 409。

## 6. 配置（src/main/resources/application.properties）

```properties
lease.pool-capacity=4           # 资源池容量
lease.default-ttl-seconds=60    # ttlSeconds=0 时使用的默认 TTL
lease.max-ttl-seconds=3600      # 允许的最大 TTL
lease.reaper-interval-millis=2000  # 定时回收扫描间隔
#lease.team-quotas.team-a=2     # 静态团队额度（可选）：teamId -> 上限
#lease.team-quotas.team-b=1     # 未配置的团队仅受整池容量约束
```

团队额度除静态配置外，更常用的是在线调整（立即生效、不重启、不拆存量）：

```bash
curl -s -X PUT localhost:8080/api/leases/teams/team-a/quota \
  -H 'Content-Type: application/json' -d '{"limit":2}'   # 设置/调整上限
curl -s -X DELETE localhost:8080/api/leases/teams/team-a/quota  # 清除上限，恢复整池共享
```

## 7. 本地验证（无需联网 / 外部服务）

```bash
# 全量测试（29 个用例）
mvn test

# 只跑某一类场景
mvn test -Dtest=LeaseServiceLifecycleTest        # 生命周期 / 配额 / 申请幂等
mvn test -Dtest=LeaseServiceCompensationTest     # 失败补偿 / 超时回收 / 双重释放
mvn test -Dtest=LeaseServiceConcurrencyTest      # 并发抢占 / 提交竞争 / 释放-回收竞争
mvn test -Dtest=LeaseServiceGuardTest            # 查询可复现 / 状态守卫 / 标识冲突
mvn test -Dtest=LeaseServiceTeamQuotaTest        # 团队额度准入 / 跨团队并发 / 拒绝幂等 / 额度归还 / 在线调整
mvn test -Dtest=RequestIdReuseConflictTest       # 被拒标识跨申请/续约/释放复用：409/复现首次结论/不多占额度/对账
mvn test -Dtest=LeaseControllerIntegrationTest   # HTTP 状态码与错误码端到端（含被拒标识复用续约 409，不再 500）
mvn test -Dtest=TeamQuotaControllerIntegrationTest  # 团队额度 HTTP 端到端（429 / 在线调整 / 对账）

# 启动后手工验证
mvn spring-boot:run
```

手工复现“请求标识先被团队额度拒、再被复用续约”的场景：

```bash
# 1) team-demo 上限设为 1
curl -s -X PUT localhost:8080/api/leases/teams/team-demo/quota \
  -H 'Content-Type: application/json' -d '{"limit":1}'
# 2) 占满 team-demo 的额度
curl -s -X POST localhost:8080/api/leases -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-1","tenantId":"team-demo","ttlSeconds":120}'
# 3) 同团队再申请 -> 首次结论 429 TEAM_QUOTA_EXCEEDED
curl -i -X POST localhost:8080/api/leases -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-rej","tenantId":"team-demo","ttlSeconds":120}'
# 4) 另一个团队申请一个正在使用的环境，记下 leaseId
curl -s -X POST localhost:8080/api/leases -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-2","tenantId":"team-other","ttlSeconds":120}'
# 5) 拿 demo-rej 去续约那个环境 -> 409 IDEMPOTENCY_KEY_CONFLICT（修复前为 500）
curl -i -X POST localhost:8080/api/leases/<leaseId>/renew \
  -H 'Content-Type: application/json' -d '{"requestId":"demo-rej","ttlSeconds":120}'
# 6) 拿 demo-rej 去释放同样 409；再拿 demo-rej 重新申请，仍复现 429（额度空出也一样）
curl -i -X POST localhost:8080/api/leases/<leaseId>/release \
  -H 'Content-Type: application/json' -d '{"requestId":"demo-rej"}'
```

并发与超时测试通过注入可控 `Clock`（`MutableClock`）驱动时间，结果确定可复现。
单测日志统一打印 **requestId、teamId、团队额度与整池配额变化（used/leased/available）
以及状态迁移**，例如：

```text
quota acquire: leased 0 -> 1, available=3
team quota acquire: teamId=team-a, used 0 -> 1, limit=2
state transition: requestId=req-1, leaseId=..., status null -> PENDING, expiresAt=...
state transition: requestId=req-1, leaseId=..., status PENDING -> ACTIVE, resourceId=sb-...
apply rejected team quota exceeded: requestId=tq-a2, tenantId=team-a, used=1, limit=1
team quota limit updated: teamId=team-a, limit 2 -> 3, used=2
compensation done: requestId=fail-req-1, status PENDING -> FAILED, reason=SimulatedDownstreamException, available=1
state transition (auto-reap): leaseId=..., status ACTIVE -> EXPIRED, available=...
[test] cross-team contention success a=2 b=2 c=2 rejections={...} poolLeased=6/6
[test] balance check: teamUsedSum=2, poolLeased=2/4
```
