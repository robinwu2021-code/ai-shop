package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Uses the existing real session/filter/HTTP relay fixture, without a payment membership shortcut. */
class PayBizCollectionHttpTest {
  final PayBizOnboardingHttpTest fixture = new PayBizOnboardingHttpTest();

  @BeforeEach void open() throws Exception { fixture.setUp(); }
  @AfterEach void close() { fixture.tearDown(); }

  @Test void originalCollectionBytesAndReadOnlyRecoveryReachActualOwner() throws Exception {
    String body = "{\"requestNo\":\"REQ:1\",\"storeNo\":\"S1\",\"bizNo\":\"WO1\",\"amountMinor\":100}";
    fixture.mvc.perform(post("/biz/pay/v2/collections")
            .header("Authorization", "Bearer btk_user").header("X-Merchant-No", "PAY1")
            .contentType("application/json").content(body))
        .andExpect(status().isOk());
    assertEquals("POST /biz/pay/v2/collections", fixture.target.get());
    assertEquals(body, new String(fixture.body.get(), StandardCharsets.UTF_8));
    assertEquals("Bearer btk_user", fixture.headers.get().getFirst("Authorization"));
    assertEquals("PAY1", fixture.headers.get().getFirst("X-Merchant-No"));
    fixture.mvc.perform(get("/biz/pay/v2/collections?storeNo=S1&requestNo=REQ:1")
            .header("Authorization", "Bearer btk_user").header("X-Merchant-No", "PAY1"))
        .andExpect(status().isOk());
    assertEquals("GET /biz/pay/v2/collections?storeNo=S1&requestNo=REQ:1", fixture.target.get());
    assertEquals(2, fixture.calls.get());
  }

  @Test void encodedOriginalKeyQueryIsPreservedWithoutOpeningEncodedOrTraversalPaths() throws Exception {
    String target = "/biz/pay/v2/collections?storeNo=S1&requestNo=REQ%3A..1";
    fixture.mvc.perform(get(URI.create(target)).header("Authorization", "Bearer btk_user"))
        .andExpect(status().isOk());
    assertEquals("GET " + target, fixture.target.get());
    assertFalse(BizOnboardingForwarder.allowed("GET", "/biz/pay/v2/%63ollections?requestNo=R1"));
    assertFalse(BizOnboardingForwarder.allowed("GET", "/biz/pay/v2/collections/../memberships"));
    assertFalse(BizOnboardingForwarder.allowed("GET", "/biz/pay/v2/collections#fragment"));
    assertFalse(BizOnboardingForwarder.allowed("PUT", target));
  }

  @Test void conflictAndUnknownResponseAreNotRetriedAndRevokedSessionNeverReachesOwner() throws Exception {
    fixture.responseStatus.set(409);
    fixture.responseBody.set("{\"reasonCode\":\"IDEMPOTENCY_CONFLICT\"}".getBytes(StandardCharsets.UTF_8));
    fixture.mvc.perform(post("/biz/pay/v2/collections").header("Authorization", "Bearer btk_user")
            .contentType("application/json").content("{}"))
        .andExpect(status().isConflict())
        .andExpect(content().bytes(fixture.responseBody.get()));
    fixture.responseStatus.set(503);
    fixture.mvc.perform(post("/biz/pay/v2/collections").header("Authorization", "Bearer btk_user")
            .contentType("application/json").content("{}"))
        .andExpect(status().isServiceUnavailable());
    assertEquals(2, fixture.calls.get());
    fixture.jdbc.update("UPDATE mch_session SET revoked_at=CURRENT_TIMESTAMP");
    fixture.mvc.perform(get("/biz/pay/v2/collections?storeNo=S1&requestNo=R1")
            .header("Authorization", "Bearer btk_user"))
        .andExpect(status().isUnauthorized());
    assertEquals(2, fixture.calls.get());
  }
}
