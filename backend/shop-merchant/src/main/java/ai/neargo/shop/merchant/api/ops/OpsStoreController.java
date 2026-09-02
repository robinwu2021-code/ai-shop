package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 门店档案（P-11.2.1，TDD-运营端门店与商品治理）。
 *
 * <p><b>只读为主</b>：门店资料、价格、库存运营一律不改 —— 平台的边界是
 * 「裁、定、兜」，不替商家运营。仅有的写动作是解除强制下线（{@link #restore}），
 * 压下那一侧走违规处置（{@code POST /ops/merchants/{merchantNo}/violations}，
 * {@code action=STORE_OFFLINE}）—— 处置动作与留痕必须是同一次提交。
 *
 * <p>门店的**经营数据**不在这里：{@code MerchantOrderService} 在 trade 域
 * （兄弟模块够不着），见 {@code OpsStoreStatsController}，前端合并展示。
 */
@Profile("ops")
@RestController
@Validated
public class OpsStoreController {

    private final MerchantGovernService governService;
    private final AuditLogPort auditLogPort;
    private final ai.neargo.shop.merchant.service.StoreFulfillmentService fulfillmentService;

    public OpsStoreController(MerchantGovernService governService, AuditLogPort auditLogPort,
                              ai.neargo.shop.merchant.service.StoreFulfillmentService fulfillmentService) {
        this.governService = governService;
        this.auditLogPort = auditLogPort;
        this.fulfillmentService = fulfillmentService;
    }

    /**
     * 商家履约配置（方案 v4，P0 只读）：门店 × 送货方式矩阵。
     * 运营接到履约投诉时的第一入口 —— 先看这家店到底开了哪几路，再谈处置。
     */
    @GetMapping("/ops/merchants/{merchantNo}/fulfillment")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public java.util.List<ai.neargo.shop.merchant.service.StoreFulfillmentService.StoreFulfillmentVO> fulfillment(
            @PathVariable String merchantNo) {
        return fulfillmentService.byMerchant(merchantNo);
    }

    /**
     * 跨主体门店检索。带 {@code merchantNo} 就是「该主体的全部门店」——
     * 商家详情抽屉与独立检索页共用这一条。<b>含停用的</b>：治理视角更不能看不见。
     */
    /**
     * 锁路 / 解锁（P2）。**用锁不用删**：商家配置原样保留，处置结束一键恢复。
     * 锁着时买家侧不可选、商家侧置灰不可自行打开。
     */
    @PostMapping("/ops/stores/{storeNo}/channels/{channel}/lock")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_FULFILLMENT_UPDATE + "')")
    public java.util.Map<String, Object> lockChannel(@PathVariable String storeNo, @PathVariable String channel,
                                                     @org.springframework.web.bind.annotation.RequestBody(required = false) LockReq req) {
        fulfillmentService.setLocked(storeNo, channel, true);
        auditLogPort.record("CHANNEL_LOCK", storeNo + ":" + channel,
                req == null || req.reason() == null ? "锁路" : "锁路：" + req.reason(), true);
        return java.util.Map.of("storeNo", storeNo, "channel", channel, "locked", true);
    }

    @PostMapping("/ops/stores/{storeNo}/channels/{channel}/unlock")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_FULFILLMENT_UPDATE + "')")
    public java.util.Map<String, Object> unlockChannel(@PathVariable String storeNo, @PathVariable String channel) {
        fulfillmentService.setLocked(storeNo, channel, false);
        auditLogPort.record("CHANNEL_UNLOCK", storeNo + ":" + channel, "解锁", true);
        return java.util.Map.of("storeNo", storeNo, "channel", channel, "locked", false);
    }

    public record LockReq(String reason) {
    }

    @GetMapping("/ops/stores")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public PageData<MerchantGovernService.StoreGovernVO> stores(
            @RequestParam(required = false) String merchantNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String businessMode,
            @RequestParam(required = false) String communityNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return governService.searchStores(merchantNo, status, businessMode, communityNo, keyword,
                page, Math.min(size, 100));
    }

    /** 门店详情：门面 + 配送规则 + 经营模式 + 收款商户号。 */
    @GetMapping("/ops/stores/{storeNo}")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public MerchantGovernService.StoreGovernVO storeDetail(@PathVariable String storeNo) {
        return governService.storeDetail(storeNo);
    }

    /**
     * 解除门店强制下线（SUSPENDED → ACTIVE），恢复被平台压下的货架行。
     * 商家在处置期间自主下架的商品不动。
     */
    @PostMapping("/ops/stores/{storeNo}/restore")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_BAN + "')")
    public MerchantGovernService.StoreGovernVO restore(@PathVariable String storeNo) {
        var vo = governService.restoreStore(storeNo, SecurityUtils.currentUserNo());
        // 解除与压下同样要留痕：critical —— 它改变一家店能不能卖
        auditLogPort.record("STORE_RESTORE", storeNo, "解除门店强制下线", true);
        return vo;
    }
}
