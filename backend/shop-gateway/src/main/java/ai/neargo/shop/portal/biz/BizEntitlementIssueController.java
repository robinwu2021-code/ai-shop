package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.paybridge.EntitlementScopeResolver;
import ai.neargo.shop.payclient.EntitlementBatchAppService;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A targeted audience spans the merchant; limited-store delegation cannot issue outside its stores. */
@Profile("api")
@RestController
public class BizEntitlementIssueController {
    private final EntitlementBatchAppService batches;
    private final EntitlementScopeResolver scopes;
    public BizEntitlementIssueController(EntitlementBatchAppService batches, EntitlementScopeResolver scopes) {
        this.batches = batches; this.scopes = scopes;
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_ISSUE + "')")
    @PostMapping("/biz/entitlement-issue-batches")
    public BatchView create(@RequestBody CreateBatch request, @RequestHeader("Idempotency-Key") String key) {
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        return batches.create(scope(), request, ActivityCommandDeduplicator.hash(key));
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_READ + "')")
    @GetMapping("/biz/entitlement-issue-batches/{batchNo}")
    public BatchView get(@PathVariable String batchNo) { return batches.get(scope(), batchNo); }
    private CatalogScope scope() {
        if (BizContext.current().allowedStoresOrAll() != null) throw BizException.of(ErrorCode.FORBIDDEN);
        return scopes.resolve(new RedemptionScope("ALL", List.of()));
    }
}
