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

    private final StoreService storeService;
    private final StoreFavoriteService favoriteService;
    private final StoreCodeService storeCodeService;
    private final AttributionService attributionService;

    public MpStoreController(StoreService storeService, StoreFavoriteService favoriteService,
                             StoreCodeService storeCodeService,
                             AttributionService attributionService) {
        this.storeService = storeService;
        this.favoriteService = favoriteService;
        this.storeCodeService = storeCodeService;
        this.attributionService = attributionService;
    }

    @GetMapping("/mp/store/mine")
    public List<StoreBriefVO> myStores() {
        return favoriteService.myStores();
    }

    @GetMapping("/mp/store/by-code")
    public StoreHomeVO byCode(@RequestParam String storeCode) {
        String merchantNo = storeCodeService.resolve(storeCode);
        return storeService.home(merchantNo, SecurityUtils.currentUserNoOrNull(),
                favoriteService.isFavorited(merchantNo));
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
}
