package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.CreateStaffDiningOrderReq;
import ai.neargo.shop.food.dto.CancelDiningOrderReq;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.foodclient.RemoteDiningOrderService;
import ai.neargo.shop.food.spi.DiningOrderInternalApi;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Typed standalone transport must preserve original buyer/employee scope and reject mismatched receipts. */
class StaffOrderTransportTest {
    private static final CreateStaffDiningOrderReq REQUEST = new CreateStaffDiningOrderReq(
            "BUYER", "CART", 3, "QUOTE", "a".repeat(64), "POSTPAY");
    private static DiningOrderBatchVO receipt(String store) {
        return new DiningOrderBatchVO("BATCH", store, null, "CART", 3, "QUOTE", "ACCEPTED", "POSTPAY",
                LocalDate.of(2026, 10, 24), "ORDER", "SUB", "WAIT_PAY", "CNY", 100L, 0);
    }
    @Test void remoteStaffPlacementPreservesIdentityAndOriginalIdempotencyKey() {
        var api = mock(DiningOrderInternalApi.class);
        when(api.staffPlace(any())).thenReturn(receipt("STORE"));
        var result = new RemoteDiningOrderService(api).staffPlace("ENTITY", "STORE", "STAFF", REQUEST, "ORIGINAL");
        assertThat(result.batchNo()).isEqualTo("BATCH");
        assertThat(result.coreStatus()).isEqualTo("WAIT_PAY");
        verify(api).staffPlace(new DiningOrderInternalApi.StaffPlace(
                "ENTITY", "STORE", "STAFF", REQUEST, "ORIGINAL"));
    }
    @Test void remoteStaffPlacementRejectsReceiptFromAnotherStore() {
        var api = mock(DiningOrderInternalApi.class);
        when(api.staffPlace(any())).thenReturn(receipt("OTHER"));
        assertThatThrownBy(() -> new RemoteDiningOrderService(api)
                .staffPlace("ENTITY", "STORE", "STAFF", REQUEST, "ORIGINAL"))
                .isInstanceOf(BizException.class);
    }
    @Test void pendingCancellationTransportPreservesActualActorAndOriginalKey() {
        var api = mock(DiningOrderInternalApi.class);
        var request = new CancelDiningOrderReq(7L, "guest withdrew");
        when(api.staffCancel(any())).thenReturn(receipt("STORE"));
        assertThat(new RemoteDiningOrderService(api).staffCancel(
                "ENTITY", "STORE", "SUPERVISOR", "BATCH", request, "ORIGINAL").batchNo()).isEqualTo("BATCH");
        verify(api).staffCancel(new DiningOrderInternalApi.StaffCancel(
                "ENTITY", "STORE", "SUPERVISOR", "BATCH", request, "ORIGINAL"));
        when(api.staffCancel(any())).thenReturn(receipt("OTHER"));
        assertThatThrownBy(() -> new RemoteDiningOrderService(api).staffCancel(
                "ENTITY", "STORE", "SUPERVISOR", "BATCH", request, "ORIGINAL")).isInstanceOf(BizException.class);
    }
    @Test void cancellationJsonCannotCoerceVersionsOrInjectAuthorityOrMoney() {
        var json = JsonMapper.builder().build();
        assertThat(json.readValue("{\"version\":0,\"reason\":\"withdraw\"}", CancelDiningOrderReq.class))
                .isEqualTo(new CancelDiningOrderReq(0L, "withdraw"));
        for (String value : new String[]{"0.5", "\"0\"", "true", "9223372036854775808"}) {
            assertThatThrownBy(() -> json.readValue("{\"version\":" + value + ",\"reason\":\"withdraw\"}",
                    CancelDiningOrderReq.class)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> json.readValue("{\"version\":0,\"reason\":\"withdraw\",\"actorNo\":\"FORGED\"}",
                CancelDiningOrderReq.class)).isInstanceOf(RuntimeException.class);
    }
    @Test void staffBodyCannotInjectMerchantEmployeeOrFinancialResult() {
        var json = JsonMapper.builder().build();
        String original = json.writeValueAsString(REQUEST);
        assertThat(json.readValue(original, CreateStaffDiningOrderReq.class)).isEqualTo(REQUEST);
        assertThatThrownBy(() -> json.readValue(original.replace("\"buyerNo\":\"BUYER\"", "\"buyerNo\":123"),
                CreateStaffDiningOrderReq.class)).isInstanceOf(RuntimeException.class);
        for (String field : new String[]{"entityNo", "operatorNo", "actorNo", "paidAt", "amountMinor"}) {
            String forged = original.substring(0, original.length() - 1) + ",\"" + field + "\":\"FORGED\"}";
            assertThatThrownBy(() -> json.readValue(forged, CreateStaffDiningOrderReq.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
