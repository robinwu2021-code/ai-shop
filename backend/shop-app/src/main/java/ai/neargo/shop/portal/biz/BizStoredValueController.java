package ai.neargo.shop.portal.biz;

import ai.neargo.shop.spi.storedvalue.StoredValueExecutionScopePort;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.paybridge.StoredValueRechargeCoordinator;
import ai.neargo.shop.paybridge.dto.EntitlementServiceViews;
import ai.neargo.shop.paybridge.dto.StoredValueRechargeProgress;
import ai.neargo.shop.payclient.StoredValueRechargeAppService;
import ai.neargo.shop.promotion.dto.RechargeIntentCreate;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ReferenceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CollectionMethod;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import ai.neargo.shop.trade.dto.ServiceOrderCreate;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Merchant collects outside the platform. Private evidence is submitted to pay and never echoed to consumer DTOs. */
@Profile("api")
@RestController
public class BizStoredValueController {
    private final StoredValueRechargeCoordinator recharges;
    private final RechargeIntentService intents;
    private final StoredValueRechargeAppService storedValue;
    private final CustomerIdentityPort customers;
    private final EntitlementServiceOrderService serviceOrders;
    private final EntitlementServiceUseCoordinator serviceUses;
    private final StoredValueUseQuotePort useQuotes;
    @Autowired private StoredValueExecutionScopePort walletScopes;
    @Value("${shop.pay.stored-value.brand-execution-enabled:false}") private boolean walletSelectionEnabled;
    public BizStoredValueController(StoredValueRechargeCoordinator recharges, RechargeIntentService intents,
            StoredValueRechargeAppService storedValue, CustomerIdentityPort customers,
            EntitlementServiceOrderService serviceOrders, EntitlementServiceUseCoordinator serviceUses,
            StoredValueUseQuotePort useQuotes) {
        this.recharges = recharges; this.intents = intents; this.storedValue = storedValue; this.customers = customers;
        this.serviceOrders = serviceOrders; this.serviceUses = serviceUses; this.useQuotes = useQuotes;
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_RECHARGE_CREATE + "')")
    @PostMapping("/biz/stored-value/recharges")
    public StoredValueRechargeProgress create(@RequestBody RechargeIntentCreate c,
                                               @RequestHeader("Idempotency-Key") String key) {
        var ctx = context(key); String entity = BizContext.requireMerchantNo();
        requireStore(c.storeNo(), BizPerms.SV_RECHARGE_CREATE);
        return recharges.create(ctx, entity, c, key);
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_RECHARGE_CREATE + "')")
    @PostMapping("/biz/stored-value/recharges/{rechargeNo}/submit")
    public StoredValueRechargeProgress submit(@PathVariable String rechargeNo, @RequestBody SubmitRequest c,
                                               @RequestHeader("Idempotency-Key") String key) {
        var ctx = context(key); String entity = BizContext.requireMerchantNo();
        requireOriginalStore(ctx, entity, rechargeNo, BizPerms.SV_RECHARGE_CREATE);
        return recharges.submit(ctx, entity, new SubmitRecharge(rechargeNo, c.expectedRevision(), c.actualAmountMinor(),
                c.collectionMethod(), c.receiptRef(), c.receiptDigest()));
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_RECHARGE_RECEIVE + "')")
    @PostMapping("/biz/stored-value/recharges/{rechargeNo}/receive")
    public StoredValueRechargeProgress receive(@PathVariable String rechargeNo, @RequestBody ReceiveRequest c,
                                                @RequestHeader("Idempotency-Key") String key) {
        var ctx = context(key); String entity = BizContext.requireMerchantNo();
        requireOriginalStore(ctx, entity, rechargeNo, BizPerms.SV_RECHARGE_RECEIVE);
        return recharges.receive(ctx, entity, new ReceiveRecharge(rechargeNo, c.expectedRevision(), c.receiptDigest(),
                c.confirmationNote()));
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_RECHARGE_CREATE + "')")
    @PostMapping("/biz/stored-value/recharges/{rechargeNo}/cancel")
    public StoredValueRechargeProgress cancel(@PathVariable String rechargeNo, @RequestBody CancelRequest c,
                                               @RequestHeader("Idempotency-Key") String key) {
        var ctx = context(key); String entity = BizContext.requireMerchantNo();
        requireOriginalStore(ctx, entity, rechargeNo, BizPerms.SV_RECHARGE_CREATE);
        return recharges.cancel(ctx, entity, new CancelRecharge(rechargeNo, c.expectedRevision(), c.reason()));
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_RECHARGE_READ + "')")
    @GetMapping("/biz/stored-value/recharges/{rechargeNo}")
    public StoredValueRechargeProgress query(@PathVariable String rechargeNo) {
        var ctx = context("read-recharge"); String entity = BizContext.requireMerchantNo();
        requireOriginalStore(ctx, entity, rechargeNo, BizPerms.SV_RECHARGE_READ);
        return recharges.query(ctx, entity, rechargeNo);
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_ACCOUNT_READ + "')")
    @GetMapping("/biz/stored-value/accounts")
    public StoredValueBalanceView balance(@RequestParam String customerRef,
            @RequestParam(required = false) String storeNo,
            @RequestParam(defaultValue = "CNY") String currency) {
        var ctx = context("read-stored-value-account"); String entity = BizContext.requireMerchantNo();
        String store = storeNo == null ? BizContext.current().currentStoreNo() : storeNo;
        ContractChecks.id(customerRef); ContractChecks.id(store); ContractChecks.currency(currency);
        requireStore(store, BizPerms.SV_ACCOUNT_READ);
        String userNo = customers.resolveReference(ctx, new ReferenceQuery(entity, store, customerRef)).userNo();
        var balance = storedValue.balance(ctx, new BalanceQuery(userNo, entity, currency, store));
        return StoredValueBalanceView.from(balance);
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_CONSUME_CREATE + "')")
    @PostMapping("/biz/stored-value/consumptions/preview")
    public ConsumptionPreview previewConsumption(@RequestBody ConsumptionPreviewRequest c,
            @RequestHeader("Idempotency-Key") String key) {
        var ctx = context(key); String entity = BizContext.requireMerchantNo();
        requireStore(c.storeNo(), BizPerms.SV_CONSUME_CREATE);
        // Core resolves the reference only when creating; original-key replay verifies its frozen payload instead.
        boolean selected=c.issuerRef()!=null;
        if(selected&&!walletSelectionEnabled)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var create=new ServiceOrderCreate(c.customerRef(),c.storeNo(),c.items());
        var order=selected?serviceOrders.createMonetarySelected(ctx,entity,create,key,ActivityCommandDeduplicator.hash(c.issuerRef()+"\n"+c.accountRef())):serviceOrders.createMonetary(ctx,entity,create,key);
        var quote = order.preview() != null && order.preview().moneyQuote() != null
                && (order.command() != null || java.time.Instant.now().isBefore(order.preview().expiresAt()))
                ? order.preview().moneyQuote() : selected?selectedQuote(ctx,entity,c,order):useQuotes.quote(ctx,order.fact().source());
        if (quote.redeemable() && (order.preview() == null || order.preview().moneyQuote() == null
                || !quote.quoteRef().equals(order.preview().ref()))) {
            order = serviceOrders.bindMoneyPreview(ctx, entity, c.storeNo(), order.serviceOrderNo(), quote);
        }
        return new ConsumptionPreview(EntitlementServiceViews.consumer(order), StoredValueQuoteView.from(quote));
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_CONSUME_CREATE + "')")
    @PostMapping("/biz/stored-value/consumptions")
    public EntitlementServiceViews.Operation consume(@RequestBody StoredValueConsumeRequest c) {
        var ctx = context(java.util.UUID.randomUUID().toString()); String entity = BizContext.requireMerchantNo();
        var order = serviceOrders.requireMerchant(ctx, entity, c.orderNo());
        requireStore(order.fact().storeNo(), BizPerms.SV_CONSUME_CREATE);
        if (order.fact().source().sourceRevision() != c.orderRevision() || order.preview() == null
                || order.preview().moneyQuote() == null) throw BizException.of(ErrorCode.CONFLICT);
        if (order.command() == null) {
            String userNo = customers.resolveReference(ctx,
                    new ReferenceQuery(entity, order.fact().storeNo(), c.customerRef())).userNo();
            if (!userNo.equals(order.fact().userNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // Stage validates the original authorization on replay, without relying on an expired short-lived reference.
        var result = serviceUses.submit(ctx, entity, order.fact().storeNo(), new ServiceUseSubmit(order.serviceOrderNo(),
                order.preview().ref(), c.authorizationRef(), order.preview().assetRevision()));
        return EntitlementServiceViews.Operation.from(result);
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_CONSUME_CREATE+"')")
    @GetMapping("/biz/stored-value/consumptions/orders/{orderNo}")
    public EntitlementServiceViews.ServiceView consumptionOrder(@PathVariable String orderNo,@RequestParam String storeNo){
        requireStore(storeNo,BizPerms.SV_CONSUME_CREATE);
        return EntitlementServiceViews.consumer(serviceOrders.requireMerchant(context("read-sv-service"),BizContext.requireMerchantNo(),storeNo,orderNo));
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_CONSUME_CREATE+"')")
    @PostMapping("/biz/stored-value/consumptions/orders/{orderNo}/cancel")
    public EntitlementServiceViews.Operation cancelConsumption(@PathVariable String orderNo,@RequestBody CancelConsumption c){
        requireStore(c.storeNo(),BizPerms.SV_CONSUME_CREATE);var ctx=context("cancel-sv-service");String entity=BizContext.requireMerchantNo();serviceOrders.requireMerchant(ctx,entity,c.storeNo(),orderNo);
        return EntitlementServiceViews.Operation.from(serviceUses.cancel(ctx,entity,c.storeNo(),orderNo,"Merchant cancelled service"));
    }
    public record CancelConsumption(String storeNo){public CancelConsumption{ContractChecks.id(storeNo);}}
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_CONSUME_CREATE+"')")
    @PostMapping("/biz/stored-value/consumptions/resolve")
    public ConsumptionResolution resolveConsumption(@RequestBody ConsumptionPreviewRequest c,@RequestHeader("Idempotency-Key")String key){
        requireStore(c.storeNo(),BizPerms.SV_CONSUME_CREATE);var ctx=context(key);String entity=BizContext.requireMerchantNo();
        var known=serviceOrders.findOperation(ctx.tenantNo(),entity,serviceOrders.operationNoForKey(ctx,entity,key));
        if(known==null)return new ConsumptionResolution(false,null);
        var create=new ServiceOrderCreate(c.customerRef(),c.storeNo(),c.items());
        // Exact existing payload replay validates the original selection, without resolving expired references.
        var original=c.issuerRef()==null?serviceOrders.createMonetary(ctx,entity,create,key):serviceOrders.createMonetarySelected(ctx,entity,create,key,ActivityCommandDeduplicator.hash(c.issuerRef()+"\n"+c.accountRef()));
        return new ConsumptionResolution(true,EntitlementServiceViews.consumer(original));
    }
    public record ConsumptionResolution(boolean found,EntitlementServiceViews.ServiceView service){}
    private StoredValueUseQuotePort.Quote selectedQuote(EntitlementContext ctx,String entity,ConsumptionPreviewRequest command,ServiceOrderSnapshot order){
        var customer=customers.resolveReference(ctx,new ReferenceQuery(entity,command.storeNo(),command.customerRef()));
        var scope=walletScopes.current(ctx.tenantNo(),ctx.actorNo(),entity,command.storeNo(),order.fact().currency());
        var query=new StoredValueWallets.Query(scope,new StoredValueWallets.Customer(customer.customerNo(),customer.userNo()));
        return useQuotes.quote(ctx,order.fact().source(),new StoredValueWallets.Choice(query,command.issuerRef(),command.accountRef()));
    }
    private void requireOriginalStore(EntitlementContext ctx, String entity, String no, String permission) {
        var p = intents.byRechargeNo(ctx.tenantNo(), no);
        if (!entity.equals(p.permit().entityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        requireStore(p.permit().storeNo(), permission);
    }
    private void requireStore(String store, String permission) {
        var ctx = BizContext.current(); var allowed = ctx.allowedStoresOrAll();
        if (allowed != null && !allowed.contains(store)) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!ctx.withStore(store).can(permission)) throw BizException.of(ErrorCode.FORBIDDEN);
    }
    private EntitlementContext context(String key) {
        ContractChecks.id(key);
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT) throw BizException.of(ErrorCode.FORBIDDEN);
        return new EntitlementContext(user.tenantNo(), user.userNo(), ActivityCommandDeduplicator.hash(key),
                "0".repeat(64));
    }
    public record SubmitRequest(long expectedRevision, long actualAmountMinor, CollectionMethod collectionMethod,
                                 String receiptRef, String receiptDigest) {
        @Override public String toString() { return "SubmitRequest[evidence=REDACTED]"; }
    }
    public record ReceiveRequest(long expectedRevision, String receiptDigest, String confirmationNote) {
        @Override public String toString() { return "ReceiveRequest[evidence=REDACTED]"; }
    }
    public record CancelRequest(long expectedRevision, String reason) { }
    public record ConsumptionPreviewRequest(String customerRef,String storeNo,List<ServiceOrderCreate.Line> items,String issuerRef,String accountRef) {
        public ConsumptionPreviewRequest(String customerRef,String storeNo,List<ServiceOrderCreate.Line> items){this(customerRef,storeNo,items,null,null);}
        public ConsumptionPreviewRequest {
            ContractChecks.require(issuerRef==null?accountRef==null:accountRef!=null&&!issuerRef.isBlank()&&!accountRef.isBlank()&&issuerRef.length()<=2048&&accountRef.length()<=2048);
            ContractChecks.id(customerRef); ContractChecks.id(storeNo);
            items = items == null ? List.of() : List.copyOf(items);
            ContractChecks.require(!items.isEmpty());
        }
        @Override public String toString() { return "ConsumptionPreviewRequest[customerRef=REDACTED, storeNo="
                + storeNo + ", items=" + items.size() + "]"; }
    }
    public record ConsumptionPreview(EntitlementServiceViews.ServiceView service, StoredValueQuoteView quote) { }
    public record StoredValueQuoteView(String quoteRef, String currency, long amountMinor, long applicableMinor,
                                       boolean redeemable, StoredValueUseQuotePort.Reason reason,
                                       long assetRevision, String selectionDigest, java.time.Instant expiresAt,String scopeType,String issuerName) {
        static StoredValueQuoteView from(StoredValueUseQuotePort.Quote q) {
            return new StoredValueQuoteView(q.quoteRef(), q.currency(), q.amountMinor(), q.applicableMinor(),
                    q.redeemable(),q.reason(),q.assetRevision(),q.snapshotHash(),q.expiresAt(),q.walletProof()==null?"STORE":q.walletProof().selected().scopeType(),q.walletProof()==null?null:q.walletProof().selected().issuerName());
        }
    }
    /** Public merchant view contains totals only; the pay account identifier and pool splits stay internal. */
    public record StoredValueBalanceView(String currency, long balanceMinor, long heldMinor, long availableMinor,
                                         Long applicableMinor) {
        static StoredValueBalanceView from(BalanceView balance) {
            return new StoredValueBalanceView(balance.currency(), balance.balanceMinor(), balance.heldMinor(),
                    balance.availableMinor(), balance.applicableMinor());
        }
    }
    public record StoredValueConsumeRequest(String customerRef, String orderNo, long orderRevision,
                                             String authorizationRef) {
        public StoredValueConsumeRequest {
            ContractChecks.id(customerRef); ContractChecks.id(orderNo); ContractChecks.require(orderRevision >= 0);
            ContractChecks.optionalId(authorizationRef);
        }
        @Override public String toString() { return "StoredValueConsumeRequest[customerRef=REDACTED, orderNo="
                + orderNo + ", authorizationRef=REDACTED]"; }
    }
}
