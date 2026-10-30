package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.DispatchService;
import ai.neargo.inventory.domain.service.DispatchService.Claim;
import ai.neargo.inventory.domain.service.DispatchService.Filter;
import ai.neargo.inventory.domain.service.DispatchService.View;
import ai.neargo.inventory.guard.InventoryRequestContext;
import ai.neargo.inventory.svc.api.InboundController.DocNo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 出库池（{@code DISPATCH_POOL} 能力包，TDD §2.9、§2.13）。
 *
 * <p>查询支持多值过滤，宿主按登录人的数据范围把允许的商家号 / 接收人号放进来即可。
 */
@RestController
public class DispatchController {

    private final DispatchService dispatch;
    private final BusinessProfileService profiles;
    private final CommandService commands;

    public DispatchController(DispatchService dispatch, BusinessProfileService profiles, CommandService commands) {
        this.dispatch = dispatch;
        this.profiles = profiles;
        this.commands = commands;
    }

    @GetMapping("/api/inventory/v1/dispatch-pool")
    public List<View> query(@RequestParam(required = false) List<String> status,
                            @RequestParam(required = false) List<String> rootTargetRef,
                            @RequestParam(required = false) String targetType,
                            @RequestParam(required = false) List<String> targetRef,
                            @RequestParam(required = false) String outboundNo,
                            @RequestParam(required = false) String serialNo,
                            @RequestParam(required = false) String itemId) {
        String owner = enabled();
        return dispatch.query(owner, new Filter(status, rootTargetRef, targetType, targetRef, outboundNo, serialNo,
                itemId));
    }

    @PostMapping("/api/inventory/v1/dispatch-pool/{dispatchId}/redirect")
    public View redirect(@PathVariable String dispatchId, @RequestBody Redirect req) {
        enabled();
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "dispatch.redirect", new InboundController.Keyed(dispatchId, req), View.class,
                () -> dispatch.redirect(caller, dispatchId, req.targets(), req.reason()));
    }

    @PostMapping("/api/inventory/v1/dispatch-pool/{dispatchId}/revoke")
    public DocNo revoke(@PathVariable String dispatchId, @RequestBody Reason req) {
        enabled();
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "dispatch.revoke", new InboundController.Keyed(dispatchId, req), DocNo.class,
                () -> new DocNo(dispatch.revoke(caller, dispatchId, req.reason())));
    }

    @PostMapping("/api/inventory/v1/dispatch-pool/claims")
    public Claim claim(@RequestBody ClaimRequest req) {
        enabled();
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "dispatch.claim", req, Claim.class,
                () -> dispatch.claim(caller, req.dispatchIds(), req.businessRef()));
    }

    @GetMapping("/api/inventory/v1/dispatch-pool/claims/by-request/{requestId}")
    public Claim claimByRequest(@PathVariable String requestId) {
        enabled();
        return dispatch.claimByRequest(InventoryRequestContext.caller(), requestId);
    }

    @PostMapping("/api/inventory/v1/dispatch-pool/claims/{claimNo}/release")
    public DocNo release(@PathVariable String claimNo) {
        enabled();
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "dispatch.release", claimNo, DocNo.class, () -> {
            dispatch.release(caller, claimNo);
            return new DocNo(claimNo);
        });
    }

    private String enabled() {
        String owner = InventoryRequestContext.principal().ownerId();
        profiles.requireCapability(owner, Capability.DISPATCH_POOL);
        return owner;
    }

    public record Redirect(Map<String, String> targets, String reason) {
    }

    public record Reason(String reason) {
    }

    public record ClaimRequest(List<String> dispatchIds, String businessRef) {
    }
}
