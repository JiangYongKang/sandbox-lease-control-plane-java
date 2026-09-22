package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 端到端：HTTP 状态码与可区分错误码、重复提交一致性。
 * 默认资源池容量为 4（application.properties）。
 */
@SpringBootTest
@AutoConfigureMockMvc
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
}
