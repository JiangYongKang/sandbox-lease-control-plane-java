package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 团队额度 REST 端到端：查询、在线调整、拒绝码与整池快照一致性。
 *
 * <p>application.properties 默认给 team-a=2 / team-b=1；本类每个测试方法前后都重建
 * Spring 上下文（BEFORE_EACH_TEST_METHOD），测试前资源池干净、方法结束后也不把占用过的
 * 进程内资源池留给后续复用同一上下文的其它集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class TeamQuotaControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TeamQuotaControllerIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void teamQuota_queryConfigureAdjustAndRejectOverHttp() throws Exception {
        // 默认配置：team-a 上限 2，初始占用 0；视图同时带整池快照
        mockMvc.perform(get("/api/teams/team-a/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("team-a"))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.leased").value(0))
                .andExpect(jsonPath("$.remaining").value(2))
                .andExpect(jsonPath("$.poolCapacity").value(4))
                .andExpect(jsonPath("$.poolLeased").value(0));
        log.info("[test] http GET /api/teams/team-a/quota default limit=2 leased=0 pool=0/4");

        // 在线调整为 1 立即生效
        mockMvc.perform(put("/api/teams/team-a/quota")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.remaining").value(1));
        log.info("[test] http PUT team-a limit=1 effective immediately");

        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-ta-1\",\"tenantId\":\"team-a\",\"ttlSeconds\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 第二发：团队额度满 -> 503 + TEAM_QUOTA_EXHAUSTED（此时整池只占 1/4）
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-ta-2\",\"tenantId\":\"team-a\",\"ttlSeconds\":60}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("TEAM_QUOTA_EXHAUSTED"));
        log.warn("[test] http requestId=http-ta-2 tenantId=team-a rejected TEAM_QUOTA_EXHAUSTED while pool=1/4");

        // 重复提交同一 requestId：仍是同一拒绝
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-ta-2\",\"tenantId\":\"team-a\",\"ttlSeconds\":60}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("TEAM_QUOTA_EXHAUSTED"));

        // 团队租约清单：1 条 ACTIVE
        mockMvc.perform(get("/api/teams/team-a/leases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leased").value(1))
                .andExpect(jsonPath("$.leases.length()").value(1))
                .andExpect(jsonPath("$.leases[0].status").value("ACTIVE"));
        log.info("[test] http GET /api/teams/team-a/leases leased=1 listed=1");

        // 团队视图与整池 quota 口径一致：poolLeased 必须也是 1
        mockMvc.perform(get("/api/teams/team-a/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leased").value(1))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.poolLeased").value(1))
                .andExpect(jsonPath("$.poolAvailable").value(3));
        mockMvc.perform(get("/api/leases/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leased").value(1))
                .andExpect(jsonPath("$.capacity").value(4));
        log.info("[test] team view poolLeased=1 matches GET /api/leases/quota leased=1");

        // 移除配置 -> 204 场景不需要；limit=null 后团队视图 limit 字段不存在/null
        mockMvc.perform(put("/api/teams/team-a/quota")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").doesNotExist())
                .andExpect(jsonPath("$.remaining").doesNotExist())
                .andExpect(jsonPath("$.leased").value(1));
        log.info("[test] http PUT team-a limit=null -> pool-only, existing lease untouched");

        // 非法额度 -> 400 INVALID_REQUEST
        mockMvc.perform(put("/api/teams/team-a/quota")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        log.warn("[test] http PUT team-a limit=-5 rejected INVALID_REQUEST");
    }

    @Test
    void unconfiguredTeam_quotaViewHasNullLimitAndIsPoolBounded() throws Exception {
        // 全新上下文：本测试类独立上下文内上一个测试也可能调整状态，故选用全新团队名
        mockMvc.perform(get("/api/teams/team-web-unconfigured/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").doesNotExist())
                .andExpect(jsonPath("$.remaining").doesNotExist())
                .andExpect(jsonPath("$.leased").value(0));
        log.info("[test] unconfigured tenantId=team-web-unconfigured view limit=null leased=0");
    }
}
