package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.StoredValueRechargeAppService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreateRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueQueryService;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ConsumptionQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;

@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class StoredValueRechargeAppServiceImpl implements StoredValueRechargeAppService {
    private final StoredValueRechargeService recharges;
    private final StoredValueCreditService credits;
    private final StoredValueQueryService queries;
    public StoredValueRechargeAppServiceImpl(StoredValueRechargeService recharges, StoredValueCreditService credits,
            StoredValueQueryService queries) {
        this.recharges = recharges; this.credits = credits; this.queries = queries;
    }
    @Override public RechargeView create(EntitlementContext ctx, String entity, CreateRecharge c) {
        return recharges.create(ctx, entity, c);
    }
    @Override public RechargeView submit(EntitlementContext ctx, String entity, SubmitRecharge c) {
        return recharges.submit(ctx, entity, c);
    }
    @Override public RechargeView receive(EntitlementContext ctx, String entity, ReceiveRecharge c) {
        return recharges.receive(ctx, entity, c);
    }
    @Override public RechargeView cancel(EntitlementContext ctx, String entity, CancelRecharge c) {
        return recharges.cancel(ctx, entity, c);
    }
    @Override public RechargeView credit(EntitlementContext ctx, CreditRecharge c) { return credits.credit(ctx, c); }
    @Override public RechargeView query(EntitlementContext ctx, String entity, String no) {
        return recharges.query(ctx, entity, no);
    }
    @Override public BalanceView balance(EntitlementContext ctx, BalanceQuery q) { return queries.balance(ctx, q); }
    @Override public CursorPage<StoreItem> stores(EntitlementContext ctx, StoreQuery q) { return queries.stores(ctx, q); }
    @Override public CursorPage<UsageItem> consumptions(EntitlementContext ctx, ConsumptionQuery q) {
        return queries.consumptions(ctx, q);
    }
}
