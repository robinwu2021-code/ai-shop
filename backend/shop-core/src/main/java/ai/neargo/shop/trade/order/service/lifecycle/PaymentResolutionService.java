package ai.neargo.shop.trade.order.service.lifecycle;

import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.contract.PaymentResolutionView;

import java.util.List;
import java.util.Map;

public interface PaymentResolutionService {
    void record(OrdOrder order, SettlePort.PaymentReceipt receipt, String reason);
    PaymentResolutionView view(String payNo, String buyerNo);
    /** 一页门店单共享父支付号，按本人批量读取安全的异常投影。 */
    Map<String, PaymentResolutionView> views(List<String> payNos, String buyerNo);
}
