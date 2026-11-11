package ai.neargo.shop.portal.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.promotion.BrandMarketingApplicationService;
import ai.neargo.shop.promotion.BrandMarketingCommandInputs.GenerateStatement;
import ai.neargo.shop.promotion.BrandMarketingCommandInputs.SaveRule;
import ai.neargo.shop.promotion.BrandOrderActivityInput;
import ai.neargo.shop.promotion.dto.BrandCouponModels.IssueCoupon;
import ai.neargo.shop.promotion.dto.BrandCouponModels.SaveCoupon;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;
import tools.jackson.databind.ObjectMapper;

/** Real MVC binding and the existing error advice distinguish invalid command input from server defects. */
class BrandMarketingCommandBindingTest {
    private static final Actor ACTOR = new Actor("MAIN", "USER:U1");
    private static final String PATH = "/biz/management/brands/B1/marketing/commands?managementNo=M1";

    @Test
    void malformedAmountsDatesAndOverflowNeverReachTheBusinessService() throws Exception {
        try (var f = new Fixture()) {
            var activity = activity();
            activity.put("amountMinor", "abc");
            f.invalid("saveActivity", activity);
            activity = activity();
            activity.put("startsAt", "not-an-instant");
            f.invalid("saveActivity", activity);
            var coupon = coupon();
            coupon.put("faceValueMinor", "abc");
            f.invalid("saveCoupon", coupon);
            f.invalid("generateStatement", Map.of("entityNo", "E1", "currency", "CNY", "throughAt", "invalid"));
            f.invalid("saveRule", Map.of("name", "规则", "currency", "CNY", "headquartersBasisPoints",
                    Long.MAX_VALUE, "storeBasisPoints", 0));
            verifyNoInteractions(f.service, f.access);
        }
    }

    @Test
    void beanValidationRejectsMissingAndOutOfRangeExecutableFieldsOnce() throws Exception {
        try (var f = new Fixture()) {
            var input = coupon();
            input.put("name", "");
            f.invalid("saveCoupon", input);
            f.invalid("saveRule", Map.of("name", "规则", "currency", "CNY", "headquartersBasisPoints", 10001,
                    "storeBasisPoints", 0));
            f.invalid("saveRule", Map.of("name", "规则", "currency", "CNY", "headquartersBasisPoints", 4000,
                    "storeBasisPoints", 4000));
            f.invalid("publishCoupon", Map.of("couponId", "C1", "couponRevision", -1,
                    "costRuleVersionNo", "R1"));
            f.invalid("issueCoupon", Map.of("couponId", "C1", "audience", "ALL"));
            verifyNoInteractions(f.service, f.access);
        }
    }

    @Test
    void validWireMetadataAndCouponAliasesBindToTypedTrustedInputs() throws Exception {
        try (var f = new Fixture()) {
            when(f.service.execute(eq(ACTOR), eq("M1"), eq("B1"), eq("saveActivity"),
                    any(BrandOrderActivityInput.class), eq("7"), eq("original"))).thenReturn(Map.of("success", true));
            when(f.service.execute(eq(ACTOR), eq("M1"), eq("B1"), eq("saveCoupon"), any(SaveCoupon.class),
                    eq("7"), eq("original"))).thenReturn(Map.of("success", true));
            when(f.service.execute(eq(ACTOR), eq("M1"), eq("B1"), eq("issueCoupon"), any(IssueCoupon.class),
                    eq("7"), eq("original"))).thenReturn(Map.of("success", true));
            when(f.service.execute(eq(ACTOR), eq("M1"), eq("B1"), eq("saveRule"), any(SaveRule.class),
                    eq("7"), eq("original"))).thenReturn(Map.of("success", true));
            f.valid("saveActivity", activity(), false);
            f.valid("saveCoupon", coupon(), true);
            f.valid("issueCoupon", Map.of("couponId", "C1", "audience", "ALL", "previewToken", "SN1"), false);
            f.valid("saveRule", Map.of("name", "规则", "type", "RATIO", "status", "draft", "currency", "CNY",
                    "headquartersBasisPoints", 6000, "storeBasisPoints", 4000), true);
            verify(f.service).execute(eq(ACTOR), eq("M1"), eq("B1"), eq("issueCoupon"),
                    eq(new IssueCoupon("C1", "ALL", "SN1")), eq("7"), eq("original"));
            verify(f.service).execute(eq(ACTOR), eq("M1"), eq("B1"), eq("saveRule"),
                    eq(new SaveRule("规则", "CNY", 6000, 4000)), eq("7"), eq("original"));
        }
    }

