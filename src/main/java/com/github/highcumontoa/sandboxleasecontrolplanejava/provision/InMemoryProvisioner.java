package com.github.highcumontoa.sandboxleasecontrolplanejava.provision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内实现：只记录已分配句柄，用来校验“申请失败/释放后不残留底层资源”。
 * 抛出的异常刻意使用底层基础设施异常的形态（不带业务错误码），
 * 由上层统一归类为对外可区分错误，模拟真实环境里 provisioner 失败的情形。
 */
@Component
public class InMemoryProvisioner implements EnvironmentProvisioner {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProvisioner.class);

    private final Set<String> handles = ConcurrentHashMap.newKeySet();

    @Override
    public String provision(String leaseId, String envType, boolean failWhenTrue) {
        if (failWhenTrue) {
            throw new ProvisionerInfraException(
                    "infrastructure provision failed for lease=" + leaseId + " envType=" + envType);
        }
        String handle = "env-" + envType + "-" + UUID.randomUUID();
        handles.add(handle);
        log.info("[provisioner] handle allocated: lease={} envType={} handle={} activeHandles={}",
                leaseId, envType, handle, handles.size());
        return handle;
    }

    @Override
    public void deprovision(String handle) {
        if (handle == null) {
            return;
        }
        boolean removed = handles.remove(handle);
        if (removed) {
            log.info("[provisioner] handle released: handle={} activeHandles={}", handle, handles.size());
        } else {
            // 幂等回收：句柄已不存在时不报错（补偿/重复回收路径会走到这里）
            log.warn("[provisioner] handle already absent on release: handle={}", handle);
        }
    }

    /** 测试断言用：当前仍挂在底层 provisioner 上的句柄数。 */
    public int activeHandleCount() {
        return handles.size();
    }

    /** 测试辅助：句柄是否仍存在。 */
    public boolean containsHandle(String handle) {
        return handles.contains(handle);
    }

    /**
     * 模拟底层基础设施异常（不实现业务错误码，验证上层归类逻辑）。
     */
    public static class ProvisionerInfraException extends RuntimeException {
        public ProvisionerInfraException(String message) {
            super(message);
        }
    }
}
