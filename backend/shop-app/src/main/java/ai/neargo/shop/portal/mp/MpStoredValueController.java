package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.payclient.StoredValueRechargeAppService;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ConsumptionQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consumer-facing stored value surface is intentionally read-only and exposes no balance-pool split. */
@Profile("api")
@RestController
public class MpStoredValueController {
    private final StoredValueRechargeAppService storedValues;

    public MpStoredValueController(StoredValueRechargeAppService storedValues) {
        this.storedValues = storedValues;
    }

    @GetMapping("/mp/stored-value/stores")
    public CursorPage<StoreItem> stores(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        var ctx = context("stored-value-stores", cursor == null ? "first" : cursor);
        return storedValues.stores(ctx, new StoreQuery(ctx.actorNo(), cursor, size));
    }

    @GetMapping("/mp/stored-value/consumptions")
    public CursorPage<UsageItem> consumptions(@RequestParam String entityNo, @RequestParam(required=false) String storeNo,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
        var ctx = context("stored-value-consumptions", entityNo + ":" + storeNo + ":" + cursor);
        return storedValues.consumptions(ctx,
                new ConsumptionQuery(ctx.actorNo(), entityNo, storeNo, cursor, size));
    }

    private EntitlementContext context(String request, String payload) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.CONSUMER) throw BizException.of(ErrorCode.FORBIDDEN);
        return new EntitlementContext(user.tenantNo(), user.userNo(), ActivityCommandDeduplicator.hash(request),
                ActivityCommandDeduplicator.hash(payload));
    }
}
