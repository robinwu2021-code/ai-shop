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
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 商品审核（由原 {@code OpsController} 拆出，S7）。
 *
 * <p>审计日志走 {@link AuditLogPort} 而不是直接调 platform 域的 {@code OpsService}：
 * 商品域为了写一行审计而依赖整个平台域，是把「记账本」变成了模块依赖。
 */
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
    @PreAuthorize("@perm.can('" + Perms.GOODS_AUDIT + "')")
    public GoodsVO auditGoods(@PathVariable String goodsNo, @RequestBody AuditReq req) {
        var vo = merchantGoodsService.audit(goodsNo, Boolean.TRUE.equals(req.approved()), req.reason());
        auditLogPort.record("GOODS_AUDIT", goodsNo,
                (Boolean.TRUE.equals(req.approved()) ? "通过" : "驳回：" + req.reason()));
        return vo;
    }

    @GetMapping("/ops/goods/audit-queue")
    @PreAuthorize("@perm.can('" + Perms.GOODS_AUDIT + "')")
    public PageData<GoodsVO> goodsAuditQueue(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        /*
         * **只给待审的**。此前这里走的是公共目录查询且不带任何条件，于是「待审队列」
         * 返回的是**全部商品** —— 审完一件，队列长度纹丝不动，
         * 而运营会以为是没保存成功，反复再审一遍。
         *
         * merchantNo 传 null = 跨商家查，这正是平台审核要的口径。
         */
        return merchantGoodsService.list(null, "AUDITING", page, size);
    }

    /** 审核请求。商品审核只用到前两个字段，进件审核的另两个字段在平台端那份上。 */
    public record AuditReq(Boolean approved, String reason) {
    }
}
