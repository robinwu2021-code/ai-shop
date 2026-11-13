package ai.neargo.shop.food.foodclient;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.spi.CounterSettlementInternalApi;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 拆分部署时收银台仍分得清「还差钱」「账单变了」：远端业务码只在请求摘要对得上时采信。 */
class RemoteCounterSettlementServiceTest {
    private static ServiceCallException remote(String code, String digest) {
        return new ServiceCallException(CallOutcome.REMOTE_ERROR, "FOOD", 409, "FOOD 返回 409", null, code, digest);
    }

    @Test
    void boundBusinessCodesSurviveTheHopAndUnboundOnesDegradeToConflict() {
        var request = new CounterSettlementInternalApi.Finalize("E", "S", "A", "FSET1", List.of("OFR1"));
        String digest = CounterSettlementInternalApi.digest(request);
        assertThat(RemoteCounterSettlementService.business(remote("POSTPAY_RECEIPT_UNDERPAID", digest), digest)
                .errorCode()).isEqualTo(ErrorCode.POSTPAY_RECEIPT_UNDERPAID);
        assertThat(RemoteCounterSettlementService.business(remote("COUNTER_SETTLEMENT_NOT_READY", digest), digest)
                .errorCode()).isEqualTo(ErrorCode.COUNTER_SETTLEMENT_NOT_READY);
        // 别的请求的摘要 → 不采信，按状态码退化
        String other = CounterSettlementInternalApi.digest(
                new CounterSettlementInternalApi.Finalize("E", "S", "A", "FSET2", List.of("OFR1")));
        assertThat(other).isNotEqualTo(digest);
        assertThat(RemoteCounterSettlementService.business(remote("POSTPAY_RECEIPT_UNDERPAID", other), digest)
                .errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(RemoteCounterSettlementService.business(remote(null, null), digest).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }
}
