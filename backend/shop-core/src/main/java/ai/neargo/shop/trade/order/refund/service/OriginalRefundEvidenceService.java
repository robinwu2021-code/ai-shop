package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import java.util.List;

/** Original source cash, VAT and quantity evidence frozen with the existing refund execution. */
public interface OriginalRefundEvidenceService {
    RefundPlan freeze(OrdSubOrder child, List<OrdItem> items, RefundPlan selected);
    void verify(OrdSubOrder child, List<OrdItem> items, RefundPlan frozen);
}
