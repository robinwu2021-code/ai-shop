package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.marketing.attribution.AttributionService;
import ai.neargo.shop.marketing.attribution.dto.AttributionVO;
import ai.neargo.shop.product.dto.FrequentItemVO;
import ai.neargo.shop.product.dto.RebuyResultVO;
import ai.neargo.shop.product.dto.ReorderResultVO;
import ai.neargo.shop.product.dto.StoreHomeVO;
import ai.neargo.shop.product.service.StoreService;
import ai.neargo.shop.merchant.service.StoreCodeService;
import ai.neargo.shop.user.dto.StoreBriefVO;
import ai.neargo.shop.user.service.StoreFavoriteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店主页与归因（[API 清单 §2.9/2.10]）—— **一期主获客路径**（ADR-004）。
 *
 * <p>主页与扫码进店**游客可访问**：扫码的人多数还没登录，
 * 要求先登录才能看店，这条路径就断在第一步。
 */
@Profile("api")
@RestController
public class MpStoreController {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(MpStoreController.class.getName());

    private final StoreService storeService;
    private final StoreFavoriteService favoriteService;
    private final StoreCodeService storeCodeService;
    private final AttributionService attributionService;
    private final ai.neargo.shop.marketing.visit.StoreVisitService storeVisitService;
    private final ai.neargo.shop.merchant.service.AppointmentSlotService appointmentSlotService;

    public MpStoreController(StoreService storeService, StoreFavoriteService favoriteService,
                             StoreCodeService storeCodeService,
                             AttributionService attributionService,
                             ai.neargo.shop.marketing.visit.StoreVisitService storeVisitService,
                             ai.neargo.shop.merchant.service.AppointmentSlotService appointmentSlotService) {
        this.storeService = storeService;
        this.favoriteService = favoriteService;
        this.storeCodeService = storeCodeService;
        this.attributionService = attributionService;
        this.storeVisitService = storeVisitService;
        this.appointmentSlotService = appointmentSlotService;
    }

    @GetMapping("/mp/store/mine")
    public List<StoreBriefVO> myStores() {
        return favoriteService.myStores();
    }

    /**
     * 扫码落地。<b>游客可访问</b>，并且**在这里记获客漏斗的第一层**。
     *
     * <p>为什么埋点挂在这条而不是 {@code /enter}：{@code enter} 要求登录，
     * 而扫码的人多数还没登录 —— 挂在那儿的话「扫了码但还没注册的人」恒为 0，
     * 也就是漏斗最宽的那一层永远是空的，而这正是「这批贴纸有没有用」的答案。
     *
     * <p>埋点<b>不影响本接口的成败</b>：{@code record} 内部吞掉一切异常。
     * 商家印出去的贴纸不能因为一次埋点写失败就扫不进来。
     */
    @GetMapping("/mp/store/by-code")
    public StoreHomeVO byCode(@RequestParam String storeCode,
                              @RequestParam(required = false) String deviceId,
                              jakarta.servlet.http.HttpServletRequest request) {
        // V298：码带得出是哪家分店了。此前这里恒传 null，mkt_store_visit.store_no 一直是空的
        var target = storeCodeService.resolveTarget(storeCode);
        String merchantNo = target.entityNo();
        storeVisitService.record(new ai.neargo.shop.marketing.visit.StoreVisitService.Visit(
                merchantNo, storeCode, target.storeNo(),
                // 为空就是匿名访客 —— 那是要测的一层，不是缺失
                SecurityUtils.currentUserNoOrNull(), deviceId,
                clientIp(request), uaHash(request)));
        /*
         * **归因也在这里写**（漏斗第二环）。
         *
         * 此前它只挂在 {@code /enter} 上，而<b>没有任何端调用过 /enter</b> ——
         * 于是「进店 / 首次归因 / 首单」三环在生产里恒为 0，看板上一片零，
         * 却看不出是「没人来」还是「没人记」。
         *
         * 放在这条而不是让端上再发一次：storeCode 只有服务端解得开，
         * 端上多一次请求就多一个「忘了调」的机会 —— 而这正是它坏掉的原因。
         *
         * 未登录就跳过：归因必须挂在具体的人身上。那部分人已经被上面的
         * 匿名扫码埋点记下了，是漏斗最宽的那一层。
         */
        String userNo = SecurityUtils.currentUserNoOrNull();
        if (userNo != null && !userNo.isBlank()) {
            try {
                attributionService.report(userNo,
                        new ai.neargo.shop.marketing.attribution.AttributionService.Clue(
                                merchantNo, null, null));
            } catch (RuntimeException e) {
                // 与埋点同一条理由：归因失败不能让贴纸扫不进来
                LOG.log(java.util.logging.Level.WARNING, "扫码归因失败：" + storeCode, e);
            }
        }
        return storeService.home(merchantNo, userNo,
                favoriteService.isFavorited(merchantNo));
    }

