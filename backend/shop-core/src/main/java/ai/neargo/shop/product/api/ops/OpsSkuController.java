package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.OpsSkuDetailVO;
import ai.neargo.shop.product.service.PlatformProductService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 库存与预售（矩阵 P-3.3）+ SKU 粒度的审核与压下架。
 *
 * <p>ops-web 早就声明了这五个端点、写好了页面与 mock，后端一直缺 ——
 * 于是「库存与预售」tab 在真实环境里是五个 404，而 mock 上一切正常。
 * 方案见 {@code docs/technical/design/TDD-运营端商品治理补齐.md}。
 *
 * <p><b>与 {@link OpsGoodsController} 的分工是粒度</b>：那边一行一件商品
 * （审核、撤销过审都是商品级的事），这边一行一个规格 ——
 * 预售额度、截单时间、已售数天然是 SKU 的属性（同一件商品的三个规格
 * 完全可以有三套预售配置）。
 */
@Profile("ops")
@RestController
@Validated
public class OpsSkuController {

    private final PlatformProductService platformProductService;
    private final AuditLogPort auditLogPort;

    public OpsSkuController(PlatformProductService platformProductService, AuditLogPort auditLogPort) {
        this.platformProductService = platformProductService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * SKU 粒度全量查询。过滤维度与商品池一致（都挂在父商品上）。
     *
     * @param presaleOnly 只看开了预售的。「库存与预售」tab 用它 ——
     *                    交给前端拉一页再自己过滤的话，真实库里预售 SKU 大概率不在第一页，
     *                    那个 tab 会长期显示为空而接口 200
     */
    @GetMapping("/ops/skus")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    public PageData<OpsSkuDetailVO> list(@RequestParam(required = false) String merchantNo,
                                         @RequestParam(required = false) String categoryNo,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "false") boolean presaleOnly,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return platformProductService.listSkus(merchantNo, categoryNo, keyword, status,
                presaleOnly, page, size);
    }

    /**
     * 超卖告警（P-3.3.3）：已售 > 预售额度。
     *
     * <p><b>只读，不带处置动作</b> —— 补货还是退单要人判断，
     * 自动关单会把还能补上的那些也关掉，而那批订单已经收了钱。
     */
    @GetMapping("/ops/skus/oversell")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    public List<OpsSkuDetailVO> oversell() {
        return platformProductService.listOversellSkus();
    }

    /**
     * 预售额度与截单时间（P-3.3.1 / 3.3.2）。
     *
     * <p>额度不是给人看的数字：现货卖完后下单会回落到它上面继续成交
     * （{@code StockPortImpl.lock()} 的第二级）。所以这里记审计 ——
     * 调额度等于调一批还没采购的货能卖多少。
     */
    @PostMapping("/ops/skus/{skuNo}/presale")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_AUDIT + "')")
    public OpsSkuDetailVO presale(@PathVariable String skuNo, @RequestBody PresaleReq req) {
        var vo = platformProductService.setPresale(skuNo,
                req.presaleQuota() == null ? 0 : req.presaleQuota(), req.cutoffAt(), req.arriveAt());
        auditLogPort.record("SKU_PRESALE", skuNo,
                "额度 " + vo.presaleQuota() + "，截单 " + (vo.cutoffAt() == null ? "不设" : vo.cutoffAt())
                        + (vo.soldCount() > vo.presaleQuota() ? "（已售 " + vo.soldCount() + "，已超卖）" : ""));
        return vo;
    }

    /** SKU 粒度的审核入口：解析到父商品再审（审的是「这件商品能不能卖」）。 */
    @PostMapping("/ops/skus/{skuNo}/audit")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_AUDIT + "')")
    public OpsSkuDetailVO audit(@PathVariable String skuNo, @RequestBody AuditReq req) {
        boolean pass = Boolean.TRUE.equals(req.pass());
        var vo = platformProductService.auditSku(skuNo, pass, req.reason());
        auditLogPort.record("SKU_AUDIT", skuNo, pass ? "通过" : "驳回：" + req.reason());
        return vo;
    }

    /**
     * 平台压下架：主体级下架 + 撤社区池 + 记原因，<b>不撤过审</b> ——
     * 商家处理完自己点一下就能回来，不必走一遍重新提审。
     * 与 {@code POST /ops/goods/{goodsNo}/force-off}（撤销过审）是两件事。
     */
    @PostMapping("/ops/skus/{skuNo}/force-off")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_AUDIT + "')")
    public OpsSkuDetailVO forceOff(@PathVariable String skuNo, @RequestBody ForceOffReq req) {
        var vo = platformProductService.forceOffSku(skuNo, req.reason());
        // 不可逆到买家侧的处置，critical 留痕（P-1 审计是平台权力的制衡）
        auditLogPort.record("SKU_FORCE_OFF", skuNo, req.reason(), true);
        return vo;
    }

    /**
     * @param cutoffAt ISO 串。空 = 不设截单，只靠额度封顶
     * @param arriveAt ISO 串。**空 = 不改**（保留原值）—— 与「清空到货时间」分开：
     *                 一次只改额度的提交若顺手清了它，「截单必须早于到货」下一次就形同虚设
     */
    public record PresaleReq(Integer presaleQuota, String cutoffAt, String arriveAt) {
    }

    /** @param reason 驳回**必填**。商家拿不到理由就只能反复重提，而每次重提都占一次审核人力 */
    public record AuditReq(Boolean pass, String reason) {
    }

    /** @param reason 必填。原样进商家 B 端 —— 没有事实的处置在申诉时站不住 */
    public record ForceOffReq(String reason) {
    }
}
