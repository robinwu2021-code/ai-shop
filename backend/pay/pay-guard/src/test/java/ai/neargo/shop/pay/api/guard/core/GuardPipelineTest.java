package ai.neargo.shop.pay.api.guard.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.access.service.ApiRequestRecorder;
import ai.neargo.shop.pay.api.guard.PortalFace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 统一管线（TDD-支付服务-接入守卫模块pay-guard §2.2；开发计划 M1.4）：每个入口一个 {@link EntryGuard}，
 * 管线本身不分入口 —— 认不出就在 controller 之前回绝，不论成败都写一行请求记录。
 */
class GuardPipelineTest {

    /** 记下每一步有没有被调到 */
    private static final class FakeGuard implements EntryGuard {
        GuardResult result;
        boolean signs;
        final List<String> calls = new ArrayList<>();
        byte[] signedBody;

        @Override
        public PortalFace entry() {
            return PortalFace.OPEN;
        }

        @Override
        public GuardResult authenticate(HttpServletRequest request) {
            calls.add("authenticate");
            return result;
        }

        @Override
        public void decryptSensitiveFields(HttpServletRequest request, PortalPrincipal who) {
            calls.add("decrypt");
        }

        @Override
        public boolean signsResponses() {
            return signs;
        }

        @Override
        public void signResponse(HttpServletResponse response, byte[] body, PortalPrincipal who) {
            calls.add("sign");
            signedBody = body;
            response.setHeader("X-Signature", "sig");
        }
    }

    private final List<PayApiRequestLog> rows = new ArrayList<>();
    private final ApiRequestRecorder recorder = rows::add;
    private final FakeGuard guard = new FakeGuard();
    private final GuardFilter filter = new GuardFilter(PortalFace.OPEN, guard, new RequestLogWriter(recorder));

    private static final PortalPrincipal APP_7 = new PortalPrincipal(PortalFace.OPEN, "APP", "APP7", null, Set.of());

    private static MockHttpServletRequest post(String path, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.addHeader("X-Request-Id", "req-12345678");
        return req;
    }

    /** 记下 controller 有没有被调到、看到的调用方是谁 */
    private final class Controller extends MockFilterChain {
        boolean called;
        Object principal;
        /** controller 被调到那一刻，守卫已经做过的步骤 —— 证明 ⑨⑩ 发生在 controller 之前 */
        List<String> stepsBeforeController;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                throws java.io.IOException {
            called = true;
            principal = request.getAttribute(PortalRequests.PRINCIPAL);
            stepsBeforeController = List.copyOf(guard.calls);
            response.getWriter().write("{\"code\":0}");
        }
    }

    @Test
    @DisplayName("★★★ ① 别的入口的路径：不认人、不留痕，直接放行")
    void otherEntryPathsPassThrough() throws Exception {
        Controller controller = new Controller();
        filter.doFilter(post("/terminal/pay/v1/x", "{}"), new MockHttpServletResponse(), controller);

        assertThat(controller.called).isTrue();
        assertThat(guard.calls).isEmpty();
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("★★★ ② 请求体超过 1MB：413，不认人，记 REJECTED_PARAM")
    void oversizedBodyRejectedBeforeAuthentication() throws Exception {
        Controller controller = new Controller();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(post("/open/pay/v1/orders", "x".repeat(CachedBodyRequest.MAX_BODY + 1)), resp, controller);

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(controller.called).isFalse();
        assertThat(guard.calls).isEmpty();
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.getOutcome()).isEqualTo("REJECTED_PARAM"));
    }

    @Test
    @DisplayName("★★★ ④–⑧ 认不出：401 统一错误体带请求号，不解密、不进 controller；原因只进记录")
    void rejectedNeverReachesController() throws Exception {
        guard.result = GuardResult.unauthorized("签名不符");
        Controller controller = new Controller();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(post("/open/pay/v1/orders", "{\"a\":1}"), resp, controller);

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("\"requestId\":\"req-12345678\"").doesNotContain("签名不符");
        assertThat(resp.getHeader("X-Request-Id")).isEqualTo("req-12345678");
        assertThat(controller.called).isFalse();
        assertThat(guard.calls).containsExactly("authenticate");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getOutcome()).isEqualTo("REJECTED_AUTH");
            assertThat(r.getResponseMasked()).isEqualTo("签名不符");
            assertThat(r.getEntry()).isEqualTo("OPEN_API");
            assertThat(r.getTraceId()).isEqualTo("req-12345678");
        });
    }

    @Test
    @DisplayName("★★★ ⑨⑩ 认出：先解密、再放调用方、再进 controller；⑫ 成功也记一行 ACCEPTED")
    void acceptedRunsHooksInOrder() throws Exception {
        guard.result = GuardResult.ok(APP_7);
        Controller controller = new Controller();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(post("/open/pay/v1/orders", "{\"a\":1}"), resp, controller);

        assertThat(guard.calls).containsExactly("authenticate", "decrypt");
        assertThat(controller.called).isTrue();
        assertThat(controller.stepsBeforeController).as("解密必须在进 controller 之前").containsExactly("authenticate", "decrypt");
        assertThat(controller.principal).isEqualTo(APP_7);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getOutcome()).isEqualTo("ACCEPTED");
            assertThat(r.getAppNo()).isEqualTo("APP7");
            assertThat(r.getHttpStatus()).isEqualTo(200);
        });
    }

    @Test
    @DisplayName("★★ ⑪ 要签名的入口：拿到完整响应体签名，响应体原样到达调用方；不签的入口不包响应")
    void signingEntrySeesWholeBody() throws Exception {
        guard.result = GuardResult.ok(APP_7);
        guard.signs = true;
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(post("/open/pay/v1/orders", "{}"), resp, new Controller());

        assertThat(guard.calls).containsExactly("authenticate", "decrypt", "sign");
        assertThat(new String(guard.signedBody, StandardCharsets.UTF_8)).isEqualTo("{\"code\":0}");
        assertThat(resp.getContentAsString()).isEqualTo("{\"code\":0}");
        assertThat(resp.getHeader("X-Signature")).isEqualTo("sig");

        FakeGuard plain = new FakeGuard();
        plain.result = GuardResult.ok(APP_7);
        new GuardFilter(PortalFace.OPEN, plain, new RequestLogWriter(recorder))
                .doFilter(post("/open/pay/v1/orders", "{}"), new MockHttpServletResponse(), new Controller());
        assertThat(plain.calls).doesNotContain("sign");
    }

    @Test
    @DisplayName("★★ 一个入口恰好一个守卫：重复登记启动失败，没登记的入口要过滤器即失败")
    void oneGuardPerEntry() {
        assertThatThrownBy(() -> new GuardFilters(List.of(new FakeGuard(), new FakeGuard()), new RequestLogWriter(recorder)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OPEN");
        GuardFilters filters = new GuardFilters(List.of(new FakeGuard()), new RequestLogWriter(recorder));
        assertThat(filters.forEntry(PortalFace.OPEN)).isNotNull();
        assertThatThrownBy(() -> filters.forEntry(PortalFace.TERMINAL)).isInstanceOf(IllegalStateException.class);
    }
}
