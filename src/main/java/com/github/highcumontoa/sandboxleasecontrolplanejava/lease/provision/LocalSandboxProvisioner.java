package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 纯本地、确定性的沙箱供应器：不依赖任何外部服务、真实凭据或付费组件。
 *
 * <p>失败注入规则（便于可复现的失败补偿测试）：
 * <ul>
 *   <li>{@code tenantId} 以 {@code fail-once:} 开头：该租户第一次申请抛
 *       {@link SimulatedFlakeException}（模拟底层瞬时抖动），后续申请成功，
 *       用来验证“同一 requestId 重试得到同一 FAILED 结论”而非第二次成功；</li>
 *   <li>{@code tenantId} 以 {@code fail:} 开头：始终抛出确定性失败；</li>
 *   <li>其它情况：分配一个本地资源标识表示成功。</li>
 * </ul>
 */
@Component
public class LocalSandboxProvisioner implements SandboxProvisioner {

    /** 模拟底层瞬时故障类型，调用方必须将其归类而不是原样透出。 */
    public static final class SimulatedFlakeException extends SandboxProvisionException {
        public SimulatedFlakeException(String message) {
            super(message);
        }
    }

    /** 模拟底层确定性故障类型。 */
    public static final class SimulatedDownstreamException extends SandboxProvisionException {
        public SimulatedDownstreamException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LocalSandboxProvisioner.class);

    private final Set<String> flakeFiredTenants = new HashSet<>();

    @Override
    public String provision(String leaseId, String tenantId) {
        log.info("provisioning sandbox: leaseId={}, tenantId={}", leaseId, tenantId);

        if (tenantId.startsWith("fail:")) {
            log.warn("provision deterministic failure injected: leaseId={}, tenantId={}", leaseId, tenantId);
            throw new SimulatedDownstreamException(
                    "simulated downstream unavailable for tenant " + tenantId);
        }

        if (tenantId.startsWith("fail-once:")) {
            boolean shouldFail;
            synchronized (flakeFiredTenants) {
                shouldFail = flakeFiredTenants.add(tenantId);
            }
            if (shouldFail) {
                log.warn("provision flake failure injected: leaseId={}, tenantId={}", leaseId, tenantId);
                throw new SimulatedFlakeException(
                        "simulated transient flake for tenant " + tenantId);
            }
        }

        String resourceId = "sb-" + UUID.randomUUID();
        log.info("provision succeeded: leaseId={}, resourceId={}", leaseId, resourceId);
        return resourceId;
    }

    @Override
    public void deprovision(String resourceId) {
        if (resourceId == null) {
            return;
        }
        log.info("deprovisioned sandbox in compensation/release path: resourceId={}", resourceId);
    }
}
