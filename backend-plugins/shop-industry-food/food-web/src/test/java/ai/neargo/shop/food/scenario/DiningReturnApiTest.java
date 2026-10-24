package ai.neargo.shop.food.scenario;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.food.api.mp.MpDiningReturnController;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningReturnVO;
import ai.neargo.shop.food.service.DiningReturnService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Login supplies the buyer; request amounts, buyer overrides and kitchen commands never reach the source owner. */
class DiningReturnApiTest {
    private static final String BODY = """
            {"batchNo":"B","type":"REFUND_ONLY","reason":"reason",
             "lines":[{"sourceLineRef":"SRC","quantity":2}]}
            """;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private MockMvc mvc(DiningReturnService service) {
        return MockMvcBuilders.standaloneSetup(new MpDiningReturnController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
    }
    private void login() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                LoginUser.consumer("ACTUAL_BUYER", "Buyer"), null, List.of()));
    }
    private DiningReturnVO view() {
        return new DiningReturnVO("RETURN", "B", "A", "UNKNOWN", "UNKNOWN", "PENDING", null, "reason",
                List.of(new DiningReturnVO.Line("SRC", new BigDecimal("2"), "KEEP")), null);
    }
    @Test void derivesBuyerFromLoginAndPreservesUnknownAndIndependentKitchenTrack() throws Exception {
        DiningReturnService service = mock(DiningReturnService.class); login();
        when(service.customerApply(eq("A"), eq(new CartAccess(null, null, "ACTUAL_BUYER")), any(), eq("KEY")))
                .thenReturn(view());
        mvc(service).perform(post("/mp/x/food/stores/A/returns").header("Idempotency-Key", "KEY")
                .contentType("application/json").content(BODY))
                .andExpect(jsonPath("$.data.returnNo").value("RETURN"))
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.kitchenStatus").value("PENDING"));
        verify(service).customerApply(eq("A"), eq(new CartAccess(null, null, "ACTUAL_BUYER")), any(), eq("KEY"));
    }
    @Test void rejectsUnauthenticatedForgedAmountBuyerKitchenAndQueryScopeOverrides() throws Exception {
        DiningReturnService service = mock(DiningReturnService.class);
        var mvc = mvc(service);
        mvc.perform(post("/mp/x/food/stores/A/returns").header("Idempotency-Key", "KEY")
                .contentType("application/json").content(BODY)).andExpect(jsonPath("$.code").value(10403));
        login();
        for (String field : List.of("buyerNo", "refundMinor", "coreCaseRef", "kitchenStatus", "entityNo")) {
            mvc.perform(post("/mp/x/food/stores/A/returns").header("Idempotency-Key", "KEY")
                    .contentType("application/json").content(BODY.replace("\"batchNo\":\"B\"",
                            "\"batchNo\":\"B\",\"" + field + "\":\"FORGED\"")))
                    .andExpect(jsonPath("$.code").value(10400));
        }
        mvc.perform(get("/mp/x/food/returns/RETURN").param("buyerNo", "OTHER"))
                .andExpect(jsonPath("$.code").value(10400));
        mvc.perform(post("/mp/x/food/returns/RETURN:cancel").contentType("application/json")
                .content("{\"refundMinor\":1}")).andExpect(jsonPath("$.code").value(10400));
        verifyNoInteractions(service);
    }
}
