package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.web;

import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.LeaseService;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.LeaseView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.QuotaView;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.ReleaseRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.RenewRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamQuotaUpdateRequest;
import com.github.highcumontoa.sandboxleasecontrolplanejava.lease.api.TeamQuotaView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 临时环境租约 REST 接口。 */
@RestController
@RequestMapping("/api/leases")
public class LeaseController {

    private static final Logger log = LoggerFactory.getLogger(LeaseController.class);

    private final LeaseService leaseService;

    public LeaseController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    @PostMapping
    public LeaseView apply(@RequestBody LeaseRequest request) {
        log.info("http apply: requestId={}, tenantId={}",
                request == null ? null : request.requestId(),
                request == null ? null : request.tenantId());
        return leaseService.apply(request);
    }

    @PostMapping("/{leaseId}/renew")
    public LeaseView renew(@PathVariable String leaseId, @RequestBody RenewRequest request) {
        log.info("http renew: requestId={}, leaseId={}",
                request == null ? null : request.requestId(), leaseId);
        return leaseService.renew(leaseId, request);
    }

    @PostMapping("/{leaseId}/release")
    public LeaseView release(@PathVariable String leaseId,
                             @RequestBody(required = false) ReleaseRequest request) {
        log.info("http release: requestId={}, leaseId={}",
                request == null ? null : request.requestId(), leaseId);
        return leaseService.release(leaseId, request);
    }

    @GetMapping("/{leaseId}")
    public LeaseView get(@PathVariable String leaseId) {
        log.info("http query: leaseId={}", leaseId);
        return leaseService.get(leaseId);
    }

    @GetMapping("/quota")
    public QuotaView quota() {
        return leaseService.quota();
    }

    /** 团队额度快照：上限/当前占用/剩余。 */
    @GetMapping("/teams/{teamId}/quota")
    public TeamQuotaView teamQuota(@PathVariable String teamId) {
        return leaseService.teamQuota(teamId);
    }

    /** 团队当前持有的租约（非终态）。 */
    @GetMapping("/teams/{teamId}/leases")
    public List<LeaseView> teamLeases(@PathVariable String teamId) {
        return leaseService.teamLeases(teamId);
    }

    /** 在线设置团队额度上限，对后续申请立即生效，不影响存量租约。 */
    @PutMapping("/teams/{teamId}/quota")
    public TeamQuotaView updateTeamQuota(@PathVariable String teamId,
                                         @RequestBody TeamQuotaUpdateRequest request) {
        log.info("http update team quota: teamId={}, limit={}",
                teamId, request == null ? null : request.limit());
        return leaseService.updateTeamQuota(teamId, request);
    }

    /** 清除团队额度上限，恢复整池共享约束。 */
    @DeleteMapping("/teams/{teamId}/quota")
    public TeamQuotaView clearTeamQuota(@PathVariable String teamId) {
        log.info("http clear team quota: teamId={}", teamId);
        return leaseService.updateTeamQuota(teamId, null);
    }
}
