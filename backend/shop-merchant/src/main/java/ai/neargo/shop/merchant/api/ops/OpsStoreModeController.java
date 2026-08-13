package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.merchant.service.MerchantGovernService.StoreModeVO;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 门店经营模式（自营 / 第三方）。
 *
 * <p>补的是一个<b>「下游已在依赖、上游无人能写」</b>的缺口：
 * {@code mch_store.business_mode} 早已存在，{@code SettleServiceImpl} 每单都读它
 * 决定走哪条结算状态机与开票状态——但在此之前<b>全仓库没有任何一处能写它</b>，
 * 换一家店的经营模式只能手改数据库。这比「没有这个功能」更危险。
 *
 * <p><b>只开在运营端，不开给商家。</b>自营意味着平台是法律上的销售主体、
 * 承担全部产品责任，这个身份不能由商家自己勾选。
 *
 * <p>用 {@code SETTLE_MANAGE} 而非 {@code MERCHANT_AUDIT}：
 * 这个开关决定资金流向与开票责任，与审资质不是同一类权限。
 */
@Profile("ops")
@RestController
@Validated
public class OpsStoreModeController {

    private final MerchantGovernService governService;
    private final AuditLogPort auditLogPort;

    public OpsStoreModeController(MerchantGovernService governService, AuditLogPort auditLogPort) {
        this.governService = governService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/merchants/{merchantNo}/store-modes")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_MODE_READ + "')")
    public List<StoreModeVO> storeModes(@PathVariable String merchantNo) {
        return governService.storeModes(merchantNo);
    }

    /**
     * 无营业执照的主体 × 自营门店 —— <b>税务敞口清单</b>。
     *
     * <p>自营下平台是销售主体，列支成本要进项发票，而无照主体开不出票 ——
     * 这笔支出<b>不得在企业所得税前扣除</b>。而这个组合是
     * {@code business_mode} 默认值 + 全仓无校验的<b>必然结果</b>，不是配置失误。
     *
     * <p><b>本轮只做「看得见」，不做拦截。</b>硬拦会同时打断存量商户与
     * 农产品供应商（农户正是「无照 + 自营采购」这条合规路径）。
     *
     * <p>与 {@link #storeModes} 共用 {@code MERCHANT_MODE_READ}：
     * 看的是同一个字段，只是换了个筛法。
     */
    @GetMapping("/ops/merchants/mode-risk")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_MODE_READ + "')")
    public List<MerchantGovernService.ModeRiskVO> modeRisk() {
        return governService.modeRiskStores();
    }

    /**
     * 设置主体的资金路径。
     *
     * <p>与门店经营模式同一批人在配，所以复用 {@code MERCHANT_MODE_UPDATE} ——
     * 两者都决定钱怎么走，不该分成两套权限。
     *
     * <p>拒的两种情形：取值不在枚举里（400）、无照主体要走归集（409）。
     * 后者的理由不是税负偏高，是**成本不可税前扣除**，走一单亏一单。
     */
    @PutMapping("/ops/merchants/{merchantNo}/funds-mode")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_MODE_UPDATE + "')")
    public MerchantGovernService.MerchantProfileVO setFundsMode(
            @PathVariable String merchantNo, @RequestBody FundsModeReq req) {
        String operator = SecurityUtils.currentUserNo();
        var vo = governService.setFundsMode(merchantNo, req.fundsMode(), operator);
        auditLogPort.record("MERCHANT_FUNDS_MODE", merchantNo, "资金路径改为 " + req.fundsMode());
        return vo;
    }

    /** @param fundsMode {@code AGGREGATED} 归集 / {@code DIRECT} 直连 */
    public record FundsModeReq(String fundsMode) {
    }


    /**
     * <b>只对新单生效。</b>{@code stl_bill.business_mode} 生成时已落快照，
     * 历史账单不会被改动——端上必须把这句话显示出来，
     * 否则运营会以为改了模式能一并修正历史。
     */
    @PutMapping("/ops/stores/{storeNo}/business-mode")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_MODE_UPDATE + "')")
    public StoreModeVO setBusinessMode(@PathVariable String storeNo, @RequestBody ModeReq req) {
        String operator = SecurityUtils.currentUserNo();
        StoreModeVO vo = governService.setBusinessMode(storeNo, req.businessMode(), operator);
        auditLogPort.record("STORE_BUSINESS_MODE", storeNo + ":" + req.businessMode(), operator);
        return vo;
    }

    public record ModeReq(String businessMode) {
    }
}
