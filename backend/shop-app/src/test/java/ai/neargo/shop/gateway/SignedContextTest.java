package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.svc.context.SignedContext;
import ai.neargo.svc.context.SignedContextCodec;
import ai.neargo.svc.guard.SignedContextGuardFilter;
import ai.neargo.svc.guard.SignedContextHolder;
import ai.neargo.svc.guard.SignedContextVerifier;
import ai.neargo.svc.guard.SignedContextVerifier.Outcome;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 签名上下文的编解码、验证与微服务侧守卫（TDD-网关层与统一权限 §2.7，AC6；开发计划 S）。不起 Spring。
 */
@DisplayName("网关 · 签名上下文与守卫")
class SignedContextTest {

    private static final String KEY = "unit-test-signing-key";
    private static final byte[] BODY = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);
    private static final String PATH = "/internal/workflow/v1/tasks/T1/approve?force=1";

    private static String encoded(long timestamp) {
        return SignedContextCodec.encode(new SignedContext("OP-1", "MERCHANT", "ENT 甲", "S&1",
                List.of("workflow:task:approve", "biz:order"), "RID-1", timestamp, "POST", PATH,
                SignedContextCodec.digest(BODY)));
    }

    @Test
    @DisplayName("★★★ 编了再解：每一项都原样回来，含中文与特殊字符")
    void roundTrip() {
        SignedContext back = SignedContextCodec.decode(encoded(1_000L)).orElseThrow();
        assertThat(back.operator()).isEqualTo("OP-1");
        assertThat(back.merchantNo()).isEqualTo("ENT 甲");
        assertThat(back.storeNo()).isEqualTo("S&1");
        assertThat(back.points()).containsExactly("workflow:task:approve", "biz:order");
        assertThat(back.path()).isEqualTo(PATH);
        assertThat(back.timestamp()).isEqualTo(1_000L);
        assertThat(SignedContextCodec.decode("不是 base64 !!")).isEmpty();
    }

    @Test
    @DisplayName("★★★ 验证：对的通过；没带、签名被改、过期、换了地址、换了请求体各有各的结论")
    void verifierTellsEachFailureApart() {
        long now = System.currentTimeMillis();
        String context = encoded(now);
        String signature = SignedContextCodec.sign(context, KEY);
        SignedContextVerifier verifier = new SignedContextVerifier(KEY);

        var ok = verifier.verify(context, signature, "POST", PATH, BODY, now);
        assertThat(ok.outcome()).isEqualTo(Outcome.OK);
        assertThat(ok.context().orElseThrow().hasPoint("workflow:task:approve")).isTrue();

        assertThat(verifier.verify(null, null, "POST", PATH, BODY, now).outcome()).isEqualTo(Outcome.MISSING);
        assertThat(verifier.verify(context, SignedContextCodec.sign(context, "别的密钥"), "POST", PATH, BODY, now)
                .outcome()).isEqualTo(Outcome.BAD_SIGNATURE);
        // 改上下文里的任何一项（这里是提权：换一个操作人），签名就对不上
        String forged = SignedContextCodec.encode(new SignedContext("ADMIN", "OPERATOR", null, null,
                List.of("pay:refund:review"), "RID-1", now, "POST", PATH, SignedContextCodec.digest(BODY)));
        assertThat(verifier.verify(forged, signature, "POST", PATH, BODY, now).outcome())
                .isEqualTo(Outcome.BAD_SIGNATURE);
        assertThat(verifier.verify(context, signature, "POST", PATH, BODY, now + 6 * 60_000).outcome())
                .as("重放：过了时间窗").isEqualTo(Outcome.EXPIRED);
        assertThat(verifier.verify(context, signature, "POST", "/internal/workflow/v1/tasks/T2/approve?force=1",
                BODY, now).outcome()).as("拿 T1 的签名去调 T2").isEqualTo(Outcome.REQUEST_MISMATCH);
        assertThat(verifier.verify(context, signature, "POST", PATH,
                "{\"amount\":999}".getBytes(StandardCharsets.UTF_8), now).outcome()).isEqualTo(Outcome.BODY_MISMATCH);
        assertThat(verifier.verify(context, signature, "POST", PATH, null, now).outcome())
                .as("拿不到请求体的地方不验摘要，其余照验").isEqualTo(Outcome.OK);
    }

    @Test
    @DisplayName("★★★ 守卫三档：拦截档对不上回 401 且入口没被调到；只比对档放行；对得上时入口拿得到上下文与请求体")
    void guardFilterModes() throws Exception {
        long now = System.currentTimeMillis();
        String context = encoded(now);
        SignedContextVerifier verifier = new SignedContextVerifier(KEY);

        // 拦截档 + 没带签名
        MockHttpServletResponse denied = new MockHttpServletResponse();
        MockFilterChain untouched = new MockFilterChain();
        new SignedContextGuardFilter(verifier, "enforce", "/internal/").doFilter(request(null, null), denied, untouched);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(untouched.getRequest()).as("被拒的请求不该到入口").isNull();

        // 只比对档 + 没带签名：放行，但线程里没有上下文
        MockFilterChain observed = new MockFilterChain();
        new SignedContextGuardFilter(verifier, "observe", "/internal/")
                .doFilter(request(null, null), new MockHttpServletResponse(), observed);
        assertThat(observed.getRequest()).isNotNull();

        // 拦截档 + 签名正确：入口里拿得到上下文，请求体还能再读一遍
        AtomicReference<String> seenOperator = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        new SignedContextGuardFilter(verifier, "enforce", "/internal/").doFilter(
                request(context, SignedContextCodec.sign(context, KEY)), new MockHttpServletResponse(),
                (req, res) -> {
                    seenOperator.set(SignedContextHolder.requirePoint("workflow:task:approve").operator());
                    seenBody.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    assertThatThrownBy(() -> SignedContextHolder.requirePoint("pay:refund:review"))
                            .isInstanceOf(SecurityException.class);
                });
        assertThat(seenOperator.get()).isEqualTo("OP-1");
        assertThat(seenBody.get()).isEqualTo("{\"amount\":100}");
        assertThat(SignedContextHolder.current()).as("请求结束后线程里不留上下文").isEmpty();

        // 前缀之外的地址不拦
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/health");
        MockFilterChain passed = new MockFilterChain();
        new SignedContextGuardFilter(verifier, "enforce", "/internal/").doFilter(other, new MockHttpServletResponse(),
                passed);
        assertThat(passed.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String context, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/workflow/v1/tasks/T1/approve");
        request.setQueryString("force=1");
        request.setContent(BODY);
        if (context != null) {
            request.addHeader(SignedContextCodec.HEADER_CONTEXT, context);
            request.addHeader(SignedContextCodec.HEADER_SIGNATURE, signature);
        }
        return request;
    }
}
