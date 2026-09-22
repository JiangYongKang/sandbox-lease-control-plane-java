package com.github.highcumontoa.sandboxleasecontrolplanejava.tests;

import com.github.highcumontoa.sandboxleasecontrolplanejava.config.LeaseProperties;
import com.github.highcumontoa.sandboxleasecontrolplanejava.provision.InMemoryProvisioner;
import com.github.highcumontoa.sandboxleasecontrolplanejava.quota.ResourcePool;
import com.github.highcumontoa.sandboxleasecontrolplanejava.repository.LeaseStore;
import com.github.highcumontoa.sandboxleasecontrolplanejava.service.LeaseService;
import com.github.highcumontoa.sandboxleasecontrolplanejava.web.GlobalExceptionHandler;
import com.github.highcumontoa.sandboxleasecontrolplanejava.web.LeaseController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层切片测试（standalone MockMvc，不启动容器）：
 * 校验 HTTP 状态码、错误码区分、幂等头回放。
 */
class LeaseControllerWebTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseControllerWebTest.class);

    private MockMvc mvc;
    private ResourcePool pool;

    @BeforeEach
    void setUp() {
        LeaseProperties props = new LeaseProperties();
        props.setPoolCapacity(2);
        props.setDefaultTtlSeconds(60);
        props.setMaxTtlSeconds(600);
        LeaseStore store = new LeaseStore();
        pool = new ResourcePool(props);
        InMemoryProvisioner provisioner = new InMemoryProvisioner();
        Clock clock = new TestClock();
        LeaseService service = new LeaseService(store, pool, provisioner, props, clock);
        mvc = MockMvcBuilders.standaloneSetup(new LeaseController(service))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }

    private String acquireBody(String requestId, String owner, boolean failInjected) {
        return "{\"requestId\":\"" + requestId + "\",\"envType\":\"devbox\",\"owner\":\"" + owner
                + "\",\"ttlSeconds\":60,\"simulateProvisioningFailure\":" + failInjected + "}";
    }

    @Test
    void acquireThenGetThenReleaseOverHttp() throws Exception {
        MvcResult created = mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acquireBody("http-req-1", "alice", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.requestId").value("http-req-1"))
                .andExpect(jsonPath("$.quotaOccupied").value(1))
                .andReturn();
        log.info("[web-test] acquire httpStatus=201 body={}", created.getResponse().getContentAsString());

        String leaseId = com.jayway.jsonpath.JsonPath
                .read(created.getResponse().getContentAsString(), "$.leaseId");

        mvc.perform(get("/api/leases/" + leaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(post("/api/leases/" + leaseId + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-release-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.quotaOccupied").value(0));
        assertEquals(0, pool.getOccupied());
    }

    @Test
    void idempotencyKeyHeaderDrivesReplay() throws Exception {
        String body = acquireBody("ignored-body-id", "alice", false);
        MvcResult first = mvc.perform(post("/api/leases")
                        .header("Idempotency-Key", "header-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mvc.perform(post("/api/leases")
                        .header("Idempotency-Key", "header-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andReturn();
        log.info("[web-test] header idempotency first/second requests replayed");
        String id1 = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.leaseId");
        String id2 = com.jayway.jsonpath.JsonPath.read(second.getResponse().getContentAsString(), "$.leaseId");
        assertEquals(id1, id2);
        assertEquals(1, pool.getOccupied());
    }

    @Test
    void quotaExhaustionMapsTo409WithDistinctCode() throws Exception {
        mvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON)
                        .content(acquireBody("c1", "alice", false)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON)
                        .content(acquireBody("c2", "alice", false)))
                .andExpect(status().isCreated());
        MvcResult overflow = mvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON)
                        .content(acquireBody("c3", "alice", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("QUOTA_EXHAUSTED"))
                .andReturn();
        log.info("[web-test] overflow httpStatus=409 body={}", overflow.getResponse().getContentAsString());
    }

    @Test
    void provisioningFailureMapsTo502AndKeepsQuota() throws Exception {
        MvcResult result = mvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON)
                        .content(acquireBody("c-fail", "alice", true)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("PROVISIONING_FAILED"))
                .andReturn();
        log.info("[web-test] provision failure httpStatus=502 body={}",
                result.getResponse().getContentAsString());
        assertEquals(0, pool.getOccupied());
    }

    @Test
    void invalidAndUnknownRequestsHaveMappedStatus() throws Exception {
        mvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"envType\":\"devbox\",\"owner\":\"alice\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        mvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        mvc.perform(get("/api/leases/lease-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LEASE_NOT_FOUND"));
    }

    @Test
    void releasedLeaseOperationsMapTo409WithDistinctCodes() throws Exception {
        MvcResult created = mvc.perform(post("/api/leases").contentType(MediaType.APPLICATION_JSON)
                        .content(acquireBody("r1", "alice", false)))
                .andExpect(status().isCreated()).andReturn();
        String leaseId = com.jayway.jsonpath.JsonPath
                .read(created.getResponse().getContentAsString(), "$.leaseId");

        mvc.perform(post("/api/leases/" + leaseId + "/release")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"rel1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/leases/" + leaseId + "/release")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"rel2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LEASE_RELEASED"));
        mvc.perform(post("/api/leases/" + leaseId + "/renew")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"ren1\",\"ttlSeconds\":60}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LEASE_RELEASED"));
    }
}
