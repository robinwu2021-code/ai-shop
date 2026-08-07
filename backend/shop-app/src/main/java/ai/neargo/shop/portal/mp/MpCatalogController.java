package ai.neargo.shop.portal.mp;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.dto.SkuPriceVO;
import ai.neargo.shop.product.service.CategoryService;
import ai.neargo.shop.product.service.GoodsService;
import ai.neargo.shop.user.dto.CommunityVO;
import ai.neargo.shop.user.dto.MerchantScoreVO;
import ai.neargo.shop.user.dto.MerchantVO;
import ai.neargo.shop.user.dto.VisitedMerchantVO;
import ai.neargo.shop.user.service.CommunityService;
import ai.neargo.shop.user.service.MerchantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「逛」的三组只读端点：社区 · 商品 · 商家（[API 清单 §2.2/2.3/2.11]）。全部游客可访问。
 *
 * <p>三组合在一个 Controller 是因为它们同属「浏览」这一件事、且都很薄。
 * 但**跨了两个 svc 模块**（user 与 product）—— 这在 portal 层是允许的，
 * portal 本来就是聚合层；不允许的是 svc 之间互相依赖（ArchUnit 管这个）。
 */
@RestController
public class MpCatalogController {

    private static final long DEFAULT_SIZE = 10;

    private final CommunityService communityService;
    private final GoodsService goodsService;
    private final MerchantService merchantService;
    private final CategoryService categoryService;
    private final ai.neargo.shop.platform.OpsService opsService;

    public MpCatalogController(CommunityService communityService, GoodsService goodsService,
                               MerchantService merchantService, CategoryService categoryService,
                               ai.neargo.shop.platform.OpsService opsService) {
        this.communityService = communityService;
        this.goodsService = goodsService;
        this.merchantService = merchantService;
        this.categoryService = categoryService;
        this.opsService = opsService;
    }

    @GetMapping("/mp/community/nearby")
    public List<CommunityVO> nearby(@RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lng) {
        return communityService.nearby(toE6(lat), toE6(lng));
    }

    @GetMapping("/mp/community/{communityNo}")
    public CommunityVO communityDetail(@PathVariable String communityNo) {
        return communityService.detail(communityNo);
    }

    @GetMapping("/mp/pickup/{pickupNo}")
    public CommunityVO.PickupVO pickupDetail(@PathVariable String pickupNo) {
        return communityService.pickupDetail(pickupNo);
    }

    @GetMapping("/mp/goods")
    public PageData<GoodsVO> goodsList(@RequestParam(required = false) String communityNo,
                                       @RequestParam(required = false) String merchantNo,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) String categoryNo,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return goodsService.list(new GoodsService.GoodsQuery(
                communityNo, merchantNo, type, categoryNo, keyword, page, Math.min(size, 50)));
    }

    @GetMapping("/mp/goods/{goodsNo}")
    public GoodsVO goodsDetail(@PathVariable String goodsNo) {
        return goodsService.detail(goodsNo);
    }

    @GetMapping("/mp/category/tree")
    public List<CategoryVO> categoryTree() {
        return categoryService.tree();
    }

    @GetMapping("/mp/goods/{goodsNo}/sku-price")
    public SkuPriceVO skuPrice(@PathVariable String goodsNo, @RequestParam String skuNo) {
        return goodsService.skuPrice(goodsNo, skuNo);
    }

    @GetMapping("/mp/search/suggest")
    public List<String> suggest(@RequestParam(required = false) String keyword) {
        return goodsService.suggest(keyword);
    }

    @GetMapping("/mp/search/hot")
    public List<String> hotWords() {
        return goodsService.hotWords();
    }

    /**
     * ⚠️ 必须声明在 {@code /mp/merchant/{merchantNo}} **之前**：
     * 否则 `visited` 会被当成 merchantNo 匹配掉，返回 404 而不是列表。
     */
    @GetMapping("/mp/merchant/visited")
    public List<VisitedMerchantVO> visitedMerchants() {
        return merchantService.visited();
    }

    /** 入驻申请（C-11.x）。提交后进平台审核队列。 */
    @org.springframework.web.bind.annotation.PostMapping("/mp/merchant/apply")
    public java.util.Map<String, String> merchantApply(
            @org.springframework.web.bind.annotation.RequestBody ApplyReq req) {
        String applyNo = opsService.createApply(
                ai.neargo.shop.auth.SecurityUtils.currentUserNo(),
                req.name(), req.type(), req.contactPhone(), req.qualifications());
        return java.util.Map.of("applyNo", applyNo);
    }

    public record ApplyReq(String name, String type, String contactPhone,
                           List<String> qualifications) {
    }

    @GetMapping("/mp/merchant/{merchantNo}/score")
    public MerchantScoreVO merchantScore(@PathVariable String merchantNo) {
        return merchantService.score(merchantNo);
    }

    @GetMapping("/mp/merchant")
    public PageData<MerchantVO> merchantList(@RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size) {
        return merchantService.search(keyword, page, Math.min(size, 50));
    }

    @GetMapping("/mp/merchant/{merchantNo}")
    public MerchantVO merchantDetail(@PathVariable String merchantNo) {
        return merchantService.detail(merchantNo);
    }

    private Integer toE6(Double degree) {
        return degree == null ? null : (int) Math.round(degree * 1e6);
    }
}
