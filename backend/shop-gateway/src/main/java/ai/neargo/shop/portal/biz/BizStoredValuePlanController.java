package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.paybridge.EntitlementScopeResolver;
import ai.neargo.shop.payclient.StoredValuePlanAppService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Status;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.View;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@RestController
public class BizStoredValuePlanController {
    private final StoredValuePlanAppService plans;
    private final EntitlementScopeResolver scopes;
    private final MarketPort markets;
    private final MerchantQueryPort merchants;
    public BizStoredValuePlanController(StoredValuePlanAppService plans, EntitlementScopeResolver scopes, MarketPort markets, MerchantQueryPort merchants) {
        this.plans = plans; this.scopes = scopes; this.markets=markets; this.merchants=merchants;
    }

    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_PLAN_READ+"') or @perm.canBiz('"+BizPerms.SV_PLAN_MANAGE+"') or @perm.canBiz('"+BizPerms.SV_ACCOUNT_READ+"') or @perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"')")
    @GetMapping("/biz/stored-value/context")
    public MarketPort.MarketBrief context(@RequestParam String storeNo) {
        var s=scopes.resolve(new RedemptionScope("STORES",List.of(storeNo)));
        var current=BizContext.current().withStore(storeNo);
        if(!current.can(BizPerms.SV_PLAN_READ)&&!current.can(BizPerms.SV_PLAN_MANAGE)
                &&!current.can(BizPerms.SV_ACCOUNT_READ)&&!current.can(BizPerms.SV_RECHARGE_CREATE))throw BizException.of(ErrorCode.FORBIDDEN);
        return markets.find(merchants.marketOf(s.entityNo())).filter(MarketPort.MarketBrief::enabled)
                .orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
    }

    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_PLAN_READ+"') or @perm.canBiz('"+BizPerms.SV_PLAN_MANAGE+"') or @perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"')")
    @GetMapping("/biz/stored-value/plans")
    public CursorPage<View> list(@RequestParam String storeNo, @RequestParam(required=false) String status,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int size) {
        String permission=readPermission(storeNo);
        if(BizPerms.SV_RECHARGE_CREATE.equals(permission)){
            if(status!=null&&!"ACTIVE".equals(status))throw BizException.of(ErrorCode.FORBIDDEN);
            status="ACTIVE";
        }
        return plans.list(scope(storeNo,permission), storeNo, status, cursor, size);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_PLAN_MANAGE + "')")
    @PostMapping("/biz/stored-value/plans")
    public View create(@RequestBody Create c, @RequestHeader("Idempotency-Key") String key) {
        var s=scope(c.storeNo(), BizPerms.SV_PLAN_MANAGE);
        var market=markets.find(merchants.marketOf(s.entityNo())).filter(MarketPort.MarketBrief::enabled)
                .orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        if(!market.currency().equals(c.currency()))throw BizException.of(ErrorCode.BAD_REQUEST);
        return plans.create(s, c, key);
    }

    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_PLAN_READ+"') or @perm.canBiz('"+BizPerms.SV_PLAN_MANAGE+"') or @perm.canBiz('"+BizPerms.SV_RECHARGE_CREATE+"')")
    @GetMapping("/biz/stored-value/plans/{planNo}")
    public View get(@PathVariable String planNo, @RequestParam String storeNo) {
        String permission=readPermission(storeNo);var result=plans.get(scope(storeNo,permission),storeNo,planNo);
        if(BizPerms.SV_RECHARGE_CREATE.equals(permission)&&!"ACTIVE".equals(result.status()))throw BizException.of(ErrorCode.NOT_FOUND);
        return result;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.SV_PLAN_MANAGE + "')")
    @PutMapping("/biz/stored-value/plans/{planNo}/status")
    public View status(@PathVariable String planNo, @RequestBody Status c,
            @RequestHeader("Idempotency-Key") String key) {
        return plans.status(scope(c.storeNo(), BizPerms.SV_PLAN_MANAGE), planNo, c, key);
    }

    private static String readPermission(String store){
        var current=BizContext.current().withStore(store);
        if(current.can(BizPerms.SV_PLAN_READ))return BizPerms.SV_PLAN_READ;
        if(current.can(BizPerms.SV_PLAN_MANAGE))return BizPerms.SV_PLAN_MANAGE;
        return BizPerms.SV_RECHARGE_CREATE;
    }
    private CatalogScope scope(String store, String permission) {
        var scope = scopes.resolve(new RedemptionScope("STORES", List.of(store)));
        if (!BizContext.current().withStore(store).can(permission)) throw BizException.of(ErrorCode.FORBIDDEN);
        return scope;
    }
}
