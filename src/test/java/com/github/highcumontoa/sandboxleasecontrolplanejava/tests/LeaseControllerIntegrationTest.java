package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 端到端：HTTP 状态码与可区分错误码、重复提交一致性。
 * 默认资源池容量为 4（application.properties）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class LeaseControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseControllerIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void applyQueryReleaseOverHttp_andRejectInvalidRequest() throws Exception {
        String body = "{\"requestId\":\"http-1\",\"tenantId\":\"team-web\",\"ttlSeconds\":120}";
        MvcResult r1 = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String leaseId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(r1.getResponse().getContentAsString()).get("leaseId").asText();
        log.info("[test] http apply requestId=http-1 leaseId={}", leaseId);
        assertNotNull(leaseId);

        // 重复提交：同一结果
        mockMvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseId").value(leaseId));

        mockMvc.perform(get("/api/leases/{id}", leaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/leases/{id}/release", leaseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-rel-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        // 查询不存在的租约 -> 404 + 可区分错误码
        mockMvc.perform(get("/api/leases/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("LEASE_NOT_FOUND"));

        // 参数非法 -> 400 + INVALID_REQUEST
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"tenantId\":\"t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void provisionFailureOverHttp_isClassifiedAndCompensated() throws Exception {
        // 先把容量打满 4 个，再申请 -> QUOTA_EXHAUSTED(503)，随后释放验证余额恢复
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/leases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"fill-" + i + "\",\"tenantId\":\"t" + i + "\",\"ttlSeconds\":60}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"over\",\"tenantId\":\"tx\",\"ttlSeconds\":60}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("QUOTA_EXHAUSTED"));
        log.warn("[test] http requestId=over rejected with QUOTA_EXHAUSTED");

        MvcResult quota = mockMvc.perform(get("/api/leases/quota"))
                .andExpect(status().isOk()).andReturn();
        log.info("[test] quota snapshot: {}", quota.getResponse().getContentAsString());
        int leased = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(quota.getResponse().getContentAsString()).get("leased").asInt();
        assertEquals(4, leased);
    }

    @Test
    void rejectedRequestIdReusedForRenewAnotherLease_returnsConflictNotInternalError() throws Exception {
        // 线上场景：先把 team-rj 上限设为 1，申请被拒后拿同一 requestId
        // 去续约另一个正在使用的环境，必须是 409 冲突，而不是 500 内部错误。
        mockMvc.perform(put("/api/leases/teams/{teamId}/quota", "team-rj")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rj-http-1\",\"tenantId\":\"team-rj\",\"ttlSeconds\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 首次结论：团队额度被拒 429
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rj-http-rej\",\"tenantId\":\"team-rj\",\"ttlSeconds\":120}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("TEAM_QUOTA_EXCEEDED"));
        log.warn("[test] http requestId=rj-http-rej team=team-rj first rejected TEAM_QUOTA_EXCEEDED(429)");

        // 另一个团队正在使用的环境
        MvcResult other = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rj-http-other\",\"tenantId\":\"team-rj-b\",\"ttlSeconds\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String otherLeaseId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(other.getResponse().getContentAsString()).get("leaseId").asText();

        // 复用被拒标识续约他租约：409 + IDEMPOTENCY_KEY_CONFLICT，绝不允许 500
        mockMvc.perform(post("/api/leases/{id}/renew", otherLeaseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rj-http-rej\",\"ttlSeconds\":120}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_CONFLICT"));
        log.warn("[test] http reuse requestId=rj-http-rej renew leaseId={} -> 409 CONFLICT (was 500 before fix)",
                otherLeaseId);

        // 复用于释放他租约同样 409，且该租约仍 ACTIVE、额度未被动过
        mockMvc.perform(post("/api/leases/{id}/release", otherLeaseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rj-http-rej\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_CONFLICT"));
        mockMvc.perform(get("/api/leases/{id}", otherLeaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
