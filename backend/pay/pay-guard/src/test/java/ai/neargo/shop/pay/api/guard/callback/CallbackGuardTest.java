package ai.neargo.shop.pay.api.guard.callback;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.GuardFilter;
import ai.neargo.shop.pay.api.guard.core.RequestLogWriter;
import ai.neargo.shop.spi.pay.PayCallbackPort;
import ai.neargo.shop.spi.pay.PayCallbackPort.Envelope;
import ai.neargo.shop.spi.pay.PayCallbackPort.Kind;
import ai.neargo.shop.spi.pay.PayCallbackPort.Outcome;
import ai.neargo.shop.spi.pay.PayCallbackPort.RouteStyle;
import ai.neargo.shop.spi.pay.PayCallbackPort.Verdict;
import ai.neargo.shop.spi.pay.PayCallbackPort.Verified;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 渠道回调守卫（开发计划 M4.1）：解析路径 → 先落原文 → 验签 → 核对收款商户，都在 controller 之前；
 * 不过就按渠道格式回（{@code FAIL}），不进 controller。回查、比对、入账留给 controller。
 */
class CallbackGuardTest {

    /** 记下守卫交给验签前段的信封；按需验过或拒 */
    private static final class FakePort implements PayCallbackPort {
        final List<Envelope> seen = new ArrayList<>();
        boolean pass = true;

        @Override
        public Verified verify(Envelope e) {
            seen.add(e);
            Verdict rejected = pass ? null : new Verdict("9", Outcome.REJECT, "验签失败", e.kind(), e.payChannel(), null,
                    null, "SUCCESS", "FAIL", null, null);
            return new Verified(e, "9", null, Map.of(), "OUT-1", "SUCCESS", "FAIL", rejected);
        }

        @Override
        public Verdict confirm(Verified verified) {
            throw new UnsupportedOperationException("守卫不回查");
        }

        @Override
        public void handled(Verdict verdict, boolean applied, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    private final FakePort port = new FakePort();
    private final List<PayApiRequestLog> rows = new ArrayList<>();
    private final GuardFilter filter = new GuardFilter(PortalFace.CALLBACK, new CallbackGuard(port),
            new RequestLogWriter(rows::add));

    /** 记下 controller 有没有被调到、拿到的验签结果 */
    private static final class Controller extends MockFilterChain {
        Object verified;
        boolean called;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            called = true;
            verified = request.getAttribute(CallbackGuard.VERIFIED_ATTRIBUTE);
        }
    }

    private static MockHttpServletRequest post(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        r.addHeader("Wechatpay-Serial", "SER-1");
        return r;
    }

    @Test
    @DisplayName("★★★ 新地址：路径解析成信封（通道 / 通知类型 / 收款商户号），原文与头交给验签前段；过了就带着结果进 controller")
    void verifiedReachesController() throws Exception {
        Controller c = new Controller();
        filter.doFilter(post("/pay/callback/WECHAT/pay/PM-1"), new MockHttpServletResponse(), c);

        assertThat(port.seen).singleElement().satisfies(e -> {
            assertThat(e.payChannel()).isEqualTo("WECHAT");
            assertThat(e.kind()).isEqualTo(Kind.PAY);
            assertThat(e.pathRef()).isEqualTo("PM-1");
            assertThat(e.routeStyle()).isEqualTo(RouteStyle.PATH);
            assertThat(e.rawBody()).isEqualTo("{\"a\":1}");
            assertThat(e.headers()).containsEntry("Wechatpay-Serial", "SER-1");
        });
        assertThat(c.called).isTrue();
        assertThat(c.verified).isInstanceOf(Verified.class);
        assertThat(rows).as("回调有自己的记录（pay_callback_log），不写请求记录").isEmpty();
    }

    @Test
    @DisplayName("★★★ 验签不过：按渠道格式回 FAIL（HTTP 200，不是 JSON 错误体），不进 controller")
    void rejectedAnswersInTheChannelFormat() throws Exception {
        port.pass = false;
        Controller c = new Controller();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(post("/pay/callback/WECHAT/refund/PM-1"), resp, c);

        assertThat(c.called).isFalse();
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentAsString()).isEqualTo("FAIL");
        assertThat(port.seen.getFirst().kind()).isEqualTo(Kind.REFUND);
    }

    @Test
    @DisplayName("★★ 旧地址别名：/{channel} 是支付、/{channel}/refund 是退款，记成 LEGACY")
    void legacyRoutes() throws Exception {
        filter.doFilter(post("/pay/callback/ALIPAY"), new MockHttpServletResponse(), new Controller());
        filter.doFilter(post("/pay/callback/ALIPAY/refund"), new MockHttpServletResponse(), new Controller());

        assertThat(port.seen).extracting(Envelope::kind).containsExactly(Kind.PAY, Kind.REFUND);
        assertThat(port.seen).allSatisfy(e -> assertThat(e.routeStyle()).isEqualTo(RouteStyle.LEGACY));
    }

    @Test
    @DisplayName("★★ 不支持的通知类型：回 FAIL、不落记录、不进 controller；开发期 stub 地址不归守卫管")
    void unsupportedAndStub() throws Exception {
        Controller unsupported = new Controller();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(post("/pay/callback/WECHAT/applyment/PM-1"), resp, unsupported);
        assertThat(unsupported.called).isFalse();
        assertThat(resp.getContentAsString()).isEqualTo("FAIL");

        Controller stub = new Controller();
        filter.doFilter(post("/pay/callback/stub"), new MockHttpServletResponse(), stub);
        assertThat(stub.called).isTrue();
        assertThat(port.seen).isEmpty();
    }
}
