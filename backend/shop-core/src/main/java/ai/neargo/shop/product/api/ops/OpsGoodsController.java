package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 商品审核（由原 {@code OpsController} 拆出，S7）。
 *
 * <p>审计日志走 {@link AuditLogPort} 而不是直接调 platform 域的 {@code OpsService}：
 * 商品域为了写一行审计而依赖整个平台域，是把「记账本」变成了模块依赖。
 */
@Profile("ops")
@RestController
@Validated
public class OpsGoodsController {

    private final MerchantGoodsService merchantGoodsService;
    private final AuditLogPort auditLogPort;

    public OpsGoodsController(MerchantGoodsService merchantGoodsService, AuditLogPort auditLogPort) {
        this.merchantGoodsService = merchantGoodsService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 商品审核。此前只有队列没有动作 —— 商品录进来就永远停在 AUDITING，
     * 而上架要求过审，于是商家录的商品一件都上不了架。
     */
    @PostMapping("/ops/goods/{goodsNo}/audit")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_AUDIT + "')")
    public GoodsVO auditGoods(@PathVariable String goodsNo, @RequestBody AuditReq req) {
        var vo = merchantGoodsService.audit(goodsNo, Boolean.TRUE.equals(req.approved()), req.reason());
        auditLogPort.record("GOODS_AUDIT", goodsNo,
                (Boolean.TRUE.equals(req.approved()) ? "通过" : "驳回：" + req.reason()));
        return vo;
    }

    @GetMapping("/ops/goods/audit-queue")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    public PageData<GoodsVO> goodsAuditQueue(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        /*
         * **只给待审的**。此前这里走的是公共目录查询且不带任何条件，于是「待审队列」
         * 返回的是**全部商品** —— 审完一件，队列长度纹丝不动，
         * 而运营会以为是没保存成功，反复再审一遍。
         *
         * 走 auditQueue() 而不是 list(null, …, "AUDITING", …)：**两者在数据域上相反**。
         * list 与 B 端商家商品列表共用，必须豁免（B 端会话是 SELF 维度，
         * 而 prd_goods 只有 MERCHANT 锚点，接上就是 1=0）；
         * 而这一条只有运营调，要接 —— 配了商家域的审核员只看自己负责那几家的待审商品。
         */
        return merchantGoodsService.auditQueue(page, size);
    }

    /**
     * 商品池：运营端浏览全平台商品，按商家/类目/关键词筛。与上面的待审队列区分开——
     * 那个是固定 status=AUDITING 的工作队列，这个是给"这个商家/这个类目下有什么商品"
     * 这类日常查询用的，status 留空表示不筛（含在售/下架/待审全部）。
     */
    @GetMapping("/ops/goods")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    public PageData<ai.neargo.shop.product.dto.OpsGoodsListVO> goodsPool(
            @RequestParam(required = false) String merchantNo,
            @RequestParam(required = false) String categoryNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String storeNo,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return merchantGoodsService.listForOps(merchantNo, categoryNo, keyword, status, storeNo, page, size);
    }

    /**
     * 商品详情（P-3.2.2b）。审核抽屉与门店商品投影都从这里取全量：
     * 三语文案、多市场价、SKU 矩阵、驳回原因。
     */
    @GetMapping("/ops/goods/{goodsNo}")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    public GoodsVO goodsDetail(@PathVariable String goodsNo) {
        return merchantGoodsService.detailForOps(goodsNo);
    }

    /**
     * 待审草稿的字段级差异（双版本）。审核开着时线上照卖旧版、详情给的也是旧版 ——
     * 没有这份 diff，审核员批准的是一个自己从没看过的版本。与商家发布确认页
     * **同一份 diff**（服务端同一段代码算的），两边看到的不可能不一致。
     * 无待审草稿返回 null：新建提审等老链路审的是内容本身，那是常态。
     */
    @GetMapping("/ops/goods/{goodsNo}/draft-preview")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    public ai.neargo.shop.product.dto.PublishPreviewVO draftPreview(@PathVariable String goodsNo) {
        return merchantGoodsService.draftPreviewForOps(goodsNo);
    }

    /**
     * 平台强制下架（P-3.2.3）= 撤销过审。原因必填 —— 它会出现在商家 B 端；
     * 商家改后走既有的重新提审链路回来。
     */
    @PostMapping("/ops/goods/{goodsNo}/force-off")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_AUDIT + "')")
    public GoodsVO forceOff(@PathVariable String goodsNo, @RequestBody ForceOffReq req) {
        var vo = merchantGoodsService.forceOff(goodsNo, req.reason());
        // 不可逆的处置动作，critical 留痕（P-1 审计是平台权力的制衡）
        auditLogPort.record("GOODS_FORCE_OFF", goodsNo, req.reason(), true);
        return vo;
    }

    /** 审核请求。商品审核只用到前两个字段，进件审核的另两个字段在平台端那份上。 */
    public record AuditReq(Boolean approved, String reason) {
    }

    /** @param reason 必填。商家看得到它 —— 没有事实的处置在申诉时站不住 */
    public record ForceOffReq(String reason) {
    }
}