    /** 取值是 web 层的事（领域服务不碰 web 运行时，ArchitectureTest 拦这条）。 */
    private static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    /** 只留摘要：UA 原文可用于指纹，属个人信息，没有留存的理由。 */
    private static String uaHash(jakarta.servlet.http.HttpServletRequest request) {
        String ua = request == null ? null : request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return null;
        }
        return Integer.toHexString(ua.hashCode());
    }

    @GetMapping("/mp/store/{merchantNo}")
    public StoreHomeVO home(@PathVariable String merchantNo) {
        return storeService.home(merchantNo, SecurityUtils.currentUserNoOrNull(),
                favoriteService.isFavorited(merchantNo));
    }

    /** 进店埋点 + 归因。需要登录 —— 归因必须挂在具体的人身上。 */
    @PostMapping("/mp/store/{merchantNo}/enter")
    public AttributionVO enter(@PathVariable String merchantNo, @RequestBody(required = false) EnterReq req) {
        return attributionService.report(SecurityUtils.currentUserNo(),
                new AttributionService.Clue(merchantNo,
                        req == null ? null : req.inviterNo(),
                        req == null ? null : req.channel()));
    }

    @PostMapping("/mp/attribution/report")
    public AttributionVO report(@RequestBody AttributionReportReq req) {
        return attributionService.report(SecurityUtils.currentUserNo(),
                new AttributionService.Clue(req.merchantNo(), req.inviterNo(), req.channel()));
    }

    @GetMapping("/mp/store/{merchantNo}/frequent")
    public List<FrequentItemVO> frequent(@PathVariable String merchantNo) {
        return storeService.frequentItems(merchantNo);
    }

    @PostMapping("/mp/store/{merchantNo}/rebuy")
    public RebuyResultVO rebuy(@PathVariable String merchantNo) {
        return storeService.rebuy(merchantNo);
    }

    /**
     * 一键再来一单：<b>整单</b>复制到购物车。
     * 与上面的 rebuy 不是一回事 —— 那个复制「这家店我常买的」，这个复制「这一单买过的」。
     */
    @PostMapping("/mp/order/{orderNo}/reorder")
    public ReorderResultVO reorderFrom(@PathVariable String orderNo) {
        return storeService.reorderFrom(orderNo);
    }

    @PostMapping("/mp/store/{merchantNo}/favorite")
    public List<StoreBriefVO> toggleFavorite(@PathVariable String merchantNo) {
        return favoriteService.toggle(merchantNo);
    }

    public record EnterReq(String storeCode, String inviterNo, String channel) {
    }

    public record AttributionReportReq(String merchantNo, String inviterNo, String channel) {
    }

    // ---------------------------------------------------------------- 预约时段

    /**
     * 这家店还约得上的时段。
     *
     * <p><b>只列可约且没满的</b>：买家看见一个约不上的档只会去点它，然后拿到一句错误。
     * 商家侧的接口相反 —— 那边要连约满的和停掉的一起看，否则不知道
     * 「没人约」是「没开时段」还是「开的都满了」。
     *
     * <p>⚠️ 列表只是**那一刻**的快照，不是承诺。真正的判定在下单那条带条件的
     * UPDATE 里 —— 两个人同时看到同一个「剩 1」，只有一个抢得到。
     */
    @GetMapping("/mp/stores/{storeNo}/appointment-slots")
    public java.util.List<ai.neargo.shop.merchant.service.AppointmentSlotService.SlotVO> appointmentSlots(
            @PathVariable String storeNo,
            @RequestParam long from, @RequestParam long to) {
        return appointmentSlotService.list(storeNo, from, to, true);
    }
}
