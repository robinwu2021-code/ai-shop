package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 商户权限由 ai-shop 决定：店主名下的支付商户由 ai-shop 指定并带来源凭据转发；其余原样转发；App 伪造的内部头一律去掉。 */
class PayBizSourceDelegationTest {
  HttpServer upstream;
  BizOnboardingForwarder forwarder;
  final AtomicInteger calls = new AtomicInteger();
  final AtomicReference<Headers> seen = new AtomicReference<>();
  final PayBizSourceDelegation delegation =
      new PayBizSourceDelegation(user -> "U1".equals(user) ? List.of("PM1") : List.of(), "AI_SHOP_UME", "secret");

  @BeforeEach
  void setUp() throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/", ex -> {
      calls.incrementAndGet();
      seen.set(ex.getRequestHeaders());
      byte[] b = "{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "application/json");
      ex.sendResponseHeaders(200, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    upstream.start();
    forwarder = new BizOnboardingForwarder(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()));
  }

  @AfterEach
  void tearDown() {
    upstream.stop(0);
    SecurityContextHolder.clearContext();
  }

  static MockHttpServletRequest req(String user, String path, String merchant) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(LoginUser.merchantByUser(user, ""), null, List.of()));
    var r = new MockHttpServletRequest("GET", path);
    r.addHeader("Authorization", "Bearer btk_user");
    if (merchant != null) r.addHeader("X-Merchant-No", merchant);
    r.addHeader("X-Internal-Caller", "FORGED");
    r.addHeader("X-Internal-Credential", "forged");
    return r;
  }

  @Test
  void ownerMerchantIsNamedByAiShopWithSourceCredential() throws Exception {
    PayBizOnboardingRelay.forward(forwarder, req("U1", "/biz/pay/v2/onboardings", "PM1"), delegation);
    assertEquals("PM1", seen.get().getFirst("X-Merchant-No"));
    assertEquals("AI_SHOP_UME", seen.get().getFirst("X-Internal-Caller"));
    assertEquals("secret", seen.get().getFirst("X-Internal-Credential"));
  }

  @Test
  void singleOwnedMerchantIsUsedWhenTheAppNamesNone() throws Exception {
    PayBizOnboardingRelay.forward(forwarder, req("U1", "/biz/pay/v2/capability-progress", null), delegation);
    assertEquals("PM1", seen.get().getFirst("X-Merchant-No"));
    assertEquals("AI_SHOP_UME", seen.get().getFirst("X-Internal-Caller"));
  }

  @Test
  void otherMerchantsGoThroughUnchangedAndForgedInternalHeadersAreDropped() throws Exception {
    PayBizOnboardingRelay.forward(forwarder, req("U1", "/biz/pay/v2/onboardings", "PM9"), delegation);
    assertEquals("PM9", seen.get().getFirst("X-Merchant-No"));
    assertNull(seen.get().getFirst("X-Internal-Caller"));
    assertNull(seen.get().getFirst("X-Internal-Credential"));
    PayBizOnboardingRelay.forward(forwarder, req("U2", "/biz/pay/v2/onboardings", "PM1"), delegation);
    assertEquals("PM1", seen.get().getFirst("X-Merchant-No"));
    assertNull(seen.get().getFirst("X-Internal-Caller"));
  }

  @Test
  void ownerMembershipsAreAnsweredByAiShop() throws Exception {
    var r = PayBizOnboardingRelay.memberships(forwarder, req("U1", "/biz/pay/v2/memberships", null), delegation);
    assertEquals(0, calls.get());
    String body = new String(r.getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("\"merchantNo\":\"PM1\""), body);
    assertTrue(body.contains("\"onboarding.apply\""), body);
    assertTrue(body.contains("\"role\":\"MERCHANT_ADMIN\""), body);
  }

  @Test
  void nonOwnersStillAskNearPay() throws Exception {
    PayBizOnboardingRelay.memberships(forwarder, req("U2", "/biz/pay/v2/memberships", null), delegation);
    assertEquals(1, calls.get());
    assertNull(seen.get().getFirst("X-Internal-Caller"));
  }

  @Test
  void withoutSourceCredentialNothingIsDelegated() throws Exception {
    var off = new PayBizSourceDelegation(user -> List.of("PM1"), "AI_SHOP_UME", null);
    PayBizOnboardingRelay.forward(forwarder, req("U1", "/biz/pay/v2/onboardings", "PM1"), off);
    assertNull(seen.get().getFirst("X-Internal-Caller"));
  }
}
