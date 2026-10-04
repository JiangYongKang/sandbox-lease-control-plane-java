package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.web;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseService;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamLeasesView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamQuotaUpdateRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamQuotaView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 团队额度查询 / 调整与团队租约清单接口。 */
@RestController
@RequestMapping("/api/teams")
public class TeamQuotaController {

    private final LeaseService leaseService;

    public TeamQuotaController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /** 查询团队额度使用（上限 / 当前占用 / 剩余）与整池快照。 */
    @GetMapping("/{tenantId}/quota")
    public TeamQuotaView teamQuota(@PathVariable String tenantId) {
        return leaseService.teamQuota(tenantId);
    }

    /** 查询团队当前持有的非终态租约。 */
    @GetMapping("/{tenantId}/leases")
    public TeamLeasesView teamLeases(@PathVariable String tenantId) {
        return leaseService.teamLeases(tenantId);
    }

    /** 在线调整团队额度；limit=null 移除配置，立即对后续申请生效。 */
    @PutMapping("/{tenantId}/quota")
    public TeamQuotaView configure(@PathVariable String tenantId,
                                   @RequestBody(required = false) TeamQuotaUpdateRequest request) {
        Integer limit = request == null ? null : request.limit();
        return leaseService.configureTeamQuota(tenantId, limit);
    }
}
