package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.invbridge.OfflineSaleService;
import ai.neargo.shop.invbridge.StockSyncAppService;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 商家端 · 线下卖出（TDD-商品纳入进销存开关 §5.2，第三期）。
 *
 * <p>记一笔、看当天、撤一笔。权限用 {@code biz:stock} —— 柜台卖出是店员每天在做的事，
 * 要店主权限的话，店里就没人记得上账。
 */
@Profile("api")
@ConditionalOnInventory
@RestController
public class BizOfflineSaleController {

    private final OfflineSaleService service;
    private final StockSyncAppService stores;

    public BizOfflineSaleController(OfflineSaleService service, StockSyncAppService stores) {
        this.service = service;
        this.stores = stores;
    }

    private String entity(String storeNo) {
        String entityNo = BizContext.requireMerchantNo();
        stores.requireStore(entityNo, storeNo);
        return entityNo;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/store/{storeNo}/offline-sale")
    public List<OfflineSaleService.SaleRow> list(@PathVariable String storeNo,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.list(entity(storeNo), storeNo, date);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/store/{storeNo}/offline-sale")
    public SaleResult sell(@PathVariable String storeNo, @RequestBody SaleReq req) {
        return new SaleResult(service.sell(entity(storeNo), storeNo, req.lines(), req.remark(),
                SecurityUtils.currentUserNo()));
    }

    /** 撤销：开一张退回入库单，原单留着 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/store/{storeNo}/offline-sale/{docNo}/revoke")
    public SaleResult revoke(@PathVariable String storeNo, @PathVariable String docNo) {
        return new SaleResult(service.revoke(entity(storeNo), storeNo, docNo, SecurityUtils.currentUserNo()));
    }

    public record SaleReq(List<OfflineSaleService.SaleLine> lines, String remark) {
    }

    /** @param docNo 新开的那张单：卖出是出库单号，撤销是退回入库单号 */
    public record SaleResult(String docNo) {
    }
}
