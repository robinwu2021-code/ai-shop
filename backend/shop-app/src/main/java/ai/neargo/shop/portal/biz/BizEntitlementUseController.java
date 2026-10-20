package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.payclient.EntitlementCodeAppService;
import ai.neargo.shop.trade.dto.ServiceOrderIdentifiedCreate;
import ai.neargo.shop.paybridge.dto.EntitlementServiceViews;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.paybridge.dto.EntitlementServiceViews.Operation;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.trade.dto.ServiceOrderCreate;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import ai.neargo.shop.trade.service.EntitlementOrderUseService;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Public requests choose projects/rights; source owner derives recipient, units, currency and original operation. */
@Profile("api")
@RestController
public class BizEntitlementUseController {
    private final EntitlementServiceOrderService orders;
    private final EntitlementServiceUseCoordinator services;
    private final EntitlementOrderUseService ordinary;
    private final EntitlementUsePort pay;
    private final EntitlementCodeAppService codes;
    public BizEntitlementUseController(EntitlementServiceOrderService orders, EntitlementServiceUseCoordinator services,
                                       EntitlementOrderUseService ordinary, EntitlementUsePort pay,
                                       EntitlementCodeAppService codes) {
        this.orders = orders; this.services = services; this.ordinary = ordinary; this.pay = pay;
        this.codes = codes;
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_USE + "')")
    @PostMapping("/biz/entitlement-uses/preview")
    public EntitlementServiceViews.Preview preview(@RequestBody PreviewRequest q,
                                                   @RequestHeader("Idempotency-Key") String key) {
        if (key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        var ctx = context(key);
        String entity = BizContext.requireMerchantNo();
        requireStore(q.storeNo(), BizPerms.ENT_USE);
        String instance = q.instanceNo();
        ServiceOrderSnapshot order;
        if (q.code() != null) {
            String operation = orders.operationNoForKey(ctx, entity, key);
            var prior = orders.findOperation(ctx.tenantNo(), entity, operation);
            if (prior == null) {
                var located = codes.resolve(ctx, entity, q.storeNo(), q.code(), operation);
                instance = located.instanceNo();
                order = orders.createIdentified(ctx, entity, new ServiceOrderIdentifiedCreate(located.userNo(),
                        hash(q.code()), q.storeNo(), q.items()), key);
            } else {
                // Original source and identity digest allow replay after the short locator TTL expires.
                order = orders.createIdentified(ctx, entity, new ServiceOrderIdentifiedCreate(prior.fact().userNo(),
                        hash(q.code()), q.storeNo(), q.items()), key);
                if (prior.preview() == null) {
                    instance = codes.resolve(ctx, entity, q.storeNo(), q.code(), operation).instanceNo();
                } else instance = prior.preview().preview().instanceNo();
            }
            if (q.instanceNo() != null && !q.instanceNo().equals(instance)) throw BizException.of(ErrorCode.CONFLICT);
        } else {
            order = q.orderRef() == null ? orders.create(ctx, entity,
                    new ServiceOrderCreate(q.customerRef(), q.storeNo(), q.items()), key)
                    : orders.requireMerchant(ctx, entity, q.storeNo(), q.orderRef());
        }
        if (q.units() != null && q.units() != order.fact().items().stream().mapToInt(i -> i.quantity()).sum()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var result = services.preview(ctx, entity, q.storeNo(), order.serviceOrderNo(),
                instance, q.serviceDate());
        return new EntitlementServiceViews.Preview(order.operationNo(), order.serviceOrderNo(),
                order.fact().source(), result);
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_USE + "')")
    @PostMapping("/biz/entitlement-uses")
    public Operation submit(@RequestBody SubmitRequest q) {
        var ctx = context(UUID.randomUUID().toString());
        String entity = BizContext.requireMerchantNo();
        service(q.sourceRef());
        var order = orders.requireMerchant(ctx, entity, q.sourceRef().sourceNo());
        requireStore(order.fact().storeNo(), BizPerms.ENT_USE);
        if (!order.fact().source().equals(q.sourceRef())) throw BizException.of(ErrorCode.CONFLICT);
        return Operation.from(services.submit(ctx, entity, order.fact().storeNo(),
                new ServiceUseSubmit(order.serviceOrderNo(), q.previewRef(), q.authorizationRef(),
                        q.expectedAssetRevision())));
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_READ + "')")
    @GetMapping("/biz/entitlement-uses/{operationNo}")
    public Operation query(@PathVariable String operationNo) {
        var ctx = context("query-use"); String entity = BizContext.requireMerchantNo();
        var order = orders.findOperation(ctx.tenantNo(), entity, operationNo);
        if (order != null) {
            requireStore(order.fact().storeNo(), BizPerms.ENT_READ);
            String status = EntitlementServiceViews.status(order);
            if (List.of("WAIT_AUTH", "WAIT_SUBMIT", "EXPIRED").contains(status)) {
                return Operation.pending(operationNo, status);
            }
            return Operation.from(services.query(ctx, entity, operationNo));
        }
        var original = ordinary.find(ctx.tenantNo(), entity, operationNo);
        if (original == null) return Operation.pending(operationNo, State.NOT_FOUND.name());
        requireStore(original.command().storeNo(), BizPerms.ENT_READ);
        return Operation.from(pay.query(original.context(), entity, operationNo));
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_USE + "')")
    @PostMapping("/biz/entitlement-uses/{operationNo}/cancel")
    public Operation cancel(@PathVariable String operationNo, @RequestBody CancelRequest q) {
        var ctx = context("cancel-use"); String entity = BizContext.requireMerchantNo();
        var order = orders.findOperation(ctx.tenantNo(), entity, operationNo);
        if (order == null) throw BizException.of(ErrorCode.NOT_FOUND);
        requireStore(order.fact().storeNo(), BizPerms.ENT_USE);
        return Operation.from(services.cancel(ctx, entity, order.fact().storeNo(), order.serviceOrderNo(), q.reason()));
    }
    private EntitlementContext context(String key) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT) throw BizException.of(ErrorCode.FORBIDDEN);
        return new EntitlementContext(user.tenantNo(), user.userNo(), hash(key), "0".repeat(64));
    }
    private void requireStore(String store, String permission) {
        var ctx = BizContext.current(); var allowed = ctx.allowedStoresOrAll();
        if (allowed != null && !allowed.contains(store)) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!ctx.withStore(store).can(permission)) throw BizException.of(ErrorCode.FORBIDDEN);
    }
    private void service(SourceRef source) {
        if (!"CORE".equals(source.sourceSystem()) || !"SERVICE_USE".equals(source.sourceType())) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 required", impossible); }
    }
    public record PreviewRequest(String customerRef, String code, String instanceNo, String orderRef, String storeNo,
                                  LocalDate serviceDate, Integer units, List<ServiceOrderCreate.Line> items) {
        public PreviewRequest {
            ContractChecks.id(storeNo); ContractChecks.optionalId(orderRef); ContractChecks.optionalId(instanceNo);
            int paths = (customerRef == null ? 0 : 1) + (code == null ? 0 : 1) + (orderRef == null ? 0 : 1);
            ContractChecks.require(paths == 1 && (code != null || instanceNo != null));
            ContractChecks.require(code == null || code.matches("ec1_[A-Za-z0-9_-]{43}"));
            ContractChecks.require(units == null || units > 0);
            items = items == null ? List.of() : List.copyOf(items);
            ContractChecks.require(orderRef != null || !items.isEmpty());
        }
        @Override public String toString() { return "PreviewRequest[identity=REDACTED, storeNo=" + storeNo + "]"; }
    }
    public record SubmitRequest(String previewRef, SourceRef sourceRef, String authorizationRef,
                                 long expectedAssetRevision) {
        public SubmitRequest {
            ContractChecks.id(previewRef);
            ContractChecks.present(sourceRef);
            ContractChecks.optionalId(authorizationRef);
            ContractChecks.require(expectedAssetRevision >= 0);
        }
    }
    public record CancelRequest(String reason) {
        public CancelRequest { ContractChecks.require(reason != null && !reason.isBlank() && reason.length() <= 100); }
    }
}