    @Test
    void unsupportedActionsAreRejectedAndIdentityFieldsCannotOverrideTrustedInputs() throws Exception {
        try (var f = new Fixture()) {
            f.invalid("unknownAction", Map.of());
            verifyNoInteractions(f.service, f.access);
            var input = coupon();
            input.put("issuerEntityNo", "OTHER_ISSUER");
            when(f.service.execute(eq(ACTOR), eq("M1"), eq("B1"), eq("saveCoupon"), any(SaveCoupon.class),
                    eq("7"), eq("original"))).thenReturn(Map.of("success", true));
            f.valid("saveCoupon", input, false);
            var bound = org.mockito.ArgumentCaptor.forClass(SaveCoupon.class);
            verify(f.service).execute(eq(ACTOR), eq("M1"), eq("B1"), eq("saveCoupon"), bound.capture(),
                    eq("7"), eq("original"));
            assertThat(bound.getValue().storeIds()).containsExactly("S1");
            assertThat(java.util.Arrays.stream(SaveCoupon.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("issuerEntityNo", "entityNo", "actorNo");
        }
    }

    @Test
    void serverIllegalArgumentStillReturnsInternalErrorAfterSuccessfulBinding() throws Exception {
        try (var f = new Fixture()) {
            when(f.service.execute(eq(ACTOR), eq("M1"), eq("B1"), eq("generateStatement"),
                    any(GenerateStatement.class), eq("7"), eq("original")))
                    .thenThrow(new IllegalArgumentException("Internal processing defect"));
            f.mvc.perform(post(PATH).header("Idempotency-Key", "original").contentType("application/json")
                    .content(f.body("generateStatement", Map.of("entityNo", "E1", "currency", "CNY",
                            "throughAt", "2026-01-01T00:00:00Z"), false)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10500));
            verify(f.service).execute(eq(ACTOR), eq("M1"), eq("B1"), eq("generateStatement"),
                    eq(new GenerateStatement("E1", "CNY", Instant.parse("2026-01-01T00:00:00Z"))),
                    eq("7"), eq("original"));
        }
    }

    private Map<String, Object> activity() {
        var input = new LinkedHashMap<String, Object>();
        input.put("name", "满减"); input.put("type", "reduction"); input.put("status", "draft");
        input.put("storeIds", List.of("S1")); input.put("currency", "CNY"); input.put("currencyScale", 2);
        input.put("amountMinor", 500); input.put("thresholdMinor", 1000);
        input.put("startsAt", "2026-11-01T00:00:00Z"); input.put("endsAt", "2026-12-01T00:00:00Z");
        input.put("ruleLabel", "展示标签");
        return input;
    }

    private Map<String, Object> coupon() {
        var input = new LinkedHashMap<String, Object>();
        input.put("name", "现金券"); input.put("type", "cashCoupon"); input.put("status", "draft");
        input.put("storeIds", List.of("S1")); input.put("currency", "CNY"); input.put("currencyScale", 2);
        input.put("faceValueMinor", 500); input.put("thresholdMinor", 1000); input.put("validDays", 30);
        input.put("limit", 100); input.put("perPerson", 1); input.put("ruleLabel", "展示标签");
        return input;
    }

    private static final class Fixture implements AutoCloseable {
        private final ManagementAccess access = mock(ManagementAccess.class);
        private final BrandMarketingApplicationService service = mock(BrandMarketingApplicationService.class);
        private final LocalValidatorFactoryBean validation = new LocalValidatorFactoryBean();
        private final ObjectMapper json = new ObjectMapper();
        private final MockMvc mvc;

        private Fixture() {
            when(access.actor()).thenReturn(ACTOR);
            validation.afterPropertiesSet();
            mvc = MockMvcBuilders.standaloneSetup(new BizBrandMarketingController(access, service))
                    .setValidator(validation).setControllerAdvice(new GlobalExceptionHandler()).build();
        }

        private String body(String action, Map<String, Object> payload, boolean payloadFirst) {
            var body = new LinkedHashMap<String, Object>();
            if (payloadFirst) body.put("payload", payload);
            body.put("action", action); body.put("expectedRevision", "7");
            if (!payloadFirst) body.put("payload", payload);
            return json.writeValueAsString(body);
        }

        private void valid(String action, Map<String, Object> payload, boolean payloadFirst) throws Exception {
            mvc.perform(post(PATH).header("Idempotency-Key", "original").contentType("application/json")
                    .content(body(action, payload, payloadFirst))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        private void invalid(String action, Map<String, Object> payload) throws Exception {
            var result = mvc.perform(post(PATH).header("Idempotency-Key", "original").contentType("application/json")
                    .content(body(action, payload, false))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(10400)).andReturn();
            assertThat(result.getResolvedException()).isInstanceOfAny(HttpMessageNotReadableException.class,
                    MethodArgumentNotValidException.class);
        }

        @Override
        public void close() { validation.close(); }
    }
}
