package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.MallException;
import ai.neargo.commerce.api.subscription.*;

import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/ops/stores/{storeNo}/subscription")
public final class CommerceSourceHttpController extends MallHttpSupport {
    private final SourceManagementFacade sources;

    public CommerceSourceHttpController(
            SourceManagementFacade sources,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        super(context, envelope);
        this.sources = sources;
    }

    private void available() {
        if (sources == null) throw new MallException("SOURCE_MANAGEMENT_UNAVAILABLE");
    }

    @GetMapping
    public Object read(
            @PathVariable("storeNo") String store,
            @RequestParam(value = "entitlementCode", required = false) String entitlement) {
        var actor = context.operator("commerce:subscription:read");
        available();
        return ok(
                sources.subscription(
                        actor,
                        new SubscriptionQuery(
                                new StoreRef(actor.tenantNo(), code(store)),
                                Optional.ofNullable(entitlement))));
    }

    public record GrantRequest(
            String operationNo, String entitlementCode, int durationDays, String reason) {}

    @PostMapping("/grants")
    public Object grant(@PathVariable("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, GrantRequest.class);
        var actor = context.operator("commerce:subscription:grant");
        available();
        return ok(
                sources.grant(
                        actor,
                        new GrantCommand(
                                new StoreRef(actor.tenantNo(), code(store)),
                                d.operationNo(),
                                d.entitlementCode(),
                                d.durationDays(),
                                Optional.empty(),
                                d.reason())));
    }

    public record RevokeRequest(String operationNo, String reason, long expectedVersion) {}

    @PostMapping("/sources/{sourceNo}/revoke")
    public Object revoke(
            @PathVariable("storeNo") String store,
            @PathVariable("sourceNo") String source,
            @RequestBody String body) {
        var d = json.read(body, RevokeRequest.class);
        var actor = context.operator("commerce:subscription:revoke");
        available();
        return ok(
                sources.revoke(
                        actor,
                        new RevokeCommand(
                                new StoreRef(actor.tenantNo(), code(store)),
                                code(source),
                                d.operationNo(),
                                d.reason(),
                                d.expectedVersion())));
    }
}
