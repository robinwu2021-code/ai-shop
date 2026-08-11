package ai.neargo.shop.settle.api.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.dto.PurchaseInvoiceVO;
import ai.neargo.shop.settle.dto.RateCardVO;
import ai.neargo.shop.settle.dto.SettleBillVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端结算（[API 清单 §3.10]）。**一期只读**：提现在 P1。
 *
 * <p>金额三列都给出去（基数/佣金/实得）—— 商家要能自己核对，
 * 只给一个「实得」会让每一次结算都变成一次客服对话。
 */
@Profile("api")
@RestController
public class BizSettleController {

    private final SettleService settleService;

    public BizSettleController(SettleService settleService) {
        this.settleService = settleService;
    }

    /**
     * 结算流水。作用域与订单页同一套惯例：默认当前门店，{@code allStores=true} 才看全部。
     *
     * <p>「全部」对老板和店员不是一回事 —— 老板的全部是主体名下所有店，
     * 店员的全部只是他被授权的那几家。这里跟订单页用<b>同一个</b>
     * {@code allowedStoresOrAll()}，不另写一套：钱的作用域比订单更不能出错，
     * 而两套实现迟早有一套忘了跟上授权模型的变化。
     *
     * <p>存量流水没有 {@code store_no}，按当前门店筛会把它们全部滤掉 ——
     * 所以只在<b>真的有多家店</b>时才收窄，单店商家永远看到全部（与今天逐字一致）。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/biz/settle/bills")
    public List<SettleBillVO> bills(
            @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean allStores) {
        var ctx = BizContext.current();
        java.util.Collection<String> scope = Boolean.TRUE.equals(allStores)
                ? ctx.allowedStoresOrAll()
                : java.util.List.of(ctx.currentStoreNo() == null ? "" : ctx.currentStoreNo());
        return settleService.merchantBills(BizContext.requireMerchantNo(), scope);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/biz/settle/bills/{settleNo}")
    public SettleBillVO bill(@PathVariable String settleNo) {
        return settleService.merchantBill(BizContext.requireMerchantNo(), settleNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/biz/settle/rate-card")
    public RateCardVO rateCard() {
        return settleService.rateCard();
    }
    // ---------------------------------------------------------------- 进项票（自营，P0-10）

    /**
     * 平台的开票信息。供应商照着它开票——**开错抬头要退回重开，一来一回半个月**，
     * 所以不能靠口头传递，要在页面上能一键复制。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/biz/settle/invoice-title")
    public java.util.Map<String, String> invoiceTitle() {
        return settleService.platformInvoiceTitle();
    }

    /**
     * 提交进项票。一张票覆盖该周期**全部已对账待开票**的单，
     * 金额必须等于这批单的应付合计。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @PostMapping("/biz/settle/invoices")
    public PurchaseInvoiceVO submitInvoice(@RequestBody SubmitInvoiceReq req) {
        return settleService.submitInvoice(BizContext.requireMerchantNo(),
                new SettleService.SubmitInvoiceCommand(req.period(), req.invoiceCode(),
                        req.invoiceNumber(), req.invoiceType(), req.titleName(), req.titleTaxNo(),
                        req.amountMinor() == null ? 0L : req.amountMinor(),
                        req.taxAmountMinor() == null ? 0L : req.taxAmountMinor(),
                        req.taxRate() == null ? 0 : req.taxRate(),
                        req.invoiceDate(), req.imageUrl()));
    }

    /** 我提交过的票，含驳回原因 —— 被驳回时供应商要知道该改什么。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/biz/settle/invoices")
    public List<PurchaseInvoiceVO> myInvoices() {
        return settleService.myInvoices(BizContext.requireMerchantNo());
    }

    /**
     * 入参一律用**包装类型**，不用 long/int。
     *
     * <p>原始类型在端上漏传该字段时会让 Jackson 直接抛
     * 「Cannot map null into type long」——返回的是 10500「系统开小差」，
     * 而真正的原因是「税额没填」。用包装类型 + 显式默认，
     * 错误就能落在业务校验里，商家看到的是「请填写金额」而不是系统故障。
     */
    public record SubmitInvoiceReq(String period, String invoiceCode, String invoiceNumber,
                                   String invoiceType, String titleName, String titleTaxNo,
                                   Long amountMinor, Long taxAmountMinor, Integer taxRate,
                                   Long invoiceDate, String imageUrl) {
    }

}
