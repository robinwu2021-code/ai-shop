package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.invbridge.StockSyncAppService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · 门店库存同步（TDD-商品纳入进销存开关 §18）：开关、期初对齐、线上可售规则。
 *
 * <p>读用 {@code biz:stock}；改用 {@code biz:store:admin} —— 打开同步、以商城为准调实存、
 * 定线上放多少货，都是店主级的经营决定，店员不该一键改掉。
 */
@Profile("api")
@ConditionalOnInventory
@RestController
public class BizStockSyncController {

    private final StockSyncAppService service;

    public BizStockSyncController(StockSyncAppService service) {
        this.service = service;
    }

    private String entity(String storeNo) {
        String entityNo = BizContext.requireMerchantNo();
        service.requireStore(entityNo, storeNo);
        return entityNo;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/stores/{storeNo}/stock-sync")
    public StockSyncAppService.SyncState state(@PathVariable String storeNo) {
        entity(storeNo);
        return service.state(storeNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PutMapping("/biz/stores/{storeNo}/stock-sync")
    public StockSyncAppService.SyncState setEnabled(@PathVariable String storeNo, @RequestBody EnabledReq req) {
        return service.setEnabled(entity(storeNo), storeNo, Boolean.TRUE.equals(req.enabled()),
                SecurityUtils.currentUserNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/stores/{storeNo}/stock-alignment")
    public List<StockSyncAppService.AlignRow> alignment(@PathVariable String storeNo) {
        return service.alignment(entity(storeNo), storeNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/stores/{storeNo}/stock-alignment/confirm")
    public AlignResult confirm(@PathVariable String storeNo, @RequestBody AlignReq req) {
        return new AlignResult(service.confirmAlignment(entity(storeNo), storeNo, req.mode(),
                SecurityUtils.currentUserNo()));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/stores/{storeNo}/sell-rules")
    public List<StockSyncAppService.RuleRow> rules(@PathVariable String storeNo) {
        entity(storeNo);
        return service.rules(storeNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PutMapping("/biz/stores/{storeNo}/sell-rules")
    public StockSyncAppService.RuleRow saveRule(@PathVariable String storeNo, @RequestBody RuleReq req) {
        return service.saveRule(entity(storeNo), storeNo, req.scopeType(), req.scopeRef(), req.ruleType(),
                req.param() == null ? 0 : req.param(), SecurityUtils.currentUserNo());
    }

    public record EnabledReq(Boolean enabled) {
    }

    public record AlignReq(String mode) {
    }

    /** @param adjusted 以商城为准时调了几行实存 */
    public record AlignResult(int adjusted) {
    }

    public record RuleReq(String scopeType, String scopeRef, String ruleType, Integer param) {
    }
}
