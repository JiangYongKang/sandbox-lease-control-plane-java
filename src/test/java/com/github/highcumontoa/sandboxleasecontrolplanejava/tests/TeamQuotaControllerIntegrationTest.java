package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 团队额度 HTTP 端到端：在线调整、可区分错误码（429 TEAM_QUOTA_EXCEEDED）、
 * 团队视图与整池配额对账。使用独立应用上下文，避免与其它集成测试共享池状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class TeamQuotaControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TeamQuotaControllerIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void teamQuotaOverHttp_adjustEnforceQueryAndClear() throws Exception {
        // 在线设置 team-web 上限为 1
        mockMvc.perform(put("/api/leases/teams/{teamId}/quota", "team-web")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.remaining").value(1));
        log.info("[test] http team=team-web quota limit set to 1");

        // 第一个申请成功
        MvcResult applied = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hq-1\",\"tenantId\":\"team-web\",\"ttlSeconds\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String leaseId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(applied.getResponse().getContentAsString()).get("leaseId").asText();

        // 第二个申请被团队额度拒绝：429 + 可区分错误码
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hq-2\",\"tenantId\":\"team-web\",\"ttlSeconds\":120}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("TEAM_QUOTA_EXCEEDED"));
        log.warn("[test] http requestId=hq-2 team=team-web rejected TEAM_QUOTA_EXCEEDED(429)");

        // 重复提交同一 requestId：即使之后额度空出也必须是同一拒绝结论
        mockMvc.perform(post("/api/leases/{id}/release", leaseId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"hq-rel-1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hq-2\",\"tenantId\":\"team-web\",\"ttlSeconds\":120}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("TEAM_QUOTA_EXCEEDED"));

        // 团队视图：上限/占用/剩余 + 当前挂着的租约，且与整池配额对得上
        mockMvc.perform(get("/api/leases/teams/{teamId}/quota", "team-web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.remaining").value(1));
        mockMvc.perform(get("/api/leases/teams/{teamId}/leases", "team-web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        MvcResult pool = mockMvc.perform(get("/api/leases/quota"))
                .andExpect(status().isOk()).andReturn();
        log.info("[test] http pool quota after release: {}", pool.getResponse().getContentAsString());

        // 非法调整 -> 400 INVALID_REQUEST
        mockMvc.perform(put("/api/leases/teams/{teamId}/quota", "team-web")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // 清除上限 -> 恢复整池共享，limit/remaining 为 null
        mockMvc.perform(delete("/api/leases/teams/{teamId}/quota", "team-web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").doesNotExist())
                .andExpect(jsonPath("$.remaining").doesNotExist());
        log.info("[test] http team=team-web quota limit cleared");
    }
}
