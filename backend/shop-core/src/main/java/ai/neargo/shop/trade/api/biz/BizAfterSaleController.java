package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.MerchantOrderService;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.common.PageData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端售后（[API 清单 §3.7]）。
 *
 * <p>**极速退的单商家只能看，不能拒**（矩阵 6.2）—— 那是平台的规则决定的退款，
 * 拒绝入口交给状态机挡，而不是在这里判身份：判身份的写法迟早会漏掉某个入口。
 */
@Profile("api")
@RestController
@Validated
public class BizAfterSaleController {

    private final AfterSaleService afterSaleService;
    private final MerchantOrderService merchantOrderService;

    public BizAfterSaleController(AfterSaleService afterSaleService, MerchantOrderService merchantOrderService) {
        this.afterSaleService = afterSaleService;
        this.merchantOrderService = merchantOrderService;
    }

    public record WorkRow(AfterSaleVO afterSale, OrderVO order) { }

    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @GetMapping("/biz/after-sale/worklist")
    public PageData<WorkRow> worklist(@RequestParam(defaultValue = "true") boolean pending,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size) {
        var ctx = BizContext.current();
        long boundedPage = Math.max(1, page);
        long boundedSize = Math.max(1, Math.min(size, 50));
        PageData<AfterSaleVO> result = afterSaleService.merchantPage(ctx.requireMerchantNo(),
                ctx.currentStoreNo(), pending, boundedPage, boundedSize);
        return PageData.of(result.records().stream()
                .map(as -> new WorkRow(as, merchantOrderService.detail(ctx.requireMerchantNo(),
                        ctx.currentStoreNo(), as.subOrderNo())))
                .toList(), result.total(), result.page(), result.size());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @GetMapping("/biz/after-sale/{afterSaleNo}")
    public WorkRow detail(@PathVariable String afterSaleNo) {
        var ctx = BizContext.current();
        AfterSaleVO as = afterSaleService.merchantDetail(ctx.requireMerchantNo(), ctx.currentStoreNo(), afterSaleNo);
        return new WorkRow(as, merchantOrderService.detail(ctx.requireMerchantNo(), ctx.currentStoreNo(),
                as.subOrderNo()));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @GetMapping("/biz/after-sale/by-order/{subOrderNo}")
    public AfterSaleVO activeForOrder(@PathVariable String subOrderNo) {
        var ctx = BizContext.current();
        return afterSaleService.merchantActiveForOrder(ctx.requireMerchantNo(), ctx.currentStoreNo(), subOrderNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @GetMapping("/biz/after-sale")
    public List<AfterSaleVO> list(@RequestParam(required = false) String status) {
        // 按人裁到他能动的店：属主 null = 全部（含没有门店号的历史单），店员只有被授权的那几家
        return afterSaleService.merchantList(BizContext.requireMerchantNo(), status,
                BizContext.current().allowedStoresOrAll());
    }

    /**
     * 写动作的属主 + 门店级判权：售后单挂在哪家店，就要这个人被授权到那家店。
     *
     * <p>服务里的 {@code ofMerchant} 只判主体 —— 只管 A 店的店员拿到 B 店的售后单号，
     * 同意 / 驳回 / 收货 / 确认退款照样能做，而读接口（详情、待办）早就按门店裁了，
     * 于是出现了「看不到、却改得了」。没有门店号的历史单只有属主能动（与 {@code allowedStoresOrAll} 同一口径）。
     */
    private String merchantFor(String afterSaleNo) {
        String merchantNo = BizContext.requireMerchantNo();
        String storeNo = afterSaleService.merchantStoreOf(merchantNo, afterSaleNo);
        if (storeNo != null || !BizContext.current().owner()) {
            BizContext.requireStoreAccess(storeNo);
        }
        return merchantNo;
    }

    /**
     * 同意。**收商家的说明**（可空）。
     *
     * <p>此前这个端点不收 body，而端上一直在发 `{remark}` —— 于是
     * <b>商家同意时写的那句话被丢在半路</b>：C 端订单页的「商家回复」永远是空的，
     * 买家只看到状态变了、不知道商家说了什么。驳回那条一直是收的，
     * 两条路一个收一个不收，看起来像「同意不需要解释」——
     * 而实际最需要解释的正是「同意退货、请寄回」这种。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @PostMapping("/biz/after-sale/{afterSaleNo}/approve")
    public AfterSaleVO approve(@PathVariable String afterSaleNo,
                               @RequestBody(required = false) ApproveReq req) {
        return afterSaleService.approve(merchantFor(afterSaleNo), afterSaleNo,
                req == null ? null : req.remark(),
                req == null ? null : req.refundMinor(),
                req == null ? null : req.refundMode(), SecurityUtils.currentUserNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @PostMapping("/biz/after-sale/{afterSaleNo}/reject")
    public AfterSaleVO reject(@PathVariable String afterSaleNo, @RequestBody @Valid RejectReq req) {
        return afterSaleService.reject(merchantFor(afterSaleNo), afterSaleNo, req.remark());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @PostMapping("/biz/after-sale/{afterSaleNo}/receive")
    public AfterSaleVO confirmReturn(@PathVariable String afterSaleNo,
                                      @RequestBody(required = false) ReceiveReq request) {
        String merchantNo = merchantFor(afterSaleNo);
        if (request != null && request.lines() != null) {
            return afterSaleService.confirmReturn(merchantNo, afterSaleNo,
                    request.receiptNo(), request.lines().stream().map(line ->
                            new AfterSaleService.ReturnReceiveLine(line.itemId(), line.receivedQty(), line.quality()))
                            .toList(),
                    SecurityUtils.currentUserNo());
        }
        return afterSaleService.confirmReturn(merchantNo, afterSaleNo,
                request != null && Boolean.TRUE.equals(request.restockApproved()), SecurityUtils.currentUserNo());
    }

    /** 在途仅退款：签收后（或商家判断不必等）由商家确认退款。系统不再自动退（TDD-订单状态机 AC8） */
    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @PostMapping("/biz/after-sale/{afterSaleNo}/confirm-refund")
    public AfterSaleVO confirmRefund(@PathVariable String afterSaleNo,
                                     @RequestBody(required = false) ConfirmRefundReq req) {
        return afterSaleService.confirmRefund(merchantFor(afterSaleNo), afterSaleNo,
                req == null ? null : req.reason(), SecurityUtils.currentUserNo());
    }

    public record ConfirmRefundReq(String reason) { }

    public record ReceiveReq(Boolean restockApproved, String receiptNo, List<ReceiveLineReq> lines) { }

    public record ReceiveLineReq(Long itemId, Integer receivedQty, String quality) { }

    public record RejectReq(@NotBlank String remark) {
    }

    /**
     * @param remark      同意时的说明，可空 —— 驳回必须给理由，同意不强制
     * @param refundMinor 商家核定的退款额(分)，可空。装箱类型防 Jackson 3 基本类型 400；仅已签收可传（AC9）
     * @param refundMode  退款形式，可空，仅 CASH；原积分补偿模式已停止受理
     */
    public record ApproveReq(String remark, Long refundMinor, String refundMode) {
    }
}
