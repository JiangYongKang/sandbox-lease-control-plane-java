# 临时环境租约控制平面（Sandbox Lease Control Plane）

面向内部开发平台的临时环境租约服务。**全部状态保存在进程内**（资源池、租约表、幂等结论、底层环境句柄均为内存对象），不依赖数据库、消息队列或任何外部服务/凭据，可以离线构建与验证。

- Spring Boot 3.4 / Java 21（在 JDK 25 上构建）
- 并发安全：单写锁串行化状态迁移 + 资源池 CAS
- 幂等：以 `requestId`（请求体字段或 `Idempotency-Key` 请求头）回放首次结论
- 申请失败补偿：预留配额与底层句柄在任何失败路径上都会被撤销/反注册

---

## 1. 租约状态机

```
                 acquire（预留配额 + provision 成功）
   (不存在) ───────────────────────────────────────► ACTIVE
       │                                                │  │
       │ acquire 任一步失败                              │  │ release()（主动释放，先拿到锁）
       │ 补偿后保留终态记录                              │  ▼
       ▼                                            RELEASED（终态）
   EXPIRED(compensated)（终态）                          │
                                                     不可续约/再释放
   ACTIVE ── expiresAt 到期 ──► EXPIRED（终态）
             ├─ 定时回收器 LeaseReaper 扫描
             ├─ 查询 GET 时惰性回收
             └─ 续约 renew 时惰性回收（并拒绝续约）

   ACTIVE ── renew() ──► ACTIVE（expiresAt 延长，version+1，不重复扣配额）
```

状态迁移规则：

| 当前状态 | 操作 | 结果 |
|---|---|---|
| 不存在 | acquire | 成功则 ACTIVE；任何步骤失败则补偿，保留一条带 `compensated=true` 的 EXPIRED 记录用于追溯 |
| ACTIVE（未到期） | renew | ACTIVE，到期时间延后（受最大 TTL 钳制），不占用新配额 |
| ACTIVE | release | RELEASED，反注册句柄并归还 1 个配额 |
| ACTIVE（已过 expiresAt） | 自动回收 / 查询 / 续约 | EXPIRED，反注册句柄并归还配额；续约得到 `LEASE_EXPIRED` |
| ACTIVE（已过期但回收器尚未终结） | release | **谁先拿到锁谁裁决**：释放先到 → RELEASED；回收先到 → 释放得到 `LEASE_EXPIRED`。两种情况下配额都只归还一次 |
| RELEASED / EXPIRED | renew / release | 拒绝（`LEASE_RELEASED` / `LEASE_EXPIRED`），不改变配额 |

每次迁移都会使租约 `version + 1`，并打印状态迁移日志。终态不可逆、不可重复终结。

## 2. 配额规则

- `lease.pool-capacity`（默认 8）个同质环境槽位，由 `ResourcePool` 维护 `capacity / occupied / available`。
- 申请时先 `reserve()`：`AtomicInteger` CAS 循环，并发抢占下成功数恰好等于容量，绝不超发。
- 释放 / 自动回收 / 失败补偿归还配额时使用 CAS 钳制，余额不可能被“还穿”到负数。
- 续约不扣配额；任何拒绝路径都不改变配额。
- `GET /api/leases/pool/status` 返回容量快照与 ACTIVE 租约数，可用于对账（`occupied == ACTIVE 租约数`，测试中断言该不变量）。

## 3. 幂等与可复现结论

- 幂等键：请求头 `Idempotency-Key` 优先，其次使用请求体 `requestId`；为空返回 `INVALID_REQUEST`。
- 每个 requestId 的**首次结论**（成功时的租约快照，或失败时的错误码与消息）被保存：
  - 重复提交直接回放同一结果（响应中 `idempotentReplay=true`），不会第二次占用配额或分配句柄；
  - 即使首次是 `QUOTA_EXHAUSTED` / `PROVISIONING_FAILED` 失败，重复提交也永远得到同一失败结论，即使期间配额已空出（避免悄悄成功造成调用方困惑）；
  - 同一 requestId 携带不同参数/操作 → `IDEMPOTENCY_KEY_CONFLICT`，不改动既有结论。
- 查询接口无副作用：同一租约重复查询得到相同终态。

## 4. 申请链路失败补偿

申请严格按以下顺序推进，失败按**反向顺序**补偿：

```
校验参数 → reserve 配额 → 落库 ACTIVE 租约 → provisioner 分配句柄 → 保存成功结论
                     │ 失败：               │ 失败：
                     │ 无需动作             │ deprovision（若有句柄，幂等）
                     │                      │ 租约置 EXPIRED(compensated)
                     │                      │ pool.release() 归还配额
                     ▼                      ▼
              记录失败结论（可回放），抛出可区分错误码
```

