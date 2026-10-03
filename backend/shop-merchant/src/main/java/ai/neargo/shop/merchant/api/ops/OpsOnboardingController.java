package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.dto.PaymentApplymentVO;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.merchant.service.MerchantPaymentService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 进件（收款开户）看板。
 *
 * <p><b>只读为主 + 一个人工回查</b>。它填的是一个真实盲区：入驻审核通过 = 能上架卖货，
 * 收款进件通过 = 能收钱，两者是两条链。审核过了但进件没走完的商家，货照上、单照来，
 * 就是<b>钱收不到</b>，而今天进件状态只有商家自己点「刷新」才推进（无回调、无触达）。
 * 运营在这里一眼看清「谁卡在收款上」，并能替他点一次回查。
 *
 * <p><b>刻意不碰通道</b>：看板读 {@code mch_payment_merchant}，回查转调已有的
 * {@code MerchantPaymentService.refresh}（它内部才去问通道）。通道接通与否
 * （另属支付方案会话）都不影响本看板 —— stub 下照样显示 APPLYING/REJECTED/ACTIVE。
 *
 * <p>权限复用 {@code merchant:admission:*}：进件与「准入与保证金」是同一拨人在管
 * （都决定这家店能不能真正把生意做成），不新增权限码。
 */
@Profile("ops")
@RestController
@Validated
public class OpsOnboardingController {

    private final MerchantGovernService governService;
    private final MerchantPaymentService paymentService;
    private final AuditLogPort auditLogPort;

    public OpsOnboardingController(MerchantGovernService governService,
                                   MerchantPaymentService paymentService,
                                   AuditLogPort auditLogPort) {
        this.governService = governService;
        this.paymentService = paymentService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 进件看板：跨商家、跨通道，按状态/通道/关键词筛。
     *
     * @param status     APPLYING / REJECTED / ACTIVE / NONE / FROZEN；空=全部。支持逗号分隔多态
     * @param payChannel WECHAT / ALIPAY；空=全部
     * @param keyword    店名 / 主体号 / 收款号 / 子商户号
     */
    @GetMapping("/ops/onboarding")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_ADMISSION_READ + "')")
    public PageData<MerchantGovernService.OnboardingRowVO> board(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String payChannel,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return governService.onboardingBoard(status, payChannel, keyword, page, Math.min(size, 100));
    }

    /**
     * 人工回查：替卡住的商家去通道问一次结果并落库。
     *
     * <p>不传 {@code storeNo}（或传空串）= 主体级默认收款号。回查是权威动作，留痕。
     */
    @PostMapping("/ops/onboarding/refresh")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_ADMISSION_UPDATE + "')")
    public PaymentApplymentVO refresh(@RequestBody RefreshReq req) {
        String storeNo = req.storeNo() == null ? "" : req.storeNo();
        PaymentApplymentVO vo = paymentService.refresh(req.merchantNo(), req.payChannel(), storeNo);
        auditLogPort.record("ONBOARDING_REFRESH", req.merchantNo() + ":" + req.payChannel(),
                "运营人工回查" + (storeNo.isBlank() ? "（主体级）" : "（门店 " + storeNo + "）"));
        return vo;
    }

    /**
     * @param merchantNo 主体号，必填
     * @param payChannel WECHAT / ALIPAY，必填
     * @param storeNo    门店号；空/空串 = 主体级默认号
     */
    public record RefreshReq(String merchantNo, String payChannel, String storeNo) {
    }
}
