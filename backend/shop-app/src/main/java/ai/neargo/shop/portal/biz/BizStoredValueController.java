package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.StoredValueRechargeCoordinator;
import ai.neargo.shop.paybridge.dto.StoredValueRechargeProgress;
import ai.neargo.shop.promotion.dto.RechargeIntentCreate;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CollectionMethod;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Merchant collects outside the platform. Private evidence is submitted to pay and never echoed to consumer DTOs. */
@Profile("api")
@RestController
public class BizStoredValueController {
    private final StoredValueRechargeCoordinator recharges;
    private final RechargeIntentService intents;
    public BizStoredValueController(StoredValueRechargeCoordinator recharges, RechargeIntentService intents) {
        this.recharges = recharges; this.intents = intents;
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
}
