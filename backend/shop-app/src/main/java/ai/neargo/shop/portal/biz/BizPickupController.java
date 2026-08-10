package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.fulfillment.dto.PickingRowVO;
import ai.neargo.shop.fulfillment.dto.PickupOverviewVO;
import ai.neargo.shop.fulfillment.dto.VerifyResultVO;
import ai.neargo.shop.fulfillment.service.PickupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import ai.neargo.shop.fulfillment.dto.PickupOrderVO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端自提点履约台（[API 清单 §3.5]）。
 *
 * <p>作用域是 {@code pickup_no}，与商家域的 {@code entity_no} **正交**：
 * 一家店可以不做自提点，一个自提点也会承接别家的货。因此这里不复用 `/biz/order` 的过滤。
 */
@Profile("api")
@RestController
public class BizPickupController {

    private final PickupService pickupService;
    private final ai.neargo.shop.merchant.service.StoreCodeService storeCodeService;

    public BizPickupController(PickupService pickupService,
                               ai.neargo.shop.merchant.service.StoreCodeService storeCodeService) {
        this.pickupService = pickupService;
        this.storeCodeService = storeCodeService;
    }

    /** 当前用户在经营侧的三个作用域。前端据此决定展示哪些入口。 */
    @GetMapping("/biz/context")
    public BizContext context() {
        BizContext ctx = BizContext.current();
        if (ctx.merchantNo() == null || ctx.merchantNo().isBlank()) {
            // 不是商家：403 而不是空对象 —— 空对象会让前端渲染出一个点不动的经营台
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return ctx;
    }

    @GetMapping("/biz/pickup/overview")
    public PickupOverviewVO overview(@RequestParam(required = false) String pickupNo) {
        return pickupService.overview(pickupNo);
    }

    @PostMapping("/biz/pickup/verify")
    public VerifyResultVO verify(@RequestBody VerifyReq req) {
        return pickupService.verify(req.verifyCode(), Boolean.TRUE.equals(req.onBehalf()));
    }

    @PostMapping("/biz/pickup/verify/batch")
    public PickupService.BatchResult verifyBatch(@RequestBody VerifyBatchReq req) {
        return pickupService.verifyBatch(req.verifyCodes());
    }

    @GetMapping("/biz/pickup/verify/search")
    public List<PickupOrderVO> search(@RequestParam String keyword) {
        return pickupService.searchByCode(keyword);
    }

    @GetMapping("/biz/pickup/orders")
    public List<PickupOrderVO> orders(@RequestParam(required = false) String pickupNo,
                                      @RequestParam(required = false) String status) {
        return pickupService.orders(pickupNo, status);
    }

    @GetMapping("/biz/pickup/picking")
    public List<PickingRowVO> picking(@RequestParam(required = false) String pickupNo) {
        return pickupService.picking(pickupNo);
    }

    /** 我的店铺码（B-11.2.6）。可打印，印在包装袋/贴纸上。 */
    @GetMapping("/biz/store/qrcode")
    public StoreQrcode qrcode() {
        String merchantNo = BizContext.requireMerchantNo();
        String code = storeCodeService.ensureFor(merchantNo);
        return new StoreQrcode(merchantNo, code, "https://shop.example.com/s/" + code,
                "建议印成 3×3cm 贴纸，贴在包装袋封口处");
    }

    public record StoreQrcode(String merchantNo, String storeCode, String url, String printableHint) {
    }

    public record VerifyReq(String verifyCode, Boolean onBehalf) {
    }

    public record VerifyBatchReq(List<String> verifyCodes) {
    }

    /**
     * 标记到货。端上传的是一批子单号（自提点通常一次点完一车货）。
     *
     * <p>对已到货/已核销的重复点击静默跳过 —— 到货登记是高频且容易重复点的动作，
     * 每次都报错只会让人学会忽略报错。
     */
    @PostMapping("/biz/pickup/arrived")
    public List<PickupOrderVO> markArrived(
            @RequestBody ArrivedReq req) {
        // 其余几个接口都能指定 pickupNo，唯独这里不能 —— 多点商家只能给「默认那个点」登记。
        // 默认值本身已经改成「当前门店的点」，这里再让端上能显式指定
        return pickupService.markArrived(req.pickupNo(), req.orderNos());
    }

    /** 短少 / 破损上报。**只留痕并通知买家，不退款**（责任未定，见 Service 注释）。 */
    @PostMapping("/biz/pickup/{orderNo}/report")
    public PickupOrderVO reportShortage(
            @PathVariable String orderNo, @RequestBody ReportReq req) {
        return pickupService.reportShortage(null, orderNo, req.kind(), req.skuNo(), req.note());
    }

    /**
     * @param orderNos 一批子单号
     * @param pickupNo 给哪个自提点登记；**不传 = 当前门店的那个点**
     */
    public record ArrivedReq(List<String> orderNos, String pickupNo) {
    }

    /** @param kind SHORTAGE（短少）/ DAMAGE（破损） */
    public record ReportReq(String skuNo, String kind, String note) {
    }
}