- 底层 `provisioner` 抛出的是**基础设施异常**（`ProvisionerInfraException`，不含业务错误码），对外统一归类为 `PROVISIONING_FAILED`（HTTP 502），底层异常类型/堆栈不直接透出；未预期异常统一收敛为 `INTERNAL_ERROR`。
- 补偿动作本身吞掉二次异常并记录 error 日志，保证“状态 + 句柄 + 配额”最终一致：失败后 `occupied` 不变、底层句柄数为 0、无悬挂 ACTIVE 租约，且随后新的申请可以复用该配额。

## 5. 对外错误码

| errorCode | HTTP | 含义 |
|---|---|---|
| `INVALID_REQUEST` | 400 | requestId/envType/owner 为空、TTL 非法、请求体不可解析 |
| `LEASE_NOT_FOUND` | 404 | 租约不存在 |
| `QUOTA_EXHAUSTED` | 409 | 资源池已满（可重试：等其它租约释放后用**新的 requestId**） |
| `LEASE_EXPIRED` | 409 | 租约已过期/被回收，操作不允许 |
| `LEASE_RELEASED` | 409 | 租约已释放，操作不允许（含重复释放） |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 幂等键已绑定其它请求/其它操作 |
| `PROVISIONING_FAILED` | 502 | 底层环境分配失败，已完成补偿 |
| `INTERNAL_ERROR` | 500 | 未预期异常（不透出底层细节） |

## 6. HTTP API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/leases` | 申请租约；请求头可带 `Idempotency-Key` |
| POST | `/api/leases/{id}/renew` | 续约（body: `requestId`, `ttlSeconds`） |
| POST | `/api/leases/{id}/release` | 释放（body: `requestId`） |
| GET | `/api/leases/{id}` | 查询（必要时惰性回收） |
| GET | `/api/leases/pool/status` | 配额/活跃租约对账视图 |

申请请求示例：

```bash
curl -s -X POST http://localhost:8080/api/leases \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-req-001' \
  -d '{"envType":"devbox","owner":"alice","ttlSeconds":300}'
```

申请体可加 `"simulateProvisioningFailure": true` 进行本地故障注入，观察补偿与错误归类。
续约/释放/申请的失败与成功结论都会以 requestId 为键回放。

## 7. 配置（`src/main/resources/application.properties`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `lease.pool-capacity` | 8 | 资源池容量 |
| `lease.default-ttl-seconds` | 300 | 未指定 TTL 时的默认租约时长 |
| `lease.max-ttl-seconds` | 3600 | TTL 上限（续约同样受钳制） |
| `lease.reaper.interval-millis` | 1000 | 自动回收扫描间隔 |
| `lease.auto-reclaim-enabled` | true | 预留开关（调度由 `@EnableScheduling` 开启） |

## 8. 本地验证

```bash
# 编译
mvn -o compile

# 全量测试（离线；22 个用例，覆盖并发抢占 / 重复提交 / 失败补偿 / 过期竞争）
mvn -o test

# 查看含 requestId、配额变化、状态迁移的测试日志
mvn -o test -Dtest=LeaseServiceTest
cat target/surefire-reports/com.github.highcumontoa.sandboxleasecontrolplanejava.tests.LeaseServiceTest.txt

# 启动后手工验证
mvn -o spring-boot:run
```

测试覆盖一览：

- `ResourcePoolTest`：64 线程抢占 10 槽位，成功数恰好为容量；超额归还被忽略。
- `LeaseServiceTest`（13 例）：完整生命周期与配额核算；重复申请同键回放且不重复占用；同键不同载荷拒绝；配额耗尽错误可区分且可回放；**provision 失败后配额/句柄/租约三者全部回滚补偿**，失败结论可回放，补偿后配额可复用；32 线程并发申请不超发；20 线程对同一热键并发提交只产生一个结果；**自动回收与主动释放竞争时终态/配额唯一确定**；重复释放、终态后续约原因可区分；过期租约处理；参数校验；未知租约 404。
- `LeaseReaperIntegrationTest`：真实调度线程 + 可控时钟，到期后自动 EXPIRED、配额与句柄归零且余额可复用。
- `LeaseControllerWebTest`（6 例）：HTTP 状态码映射、错误码区分、`Idempotency-Key` 头回放。

日志中统一打印 `requestId`、租约状态迁移（如 `ACTIVE->RELEASED`）、配额变化（`quota=occupied/capacity`、`available`），便于复现每一次并发裁决。
