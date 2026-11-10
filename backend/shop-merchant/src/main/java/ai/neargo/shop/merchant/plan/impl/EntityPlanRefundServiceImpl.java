package ai.neargo.shop.merchant.plan.impl;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.mall.MallException;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.plan.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import java.util.*;
import java.util.function.Supplier;

public final class EntityPlanRefundServiceImpl implements EntityPlanRefundService {
    private final EntityPlanPurchaseService plans;private final OfflineCashRefundPort refunds;private final OfflineCollectionPort cash;
    public EntityPlanRefundServiceImpl(EntityPlanPurchaseService plans,OfflineCashRefundPort refunds,OfflineCollectionPort cash){this.plans=plans;this.refunds=refunds;this.cash=cash;}
    private OfflineCashRefundPort.Target target(Actor a,String no,boolean operator){if(operator&&!a.operator())throw BizException.of(ErrorCode.FORBIDDEN);var o=plans.order(a,no);return new OfflineCashRefundPort.Target(new StoreRef("MAIN",o.storeNo()),no,"REVIEW_REQUIRED".equals(o.status()));}
    private <T>T invoke(Supplier<T> task){try{return task.get();}catch(MallException e){throw BizException.of(ErrorCode.BAD_REQUEST);}}
    public List<OfflineCollectionPort.CashReceipt> receipts(Actor a,String no){target(a,no,true);return cash.receipts("MAIN",no);}
    public List<OfflineCashRefundPort.View> refunds(Actor a,String no){var target=target(a,no,false);return invoke(()->refunds.list(target));}
    public OfflineCashRefundPort.View request(Actor a,String no,OfflineCashRefundPort.Request c){var target=target(a,no,true);return invoke(()->refunds.request(target,a.userNo(),c));}
    public OfflineCashRefundPort.View payout(Actor a,String no,String refund,OfflineCashRefundPort.Payout c){var target=target(a,no,true);if(c==null)throw BizException.of(ErrorCode.BAD_REQUEST);plans.verifyRefundEvidence(a,no,c.evidenceKey());return invoke(()->refunds.payout(target,a.userNo(),refund,c));}
    public OfflineCashRefundPort.View recover(Actor a,String no,String refund){var target=target(a,no,true);return invoke(()->refunds.recover(target,refund));}
}
