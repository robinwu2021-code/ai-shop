package ai.neargo.shop.scenario;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.store.JobDefinitionDao;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营端 · 定时任务。**这个系统的 17 个任务，在这之前没有任何地方能看见。**
 *
 * <p>生产上 {@code sys_job_run} 0 行、{@code shedlock} 0 行 ——
 * 「该跑的没跑」这件事，在这个页面出现之前没有人会发现。
 */
@SpringBootTest
@ActiveProfiles({"test", "ops"})
@TestPropertySource(properties = {
        // 自带一个平台库：@TestPropertySource 会造第二个上下文，
        // 共享的 jdbc:h2:mem:shop 会被 schema-test.sql 跑第二遍
        "spring.datasource.url=jdbc:h2:mem:opsjob-platform;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.job.enabled=true",
        "shop.job.flyway-locations=classpath:db/job-h2",
        "shop.job.datasource.url=jdbc:h2:mem:opsjob;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.job.datasource.username=sa",
        "shop.job.datasource.password=",
})
@DisplayName("运营端 · 定时任务")
class OpsJobFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JobDefinitionDao definitions;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String admin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    /**
     * **每个用例一个独立任务名。**
     *
     * <p>共用一个的话用例之间会串：`upsertFromCode` 刻意不覆盖 cron 与 enabled
     * （那两列归运营，代码永不覆盖 —— 见 DAO 的注释），
     * 于是前一个用例改过的 cron、关掉的开关都会留给下一个，
     * 而失败会指向一个与被测行为无关的地方。
     */
    private String seed(String name) {
        definitions.upsertFromCode(name, JobDeclaration.daily(name, "演示任务",
                "只在测试里存在", "shop-app", "0 0 3 * * *"), "PLATFORM");
        return name;
    }

    @Test
    @DisplayName("★ 列表把「定义」与「当前状态」合成一行 —— 从没跑过时状态为空，不是没有这一行")
    void listMergesDefinitionAndRun() throws Exception {
        seed("demo-list");
        String body = mvc().perform(get("/ops/jobs").header("Authorization", "Bearer " + admin()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        var rows = json.readTree(body).get("data");
        assertThat(rows).isNotEmpty();
        var demo = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(n -> "demo-list".equals(n.get("jobName").asString()))
                .findFirst().orElseThrow();
        assertThat(demo.get("displayName").asString()).isEqualTo("演示任务");
        assertThat(demo.get("lastStatus").isNull())
                .as("从没跑过时 lastStatus 为 null —— 这是今天 17 个任务的普遍状态，"
                    + "页面要显示成「从未执行」而不是空白")
                .isTrue();
    }

    @Test
    @DisplayName("★★ 非法 cron 当场 400 —— 落进去的话页面显示「改成功了」而任务再也排不上")
    void invalidCronIsRejected() throws Exception {
        seed("demo-badcron");
        mvc().perform(put("/ops/jobs/demo-badcron/cron").header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cron\":\"这不是 cron\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));

        assertThat(definitions.findByName("demo-badcron").cron())
                .as("非法 cron 落库了 —— worker 下一轮注册失败，而页面上它还开着")
                .isEqualTo("0 0 3 * * *");
    }

    @Test
    @DisplayName("★ 改 cron 记 updated_by —— 「谁把这个任务改了」必须查得到")
    void cronChangeRecordsOperator() throws Exception {
        seed("demo-cron");
        mvc().perform(put("/ops/jobs/demo-cron/cron").header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cron\":\"0 30 4 * * *\"}"))
                .andExpect(jsonPath("$.code").value(0));

        var row = definitions.findByName("demo-cron");
        assertThat(row.cron()).isEqualTo("0 30 4 * * *");
        assertThat(row.updatedBy()).isNotBlank();
    }

    @Test
    @DisplayName("★★ 立即执行只记请求，不直接跑 —— 运营端与 worker 之间不通信")
    void triggerOnlyRecordsTheRequest() throws Exception {
        seed("demo-trigger");
        mvc().perform(post("/ops/jobs/demo-trigger/trigger").header("Authorization", "Bearer " + admin()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.triggerPending").value(true));

        assertThat(definitions.findTriggerRequested(java.util.Set.of("PLATFORM")))
                .as("worker 下一轮轮询要能捡到它")
                .anyMatch(d -> "demo-trigger".equals(d.jobName()));
    }

    @Test
    @DisplayName("★ 关掉的任务不能被触发 —— 返回错误而不是静默忽略")
    void disabledJobCannotBeTriggered() throws Exception {
        String token = admin();
        seed("demo-disable");
        mvc().perform(post("/ops/jobs/demo-disable/disable").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.enabled").value(false));

        mvc().perform(post("/ops/jobs/demo-disable/trigger").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 不存在的任务返回 404，不是空对象")
    void unknownJobIs404() throws Exception {
        mvc().perform(get("/ops/jobs/nope").header("Authorization", "Bearer " + admin()))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }
}
