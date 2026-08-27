package ai.neargo.shop.scenario;

import ai.neargo.job.engine.JobRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ai.neargo.shop.job.JobHandlerRegistry;
import ai.neargo.shop.portal.internal.JobHandlerEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>生产那套 profile 起得来吗。</b>
 *
 * <p>2026-08-27 部署时线上炸在这里：{@code jobHandlerRegistry} 的 bean 定义写在
 * {@code @Profile("worker")} 的 {@code InProcessJobConfig} 里，而生产跑的是
 * {@code api,ops} —— 新加的 {@code /internal/job/**} 端点要那个 bean，容器里没有，
 * 业务系统起不来、{@code Restart=always} 反复重启了六分钟。
 *
 * <p>本地全绿的原因很实在：<b>唯一起过这套装配的测试带着 worker profile</b>，
 * 而生产恰恰是没有它的那一半。这条用例补的就是那一半 ——
 * 业务系统<b>只当任务目标、不当调度器</b>的形态。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "api", "ops"})
@TestPropertySource(properties = {
        // 独立的平台库：@TestPropertySource 会造出第二个上下文，共享 h2:mem:shop
        // 会让 schema-test.sql 跑第二遍（sys_industry 主键冲突）
        "spring.datasource.url=jdbc:h2:mem:jobtarget-platform;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.job.enabled=true",
        "shop.job.flyway-locations=classpath:db/job-h2",
        "shop.job.datasource.url=jdbc:h2:mem:jobtarget;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.job.datasource.username=sa",
        "shop.job.datasource.password=",
        "shop.job.internal-token=test-token",
})
@DisplayName("业务系统只当任务目标（生产 profile：api,ops）")
class JobTargetProfileTest {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    JobHandlerRegistry handlers;

    @Autowired
    WebApplicationContext webContext;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Value("${shop.job.internal-token}")
    String token;

    @Test
    @DisplayName("没有 worker profile 时，任务体索引与内部端点仍要在")
    void 任务目标的装配不依赖worker_profile() {
        // 这两个就是线上起不来的那对：端点要注册表，而注册表当时只在 worker 下存在
        assertThat(ctx.getBean(JobHandlerEndpoint.class)).isNotNull();
        assertThat(handlers.declarations()).isNotEmpty();
    }

    @Test
    @DisplayName("但调度器不能在：任务由独立进程排期，业务系统只被调")
    void 不带worker时不装调度器_否则两个进程会各排一遍() {
        // 两边都排期 = 同一个任务一天跑两次。ShedLock 挡得住同一时刻的并发，
        // 挡不住错开 30 秒的两轮
        assertThat(ctx.getBeanNamesForType(JobRegistry.class)).isEmpty();
    }

    @Test
    @DisplayName("内部端点不套 ApiResult 信封 —— 套上之后调度器读到的每个字段都是 null")
    void 内部端点原样返回数组() throws Exception {
        String body = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/job/declarations").header("X-Job-Token", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 顶层就是数组。裹成 {"code":0,"msg":"success","data":[...]} 时这里会变成 '{'，
        // 而调度器那边不会报错 —— 它按数组遍历一个对象，拿到三个值、字段全 null
        assertThat(body).startsWith("[");
        assertThat(body).contains("\"handlerName\"").doesNotContain("\"msg\"");
    }

    @Test
    @DisplayName("没有令牌一律 401 —— 这个口不认用户身份，只认共享密钥")
    void 内部端点必须带密钥() throws Exception {
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/job/declarations"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isUnauthorized());
    }
}
