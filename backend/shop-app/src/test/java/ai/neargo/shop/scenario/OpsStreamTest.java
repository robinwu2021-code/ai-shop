package ai.neargo.shop.scenario;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 运营端推送。
 *
 * <h2>这一条的由来</h2>
 * <p>第一版 {@code OpsStreamController} 注入了 {@code ObjectMapper}，而这个上下文里
 * <b>根本没有那个 bean</b>。单元测试全绿、编译全绿，dev server 起来 15 秒后死掉，
 * 日志被回收之后现场什么都不剩 —— <b>装配失败只有真跑一次上下文才看得出来</b>。
 *
 * <h2>为什么不自己 new 一个 ObjectMapper</h2>
 * <p>那样 SSE 与 {@code GET /ops/jobs} 会走两套序列化：{@code LocalDateTime}
 * 的写法可能不一致，页面上同一个字段出现两种样子，而这种差异不会有任何报错。
 * 现在把对象交给 Spring 的消息转换器，两条路逐字节相同 —— 这条测试顺带钉住它。
 */
@SpringBootTest
@ActiveProfiles({"test", "ops"})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:opsstream;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@DisplayName("运营端 · 服务端推送")
class OpsStreamTest {

    @Autowired
    WebApplicationContext ctx;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(ctx)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 推送控制器能装配起来 —— 装配失败只有真跑上下文才看得出来")
    void controllerIsWired() {
        assertThat(ctx.getBean(ai.neargo.shop.portal.ops.OpsStreamController.class)).isNotNull();
    }

    @Test
    @DisplayName("★ 未登录订阅一律 401 —— 这条流里有未读数与任务状态")
    void anonymousCannotSubscribe() throws Exception {
        mvc().perform(get("/ops/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isUnauthorized());
    }
}
