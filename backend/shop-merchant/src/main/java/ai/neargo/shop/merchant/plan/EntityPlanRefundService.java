package ai.neargo.shop.merchant.plan;

import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Actor;
import java.util.List;

/** Order-scoped offline exception refunds, using canonical received money. */
public interface EntityPlanRefundService {
    List<OfflineCollectionPort.CashReceipt> receipts(Actor actor,String orderNo);
    List<OfflineCashRefundPort.View> refunds(Actor actor,String orderNo);
    OfflineCashRefundPort.View request(Actor operator,String orderNo,OfflineCashRefundPort.Request command);
    OfflineCashRefundPort.View payout(Actor operator,String orderNo,String refundNo,OfflineCashRefundPort.Payout command);
    OfflineCashRefundPort.View recover(Actor operator,String orderNo,String refundNo);
}
