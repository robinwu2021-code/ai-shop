package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.ActivityBenefitCoordinator;
import ai.neargo.shop.paybridge.ActivityBenefitCoordinator.ClaimView;
import ai.neargo.shop.promotion.dto.BenefitClaimCommand;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import ai.neargo.shop.paybridge.dto.EntitlementServiceViews;
import ai.neargo.shop.paybridge.dto.EntitlementServiceViews.ServiceView;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetState;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AllowanceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.EntitlementItem;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.EntitlementDetail;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageDetail;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AllowanceView;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.ActivationQuote;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.payclient.EntitlementCodeAppService;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeRequest;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeView;
import ai.neargo.shop.common.CursorPage;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Claim identity always comes from the consumer session, never an arbitrary request recipient. */
@Profile("api")
@RestController
public class MpEntitlementController {
    private final ActivityBenefitCoordinator benefits;
    private final EntitlementQueryPort assets;
    private final EntitlementCodeAppService codes;
    private final EntitlementServiceOrderService serviceOrders;
    public MpEntitlementController(ActivityBenefitCoordinator benefits, EntitlementQueryPort assets,
                                   EntitlementCodeAppService codes, EntitlementServiceOrderService serviceOrders) {
        this.benefits = benefits; this.assets = assets; this.codes = codes; this.serviceOrders = serviceOrders;
    }
    @PostMapping("/mp/entitlement-claims")
    public ClaimReceipt claim(@RequestBody BenefitClaimCommand command, @RequestHeader("Idempotency-Key") String key) {
        return receipt(benefits.claim(context(key, command.toString()), command, key));
    }
    @GetMapping("/mp/entitlement-claims/{intentNo}")
    public ClaimReceipt query(@PathVariable String intentNo) {
        return receipt(benefits.query(context("query-claim", intentNo), intentNo));
    }
    @GetMapping("/mp/entitlements")
    public CursorPage<EntitlementItem> mine(@RequestParam(required = false) String entityNo,
            @RequestParam(required = false) AssetKind kind, @RequestParam(required = false) AssetState status,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
        var ctx = context("asset-list", "asset-list");
        return assets.mine(ctx, new AssetQuery(ctx.actorNo(), entityNo, kind, status, cursor, size));
    }
    @GetMapping("/mp/entitlements/{instanceNo}")
    public EntitlementDetail detail(@PathVariable String instanceNo, @RequestParam(required = false) String entityNo) {
        var ctx = context("asset-detail", instanceNo);
        return assets.detail(ctx, new DetailQuery(ctx.actorNo(), entityNo, instanceNo, null));
    }
    @GetMapping("/mp/entitlement-usage/{usageNo}")
    public UsageDetail usage(@PathVariable String usageNo, @RequestParam(required = false) String entityNo) {
        var ctx = context("usage-detail", usageNo);
        return assets.usage(ctx, new UsageQuery(ctx.actorNo(), entityNo, usageNo));
    }
    @GetMapping("/mp/entitlements/{instanceNo}/allowances")
    public AllowanceView allowances(@PathVariable String instanceNo,
            @RequestParam(required = false) String entityNo, @RequestParam(required = false) LocalDate serviceDate) {
        var ctx = context("asset-allowance", instanceNo);
        return assets.allowances(ctx, new AllowanceQuery(ctx.actorNo(), entityNo, instanceNo, serviceDate));
    }
    @PostMapping("/mp/entitlements/{instanceNo}/activation-quotes")
    public ActivationQuote activation(@PathVariable String instanceNo,
            @RequestParam(required = false) String entityNo) {
        var ctx = context("asset-activation-preview", instanceNo);
        return assets.activationQuote(ctx, new DetailQuery(ctx.actorNo(), entityNo, instanceNo, null));
    }
    @PostMapping("/mp/entitlements/{instanceNo}/redeem-codes")
    public CodeView code(@PathVariable String instanceNo, @RequestBody CodeRequest request,
                         @RequestHeader("Idempotency-Key") String key) {
        var ctx = context(key, instanceNo);
        return codes.issue(ctx, ctx.actorNo(), instanceNo, request, key);
    }
    @GetMapping("/mp/entitlement-service-orders")
    public List<ServiceView> pendingServices(@RequestParam(defaultValue = "20") int size) {
        var ctx = context("pending-services", "pending-services");
        return serviceOrders.pending(ctx.tenantNo(), ctx.actorNo(), size).stream()
                .map(EntitlementServiceViews::consumer).toList();
    }
    @GetMapping("/mp/entitlement-service-orders/{serviceOrderNo}")
    public ServiceView service(@PathVariable String serviceOrderNo) {
        var ctx = context("service-detail", serviceOrderNo);
        return EntitlementServiceViews.consumer(serviceOrders.mine(ctx.tenantNo(), ctx.actorNo(), serviceOrderNo));
    }
    private EntitlementContext context(String request, String payload) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.CONSUMER) throw BizException.of(ErrorCode.FORBIDDEN);
        // Context IDs are bounded independently from the public idempotency key (up to 128 characters).
        return new EntitlementContext(user.tenantNo(), user.userNo(),
                ActivityCommandDeduplicator.hash(request), ActivityCommandDeduplicator.hash(payload));
    }
    private ClaimReceipt receipt(ClaimView view) {
        return new ClaimReceipt(view.intentNo(), view.status(), view.grants().stream()
                .flatMap(g -> g.instanceNos().stream()).toList());
    }
    public record ClaimReceipt(String intentNo, String status, List<String> instanceNos) {
        public ClaimReceipt { instanceNos = List.copyOf(instanceNos); }
    }
}
