package ai.neargo.inventory.svc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.svc.context.SignedContext;
import ai.neargo.svc.context.SignedContextCodec;
import ai.neargo.svc.guard.SignedContextHolder;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 库存服务验 ai-shop 网关的签名上下文（ai-shop TDD-网关层与统一权限 §2.7、§2.22）：<b>只比对、不拒绝</b>。
 * 用 svc-client 现成的守卫过滤器，只拦 {@code /api/inventory/v1/}。
 */
@DisplayName("库存 · 网关签名上下文只比对")
class InventorySignedContextGuardTest {

    private static final String KEY = "inventory-test-key";
    private static final String PATH = "/api/inventory/v1/reservations";
    private static final byte[] BODY = "{\"qty\":1}".getBytes(StandardCharsets.UTF_8);

    private static MockHttpServletRequest request(String key) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", PATH);
        r.setContent(BODY);
        String context = SignedContextCodec.encode(new SignedContext("OP-1", "MERCHANT", "ENT-1", "S-1",
                List.of("biz:stock"), "RID-1", System.currentTimeMillis(), "POST", PATH, SignedContextCodec.digest(BODY)));
        r.addHeader(SignedContextCodec.HEADER_CONTEXT, context);
        r.addHeader(SignedContextCodec.HEADER_SIGNATURE, SignedContextCodec.sign(context, key));
        return r;
    }

    /** 跑一次过滤器，返回入口里看到的上下文（看不到为 null）与入口有没有被调到。 */
    private static Object[] run(Filter filter, MockHttpServletRequest request) throws Exception {
        AtomicReference<SignedContext> seen = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res)
                    throws java.io.IOException {
                seen.set(SignedContextHolder.current().orElse(null));
                body.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        });
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return new Object[] {seen.get(), body.get(), response.getStatus()};
    }

    @Test
    @DisplayName("★★★ 配了钥：签名对得上，入口拿得到上下文，请求体还能再读")
    void verifiedContextReachesTheEndpoint() throws Exception {
        Object[] out = run(InventoryApplication.signedContextGuard(KEY, "observe").getFilter(), request(KEY));
        assertThat(((SignedContext) out[0]).operator()).isEqualTo("OP-1");
        assertThat(out[1]).isEqualTo("{\"qty\":1}");
    }

    @Test
    @DisplayName("★★★ 签名对不上、没配钥、写成 enforce：都放行，从不拒绝")
    void neverRejects() throws Exception {
        Object[] wrong = run(InventoryApplication.signedContextGuard(KEY, "observe").getFilter(), request("别的钥"));
        assertThat(wrong[0]).isNull();
        assertThat(wrong[2]).isEqualTo(200);
        assertThat(wrong[1]).as("入口照常被调到").isEqualTo("{\"qty\":1}");

        Object[] enforce = run(InventoryApplication.signedContextGuard(KEY, "enforce").getFilter(), request("别的钥"));
        assertThat(enforce[2]).as("没有拒绝这一档").isEqualTo(200);
        assertThat(enforce[1]).isEqualTo("{\"qty\":1}");

        Object[] unkeyed = run(InventoryApplication.signedContextGuard("", "observe").getFilter(), request(KEY));
        assertThat(unkeyed[0]).as("没配钥：不验").isNull();
        assertThat(unkeyed[2]).isEqualTo(200);
    }

    @Test
    @DisplayName("只拦库存的服务入口")
    void onlyTheServicePrefix() {
        assertThat(InventoryApplication.signedContextGuard(KEY, "observe").getUrlPatterns())
                .containsExactly("/api/inventory/v1/*");
    }
}
