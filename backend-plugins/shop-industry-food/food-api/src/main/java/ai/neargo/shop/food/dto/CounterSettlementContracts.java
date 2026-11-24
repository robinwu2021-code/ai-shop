package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 柜台后付结账的请求与返回（TDD-餐饮柜台后付结账）。 */
public final class CounterSettlementContracts {
    private CounterSettlementContracts() {
    }

    /** 确认收齐：带上用外部记账接口记的现金、刷卡等记账号（{@code bizNo} 必须是结算单号）。 */
    public record FinalizeReq(@NotEmpty @Size(max = 20) List<@Size(max = 64) String> offlineRecordNos) {
    }

    /**
     * @param status        FROZEN（已冻结待收款）/ COLLECTING（确认中）/ SETTLED / CANCELLED
     * @param receivedMinor 外部记账合计；确认前为 null
     * @param changeMinor   找零；确认前为 null
     * @param receiptNo     核心实收号；确认前为 null
     * @param checkStatus   台账状态：OPEN / CLOSED
     * @param tableReleased 桌台是否已释放（菜没上齐时确认后仍占用，上齐后由服务员释放）
     */
    public record CounterSettlementVO(String settlementNo, String checkNo, String status, String currency,
            long dueMinor, Long receivedMinor, Long changeMinor, String receiptNo, List<ItemVO> items,
            String checkStatus, boolean tableReleased) {
    }

    /** 结算单覆盖的一张后付核心单。 */
    public record ItemVO(String paymentRootNo, String storeOrderNo, long dueMinor) {
    }
}
