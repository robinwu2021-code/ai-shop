package ai.neargo.shop.portal.biz;
import org.springframework.beans.factory.annotation.Autowired;


import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.EntitlementScopeResolver;
import ai.neargo.shop.paybridge.dto.StoredValueRechargeProgress;
import ai.neargo.shop.payclient.StoredValuePlanRechargeAppService;
import ai.neargo.shop.payclient.StoredValueRechargeAppService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ReferenceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Replay;
import ai.neargo.shop.paybridge.StoredValueRequestFingerprint;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CollectionMethod;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@RestController
public class BizStoredValuePlanRechargeController {
    @Autowired
    private ai.neargo.shop.paybridge.StoredValuePlanGovernanceBinding governanceBinding;
    private final StoredValuePlanRechargeAppService direct;
    private final StoredValueRechargeAppService receipts;
    private final EntitlementScopeResolver scopes;
    private final CustomerIdentityPort customers;
    private final StoreDirectoryPort stores;
    private final StoredValueRequestFingerprint fingerprints;
    public BizStoredValuePlanRechargeController(StoredValuePlanRechargeAppService direct,
            StoredValueRechargeAppService receipts, EntitlementScopeResolver scopes,
            CustomerIdentityPort customers, StoreDirectoryPort stores,StoredValueRequestFingerprint fingerprints) {
        this.direct=direct;this.receipts=receipts;this.scopes=scopes;this.customers=customers;this.stores=stores;this.fingerprints=fingerprints;
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"') and @perm.canBiz('"+BizPerms.SV_RECHARGE_RECEIVE+"')")
    @PostMapping("/biz/stored-value/plan-recharges/confirm-offline")
    public StoredValueRechargeProgress confirmOffline(@RequestBody CreateRequest c,@RequestHeader("Idempotency-Key") String key){
        requireStore(c.storeNo(),BizPerms.SV_RECHARGE_CREATE);
        requireStore(c.storeNo(),BizPerms.SV_RECHARGE_RECEIVE);
        var ctx=context(key);String entity=BizContext.requireMerchantNo();
        var card=stores.cards(List.of(c.storeNo())).get(c.storeNo());
        if(card==null||!card.active()||!entity.equals(card.entityNo()))throw BizException.of(ErrorCode.NOT_FOUND);
        var prior=direct.replay(ctx,entity,replay(c));
        var user=prior.found()?new CustomerIdentityPort.ResolvedCustomer(prior.recharge().userNo(),prior.recharge().customerNo()):customers.resolveReference(ctx,new ReferenceQuery(entity,c.storeNo(),c.customerRef()));
        return StoredValueRechargeProgress.from(direct.confirmOffline(ctx,entity,
                new Create(user.userNo(),c.storeNo(),card.storeName(),c.planNo(),c.versionNo(),replay(c).referenceHash(),user.customerNo(),prior.found()?null:governanceBinding.resolve(ctx.tenantNo(),entity,c.storeNo(),c.versionNo()))));
    }

    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"')")
    @PostMapping("/biz/stored-value/plan-recharges")
    public StoredValueRechargeProgress create(@RequestBody CreateRequest c,@RequestHeader("Idempotency-Key") String key){
        requireStore(c.storeNo(),BizPerms.SV_RECHARGE_CREATE);
        var ctx=context(key);String entity=BizContext.requireMerchantNo();
        var card=stores.cards(List.of(c.storeNo())).get(c.storeNo());
        if(card==null||!card.active()||!entity.equals(card.entityNo()))throw BizException.of(ErrorCode.NOT_FOUND);
        var prior=direct.replay(ctx,entity,replay(c));
        if(prior.found())return StoredValueRechargeProgress.from(prior.recharge());
        var user=customers.resolveReference(ctx,new ReferenceQuery(entity,c.storeNo(),c.customerRef()));
        return StoredValueRechargeProgress.from(direct.create(ctx,entity,
                new Create(user.userNo(),c.storeNo(),card.storeName(),c.planNo(),c.versionNo(),replay(c).referenceHash(),user.customerNo(),prior.found()?null:governanceBinding.resolve(ctx.tenantNo(),entity,c.storeNo(),c.versionNo()))));
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_RECHARGE_READ+"') or @perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"')")
    @GetMapping("/biz/stored-value/plan-recharges/{rechargeNo}")
    public StoredValueRechargeProgress query(@PathVariable String rechargeNo){
        return StoredValueRechargeProgress.from(original(context("read-plan-recharge"),rechargeNo,BizContext.current().can(BizPerms.SV_RECHARGE_READ)?BizPerms.SV_RECHARGE_READ:BizPerms.SV_RECHARGE_CREATE));
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"')")
    @PostMapping("/biz/stored-value/plan-recharges/{rechargeNo}/submit")
    public StoredValueRechargeProgress submit(@PathVariable String rechargeNo,@RequestBody SubmitRequest c,
            @RequestHeader("Idempotency-Key") String key){
        var ctx=context(key);var original=original(ctx,rechargeNo,BizPerms.SV_RECHARGE_CREATE);
        return StoredValueRechargeProgress.from(receipts.submit(ctx,BizContext.requireMerchantNo(),new SubmitRecharge(original.rechargeNo(),
                c.expectedRevision(),c.actualAmountMinor(),c.collectionMethod(),c.receiptNo(),receiptDigest(ctx,original.rechargeNo(),c.receiptNo()))),original.documentNo());
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_RECHARGE_RECEIVE+"')")
    @PostMapping("/biz/stored-value/plan-recharges/{rechargeNo}/receive")
    public StoredValueRechargeProgress receive(@PathVariable String rechargeNo,@RequestBody ReceiveRequest c,
            @RequestHeader("Idempotency-Key") String key){
        var ctx=context(key);var original=original(ctx,rechargeNo,BizPerms.SV_RECHARGE_RECEIVE);
        return StoredValueRechargeProgress.from(receipts.receive(ctx,BizContext.requireMerchantNo(),new ReceiveRecharge(original.rechargeNo(),
                c.expectedRevision(),receiptDigest(ctx,original.rechargeNo(),c.receiptNo()),c.confirmationNote())),original.documentNo());
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"')")
    @PostMapping("/biz/stored-value/plan-recharges/{rechargeNo}/cancel")
    public StoredValueRechargeProgress cancel(@PathVariable String rechargeNo,@RequestBody CancelRequest c,
            @RequestHeader("Idempotency-Key") String key){
        var ctx=context(key);var original=original(ctx,rechargeNo,BizPerms.SV_RECHARGE_CREATE);
        return StoredValueRechargeProgress.from(receipts.cancel(ctx,BizContext.requireMerchantNo(),new CancelRecharge(original.rechargeNo(),
                c.expectedRevision(),c.reason())),original.documentNo());
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"')")
    @PostMapping("/biz/stored-value/plan-recharges/resolve")
    public ResolveView resolve(@RequestBody CreateRequest c,@RequestHeader("Idempotency-Key") String key){
        requireStore(c.storeNo(),BizPerms.SV_RECHARGE_CREATE);
        var result=direct.replay(context(key),BizContext.requireMerchantNo(),replay(c));
        return new ResolveView(result.found(),result.found()?StoredValueRechargeProgress.from(result.recharge()):null);
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"')")
    @PostMapping("/biz/stored-value/plan-recharges/cancel-create")
    public StoredValueRechargeProgress cancelCreation(@RequestBody CreateRequest c,@RequestHeader("Idempotency-Key") String key){
        requireStore(c.storeNo(),BizPerms.SV_RECHARGE_CREATE);
        return StoredValueRechargeProgress.from(direct.cancelCreation(context(key),BizContext.requireMerchantNo(),replay(c)));
    }
    public record ResolveView(boolean found,StoredValueRechargeProgress recharge){ }
    private Replay replay(CreateRequest c){
        return new Replay(c.storeNo(),c.planNo(),c.versionNo(),fingerprints.reference(c.customerRef(),c.storeNo(),c.planNo(),c.versionNo()));
    }
    private RechargeView original(EntitlementContext ctx,String no,String permission){
        var r=direct.query(ctx,BizContext.requireMerchantNo(),no);requireStore(r.storeNo(),permission);return r;
    }
    private void requireStore(String store,String permission){
        scopes.resolve(new RedemptionScope("STORES",List.of(store)));
        if(!BizContext.current().withStore(store).can(permission))throw BizException.of(ErrorCode.FORBIDDEN);
    }
    private static EntitlementContext context(String key){
        ContractChecks.id(key);var u=SecurityUtils.requireUser();
        return new EntitlementContext(u.tenantNo(),u.userNo(),key,"0".repeat(64));
    }
    private static String receiptDigest(EntitlementContext ctx,String no,String receipt){
        return ActivityCommandDeduplicator.hash(ctx.tenantNo()+"|"+BizContext.requireMerchantNo()+"|"+no+"|"+receipt);
    }
    public record SubmitRequest(long expectedRevision,long actualAmountMinor,CollectionMethod collectionMethod,String receiptNo){
        public SubmitRequest {ContractChecks.require(expectedRevision>=0&&actualAmountMinor>0);ContractChecks.present(collectionMethod);ContractChecks.id(receiptNo);}
        @Override public String toString(){return "SubmitRequest[evidence=REDACTED]";}
    }
    public record ReceiveRequest(long expectedRevision,String receiptNo,String confirmationNote){
        public ReceiveRequest {ContractChecks.require(expectedRevision>=0);ContractChecks.id(receiptNo);}
        @Override public String toString(){return "ReceiveRequest[evidence=REDACTED]";}
    }
    public record CancelRequest(long expectedRevision,String reason){ }
    public record CreateRequest(String customerRef,String storeNo,String planNo,String versionNo){
        public CreateRequest {ContractChecks.id(customerRef);ContractChecks.id(storeNo);ContractChecks.id(planNo);ContractChecks.id(versionNo);}
        @Override public String toString(){return "CreateRequest[customerRef=REDACTED]";}
    }
}
