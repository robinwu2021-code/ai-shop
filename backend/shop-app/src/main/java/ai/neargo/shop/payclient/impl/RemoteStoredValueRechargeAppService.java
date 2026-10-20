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
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayStoredValueRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ConsumptionQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.client.PayStoredValueRequests;

@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteStoredValueRechargeAppService implements StoredValueRechargeAppService {
    private final PayInternalApi pay;
    public RemoteStoredValueRechargeAppService(PayInternalApi pay) { this.pay = pay; }
    @Override public RechargeView create(EntitlementContext ctx, String entity, CreateRecharge c) {
        return RemoteEntitlementCalls.call(() -> pay.svCreate(new PayStoredValueRequests.Create(ctx, entity, c)));
    }
    @Override public RechargeView submit(EntitlementContext ctx, String entity, SubmitRecharge c) {
        return RemoteEntitlementCalls.call(() -> pay.svSubmit(new PayStoredValueRequests.Submit(ctx, entity, c)));
    }
    @Override public RechargeView receive(EntitlementContext ctx, String entity, ReceiveRecharge c) {
        return RemoteEntitlementCalls.call(() -> pay.svReceive(new PayStoredValueRequests.Receive(ctx, entity, c)));
    }
    @Override public RechargeView cancel(EntitlementContext ctx, String entity, CancelRecharge c) {
        return RemoteEntitlementCalls.call(() -> pay.svCancel(new PayStoredValueRequests.Cancel(ctx, entity, c)));
    }
    @Override public RechargeView credit(EntitlementContext ctx, CreditRecharge c) {
        return RemoteEntitlementCalls.call(() -> pay.svCredit(new PayStoredValueRequests.Credit(ctx, c)));
    }
    @Override public RechargeView query(EntitlementContext ctx, String entity, String no) {
        return RemoteEntitlementCalls.call(() -> pay.svQuery(new PayStoredValueRequests.QueryRecharge(ctx, entity, no)));
    }
    @Override public BalanceView balance(EntitlementContext ctx, BalanceQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.svBalance(new PayStoredValueRequests.Balance(ctx, q)));
    }
    @Override public CursorPage<StoreItem> stores(EntitlementContext ctx, StoreQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.svStores(new PayStoredValueRequests.Stores(ctx, q)));
    }
    @Override public CursorPage<UsageItem> consumptions(EntitlementContext ctx, ConsumptionQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.svConsumptions(new PayStoredValueRequests.Consumptions(ctx, q)));
    }
}
