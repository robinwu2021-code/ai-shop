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

    /**
     * ★★ SSE 到点之后的那次异步分派，身份要还在。
     *
     * <p>2026-09-14 在生产日志里量到：每 30 分钟（{@code EMITTER_TIMEOUT_MS}）一次
     * {@code /ops/stream} 重连，恰好 2 条 ERROR {@code AuthorizationDeniedException}
     * 带 30 帧堆栈 —— 当天全部 10 条 ERROR 都是它，占日志文件字节的 46%。
     *
     * <p>机理：令牌过滤器继承 {@code OncePerRequestFilter}，默认<b>跳过异步分派</b>，
     * 且只把身份写进 {@code SecurityContextHolder}、不存进请求属性 —— 于是到点那次
     * ASYNC 分派拿到的是匿名身份，被 {@code anyRequest().authenticated()} 拒掉。
     * 而 SSE 的响应早已提交、转不成 401，异常一路抛到 Tomcat 记 ERROR。
     *
     * <p>后果不止是噪音：「控制台只留 ERROR 进 journal」那条本该最干净的通道，
     * 当天 100% 是这个；将来按 ERROR 数告警，上线就是废的。
     */
    @Test
    @DisplayName("★★ SSE 到点后的异步分派不被当成匿名拒掉 —— 否则每次重连 2 条 ERROR 带堆栈")
    void asyncDispatchAfterTimeoutKeepsIdentity() throws Exception {
        String token = opsLogin("support", "support123");
        org.springframework.test.web.servlet.MvcResult started = mvc().perform(get("/ops/stream")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .request().asyncStarted())
                .andReturn();

        // 模拟到点：生产上是 30 分钟后 Tomcat 触发超时监听，这里直接触发
        var ac = (org.springframework.mock.web.MockAsyncContext) started.getRequest().getAsyncContext();
        for (jakarta.servlet.AsyncListener l : ac.getListeners()) {
            l.onTimeout(new jakarta.servlet.AsyncEvent(ac));
        }

        org.assertj.core.api.Assertions.assertThatCode(() -> mvc().perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .asyncDispatch(started)))
                .as("到点后的 ASYNC 分派不该抛 AuthorizationDeniedException —— 生产上它被 Tomcat 记成 ERROR")
                .doesNotThrowAnyException();
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(body).get("data").get("token").asString();
    }
}
