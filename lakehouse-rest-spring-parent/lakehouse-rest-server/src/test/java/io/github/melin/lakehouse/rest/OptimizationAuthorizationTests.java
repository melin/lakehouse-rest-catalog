package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobRepository;
import io.github.melin.lakehouse.rest.support.Paging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 优化批次端点在授权开启时的行为（回归：非管理员查询批次列表 500）。
 *
 * <p>控制台用普通 principal（如 {@code regress-principal}）登录后打开「优化任务」页，
 * {@code GET /optimization/jobs} 走的是「可见性逐条判定」分支——与管理员（直接放行）
 * 唯一的差异就在 {@code hasAnyGrant} 这条链路。本类用它自己的 H2 库复现该场景，
 * 守住「无授权主体得到的是空列表或 403，而不是 500」这条契约。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.authorization.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:paimon-optauthz;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OptimizationAuthorizationTests {

    private static final String M = "/api/management/v1/optimization";
    private static final String ROOT = "root";
    private static final String ALICE = "regress-principal";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CatalogRepository catalogRepository;
    @Autowired
    private OptimizationJobRepository jobRepository;

    private String catalogId;

    @BeforeEach
    void seedCatalogAndJob() {
        CatalogEntity catalog = catalogRepository.findByPrefix("paimon").orElseGet(() -> {
            CatalogEntity entity = new CatalogEntity();
            entity.setId(Paging.newId());
            entity.setPrefix("paimon");
            entity.setCatalogFormat("PAIMON");
            entity.markCreated("optimization-authz-tests", 1L);
            return catalogRepository.save(entity);
        });
        catalogId = catalog.getId();
    }

    /** 造一个已终态的批次（不经过提交链路，只喂列表查询）。 */
    private void seedJob(String id) {
        OptimizationJobEntity job = new OptimizationJobEntity();
        job.setId(id);
        job.setCatalogId(catalogId);
        job.setTriggerType("SCHEDULED");
        job.setActions("compact");
        job.setStatus("SUCCESS");
        job.setTableCount(1);
        job.setCallbackToken(Paging.newId());
        jobRepository.save(job);
    }

    private MvcResult getJobs(String asPrincipal, String query) throws Exception {
        return mockMvc.perform(get(M + "/jobs" + (query == null ? "" : "?" + query))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + asPrincipal))
                .andReturn();
    }

    @Test
    void nonAdminListingJobsDoesNotFail() throws Exception {
        seedJob("job-authz-1");
        seedJob("job-authz-2");

        // 无授权主体：批次属于它能看见的 catalog 吗？不可见——但必须是 200 + 空列表，
        // 绝不能 500（「看不到 catalog」不该通过优化任务列表泄露，更不该变成服务端错误）
        MvcResult result = getJobs(ALICE, null);
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(),
                () -> "non-admin listing jobs failed, body=" + body);
        assertTrue(body.contains("\"jobs\"") || body.contains("jobs"),
                () -> "unexpected body: " + body);
    }

    @Test
    void nonAdminListingJobsWithPagingParams() throws Exception {
        seedJob("job-authz-3");
        // 控制台实际发的形状：page/pageSize/limit 等查询参数齐全
        MvcResult result = getJobs(ALICE, "catalog=&status=&action=&trigger=&limit=&page=1&pageSize=20");
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(),
                () -> "non-admin listing jobs with paging failed, body=" + body);
    }

    @Test
    void nonAdminListingJobsOfSpecificCatalogIsForbidden() throws Exception {
        seedJob("job-authz-4");
        // 指名 catalog 且无 DESCRIBE → 403（不是 500）
        MvcResult result = getJobs(ALICE, "catalog=paimon");
        assertEquals(403, result.getResponse().getStatus(),
                () -> "expected 403, got " + result.getResponse().getStatus());
    }

    @Test
    void adminStillListsAllJobs() throws Exception {
        seedJob("job-authz-5");
        MvcResult result = getJobs(ROOT, null);
        assertEquals(200, result.getResponse().getStatus());
    }
}
