package ai.neargo.job.worker;

import ai.neargo.job.engine.JobRegistry;
import ai.neargo.job.engine.JobSyncService;
import ai.neargo.job.store.JobDefinitionDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 这个进程**能不能自己起来**。
 *
 * <p>单元测试都是手工 new 出来的，装配错了一个都不会红 ——
 * 而「独立可部署」这件事的最低门槛恰恰是「不靠别人也能启动」。
 * 所以这一条必须走真实的 Spring 上下文。
 *
 * <p>库换成 H2、Flyway 指向生成的 H2 等价脚本；
 * 除此之外走的是与生产同一条装配路径（同一个 JobStoreConfig、同一个 JobWorkerConfig）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "shop.job.enabled=true",
        "shop.job.flyway-locations=classpath:db/job-h2",
        "shop.job.datasource.url=jdbc:h2:mem:jobsmoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.job.datasource.username=sa",
        "shop.job.datasource.password=",
        // 轮询给足够长，免得冒烟测试里真的去连一个不存在的业务系统
        "shop.job.worker.poll-interval=3600s",
        "shop.job.worker.instance=smoke",
        // 密钥必配 —— JobWorkerConfig 空密钥直接拒绝启动。
        // 不拦的话进程一切正常，只是每轮 401，现场看上去像业务系统全线崩了
        "shop.job.worker.token=smoke-token",
})
class JobApplicationSmokeTest {

    @Autowired
    JobDefinitionDao definitions;

    @Autowired
    JobRegistry registry;

    @Autowired
    JobSyncService sync;

    @Test
    @DisplayName("★ 独立进程能起来：数据源、迁移、注册表、调度器全部就位")
    void contextLoadsWithoutAnyBusinessModule() {
        assertNotNull(definitions);
        assertNotNull(registry);
        assertNotNull(sync);
        // 迁移真的跑过了 —— 查得动就说明三张表都在
        assertTrue(definitions.findAll().isEmpty(), "全新库应当是空的，但要查得动");
        assertTrue(registry.scheduledNames().isEmpty());
    }

    @Test
    @DisplayName("classpath 上没有任何业务模块 —— 这是「独立」的字面含义")
    void noBusinessCodeOnClasspath() {
        for (String c : new String[]{
                "ai.neargo.shop.trade.job.OrderAutoCloseJob",
                "ai.neargo.shop.merchant.job.PlanExpiryJob",
                "com.baomidou.mybatisplus.core.MybatisConfiguration",
                "org.springframework.web.servlet.DispatcherServlet"}) {
            try {
                Class.forName(c);
                fail("worker 的 classpath 上出现了 " + c
                     + "。业务代码进来 = 业务发版后 worker 跑着旧逻辑；"
                     + "web 框架进来 = 多了一个没人调的端口");
            } catch (ClassNotFoundException expected) {
                // 正是我们要的
            }
        }
    }
}
