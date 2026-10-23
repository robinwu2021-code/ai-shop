package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import java.math.BigDecimal;
import java.util.List;

/** Financial progress comes from the original core case. Kitchen progress is an independent, unchanged track. */
public record DiningReturnVO(String returnNo, String batchNo, String storeNo, String status,
        String financialStatus, String kitchenStatus, String coreCaseRef, String reason,
        List<Line> lines, IndustryAfterSalePort.Result financialCase) {
    public DiningReturnVO { lines = List.copyOf(lines); }
    public record Line(String sourceLineRef, BigDecimal quantity, String kitchenDisposition) { }
}
