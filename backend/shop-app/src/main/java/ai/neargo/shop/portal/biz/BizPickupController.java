package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.Map;

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
    private final ai.neargo.shop.merchant.service.StoreLinkService storeLinkService;
    /** 主体已授权的经营类目码 —— /biz/context 要把它带给端上 */
    private final ai.neargo.shop.spi.user.MerchantQueryPort merchantPort;
    /** 平台开关：类目闸门开不开，端上要跟着变文案与拦不拦 */
    private final ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort;

    public BizPickupController(PickupService pickupService,
                               ai.neargo.shop.merchant.service.StoreCodeService storeCodeService,
                               ai.neargo.shop.merchant.service.StoreLinkService storeLinkService,
                               ai.neargo.shop.spi.user.MerchantQueryPort merchantPort,
                               ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort) {
        this.merchantPort = merchantPort;
        this.switchPort = switchPort;
        this.pickupService = pickupService;
        this.storeCodeService = storeCodeService;
        this.storeLinkService = storeLinkService;
    }

    /**
     * 当前用户在经营侧的作用域与权限。<b>前端据此决定展示哪些入口</b>。
     *
     * <p>不直接返回 {@link BizContext} 那个 record：它内部有 {@code rolesByStore}
     * （每家店的角色映射），整个发出去既冗余又暴露了端上用不着的结构。
     * 这里只给<b>当前门店</b>的角色与算好的权限码。
     *
     * <p><b>切门店后要重新调它</b> —— 角色跟着门店走，同一个人可能在
     * A 店是店长、B 店是店员，权限跟着变。
     */
    @GetMapping("/biz/context")
    public BizContextVO context() {
        BizContext ctx = BizContext.current();
        if (ctx.merchantNo() == null || ctx.merchantNo().isBlank()) {
            // 不是商家：403 而不是空对象 —— 空对象会让前端渲染出一个点不动的经营台
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return new BizContextVO(ctx.merchantNo(), ctx.currentStoreNo(), ctx.owner(),
                List.copyOf(ctx.storeNos()), List.copyOf(ctx.pickupNos()),
                List.copyOf(ctx.groupNos()), List.copyOf(ctx.staffRoles()),
                // **与判权同一个来源**（含自定义角色）：这里另算一遍的话，
                // 只有自定义角色的人会看到一个什么入口都没有的经营台
                List.copyOf(ctx.effectivePerms()),
                /*
                 * 主体已获批的经营类目码。**端上据它把没资质的类目标出来** ——
                 * 不下发的话，商家只能靠「选了、保存、被拒」这条路才知道自己不能卖，
                 * 而那句报错既说不出缺哪张证，也说不出去哪申请。
                 */
                List.copyOf(merchantPort.authorizedCategoryCodes(ctx.merchantNo())),
                /*
                 * 只下发商家侧真的会读的那几个。**不整份倒给端上** ——
                 * 平台开关里有运营专用的项，端上拿到也没用，而多下发一个字段
                 * 就多一处将来会被误读的地方。
                 */
                Map.of("categoryGate", switchPort.bool("category.gate.enforce", false)));
    }

    /**
     * @param groupNos   我发起了哪些团（第三个作用域，与门店/自提点正交）
     * @param staffRoles 我在<b>当前门店</b>持有的角色（可多个）。老板恒为 [OWNER]
     * @param perms      这些角色合起来的权限码，<b>已取并集</b>。老板是 ["*"]。
     *                   端上照它裁剪入口 —— 不要自己按角色再推一遍，
     *                   两处各推一次迟早分岔，而分岔的表现是「看得见但点了报错」
     */
    public record BizContextVO(String merchantNo, String currentStoreNo, boolean owner,
                               List<String> storeNos, List<String> pickupNos,
                               List<String> groupNos,
                               List<String> staffRoles, List<String> perms,
                               /**
                                * 主体已获批的经营类目码（如 {@code ["FRESH_VEG"]}）。
                                * 与 {@code prd_category.requiredCode} 比对 —— 端上用它
                                * 在类目选择器里把「你还不能卖」标出来，而不是等保存被拒。
                                */
                               List<String> categoryCodes,
                               /**
                                * 平台开关里与商家侧有关的那几个。
                                *
                                * <p><b>不再用端上的编译期常量。</b>此前
                                * {@code b-app/src/shared/flags.ts} 的 ENFORCE_CATEGORY_GATE
                                * 是构建期烧进去的，运营改一次开关要重新打包发版；
                                * 更糟的是它与后端那份不同步时，症状是
                                * 「点不动一个其实能按的按钮」或「点下去吃一句说不清缘由的报错」。
                                *
                                * <p>搭这个接口的车而不是新开一个：它启动就拉、切门店重拉，
                                * 而开关一年动不了几次 —— 为它单开一次请求不值。
                                */
                               Map<String, Boolean> switches) {
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    @GetMapping("/biz/pickup/overview")
    public PickupOverviewVO overview(@RequestParam(required = false) String pickupNo) {
        return pickupService.overview(pickupNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    @PostMapping("/biz/pickup/verify")
    public VerifyResultVO verify(@jakarta.validation.Valid @RequestBody VerifyReq req) {
        return pickupService.verify(req.verifyCode(), Boolean.TRUE.equals(req.onBehalf()));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    @PostMapping("/biz/pickup/verify/batch")
    public PickupService.BatchResult verifyBatch(@RequestBody VerifyBatchReq req) {
        return pickupService.verifyBatch(req.verifyCodes());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    @GetMapping("/biz/pickup/verify/search")
    public List<PickupOrderVO> search(@RequestParam String keyword) {
        return pickupService.searchByCode(keyword);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    @GetMapping("/biz/pickup/orders")
    public List<PickupOrderVO> orders(@RequestParam(required = false) String pickupNo,
                                      @RequestParam(required = false) String status) {
        return pickupService.orders(pickupNo, status);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    @GetMapping("/biz/pickup/picking")
    public List<PickingRowVO> picking(@RequestParam(required = false) String pickupNo) {
        return pickupService.picking(pickupNo);
    }

    /**
     * 我的店铺码（B-11.2.6）。可打印，印在包装袋/贴纸上。
     *
     * <p><b>码是真的微信小程序码</b>（{@code wxacode.getUnlimited}），扫了直接进 C 端门店页。
     * 此前这里只返回一个写死 {@code https://shop.example.com/s/<code>} 的链接 ——
     * <b>占位域名</b>，商家印出去的贴纸指向一个不存在的地方，而功能点标着「已实现」。
     *
     * <p>{@code url} 在未配 {@code shop.web.base-url} 时为 <b>null</b>：
     * 不发假链接，端上据此只显示码。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/store/qrcode")
    public StoreQrcode qrcode() {
        String merchantNo = BizContext.requireMerchantNo();
        String code = storeCodeService.ensureFor(merchantNo);
        return new StoreQrcode(merchantNo, code,
                storeLinkService.linkOf(code, null),
                storeCodeService.acodeBase64(merchantNo),
                "建议印成 3×3cm 贴纸，贴在包装袋封口处");
    }

    /**
     * @param url         对外链接。<b>未配域名时为 null</b> —— 端上不显示链接那一行
     * @param imageBase64 小程序码 PNG 的 base64（不含 data: 前缀）。通道未开启时为 null
     */
    public record StoreQrcode(String merchantNo, String storeCode, String url,
                              String imageBase64, String printableHint) {
    }

    /**
     * @param verifyCode 自提码。**必填** —— 漏传时 null 会一路走到失败日志的插入，
     *                   而 {@code ful_verify_log.verify_code} 是 NOT NULL 且无默认值，
     *                   于是一个本该 400 的输入变成 500「系统开小差了」，
     *                   把排查引向服务端故障（2026-08-15 e2e 实测）
     */
    public record VerifyReq(@jakarta.validation.constraints.NotBlank String verifyCode,
                            Boolean onBehalf) {
    }

    public record VerifyBatchReq(List<String> verifyCodes) {
    }

    /**
     * 标记到货。端上传的是一批子单号（自提点通常一次点完一车货）。
     *
     * <p>对已到货/已核销的重复点击静默跳过 —— 到货登记是高频且容易重复点的动作，
     * 每次都报错只会让人学会忽略报错。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    @PostMapping("/biz/pickup/arrived")
    public List<PickupOrderVO> markArrived(
            @RequestBody ArrivedReq req) {
        // 其余几个接口都能指定 pickupNo，唯独这里不能 —— 多点商家只能给「默认那个点」登记。
        // 默认值本身已经改成「当前门店的点」，这里再让端上能显式指定
        return pickupService.markArrived(req.pickupNo(), req.orderNos());
    }

    /** 短少 / 破损上报。**只留痕并通知买家，不退款**（责任未定，见 Service 注释）。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
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
