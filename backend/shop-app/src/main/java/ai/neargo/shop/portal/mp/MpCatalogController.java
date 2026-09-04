package ai.neargo.shop.portal.mp;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.dto.SkuPriceVO;
import ai.neargo.shop.product.service.CategoryService;
import ai.neargo.shop.product.service.GoodsService;
import ai.neargo.shop.community.dto.CommunityVO;
import ai.neargo.shop.community.dto.RegionOptionVO;
import ai.neargo.shop.merchant.dto.MerchantScoreVO;
import ai.neargo.shop.merchant.dto.MerchantVO;
import ai.neargo.shop.merchant.dto.VisitedMerchantVO;
import ai.neargo.shop.community.service.CommunityService;
import ai.neargo.shop.merchant.service.MerchantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.RegionService;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.auth.SecurityUtils;
import java.util.Map;
import java.util.List;

/**
 * 「逛」的三组只读端点：社区 · 商品 · 商家（[API 清单 §2.2/2.3/2.11]）。全部游客可访问。
 *
 * <p>三组合在一个 Controller 是因为它们同属「浏览」这一件事、且都很薄。
 * 但**跨了两个 svc 模块**（user 与 product）—— 这在 portal 层是允许的，
 * portal 本来就是聚合层；不允许的是 svc 之间互相依赖（ArchUnit 管这个）。
 */
@Profile("api")
@RestController
public class MpCatalogController {

    private static final long DEFAULT_SIZE = 10;

    private final CommunityService communityService;
    private final GoodsService goodsService;
    private final MerchantService merchantService;
    private final CategoryService categoryService;
    private final ai.neargo.shop.platform.OpsService opsService;
    private final ai.neargo.shop.platform.RegionService regionService;

    public MpCatalogController(CommunityService communityService, GoodsService goodsService,
                               MerchantService merchantService, CategoryService categoryService,
                               ai.neargo.shop.platform.OpsService opsService,
                               ai.neargo.shop.platform.RegionService regionService) {
        this.communityService = communityService;
        this.goodsService = goodsService;
        this.merchantService = merchantService;
        this.categoryService = categoryService;
        this.opsService = opsService;
        this.regionService = regionService;
    }

    @GetMapping("/mp/community/nearby")
    public List<CommunityVO> nearby(@RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lng) {
        return communityService.nearby(toE6(lat), toE6(lng));
    }

    /**
     * 一个坐标解析出「我在哪」+ 归属链。**C 端匹配的唯一入口。**
     *
     * <p>端上此前是拿 {@code nearby} 的第一条当「我在哪」—— 那把
     * 「最内层怎么定」这条业务规则放进了端，而 c-app / b-app / 将来的 H5
     * 会各写一份，它们迟早不一样。
     *
     * @param coarse 坐标是不是模糊定位给的。是的话不做聚落匹配（理由见 service）
     */
    @GetMapping("/mp/location/resolve")
    public CommunityService.LocationVO resolveLocation(
            @RequestParam(required = false) Integer latE6,
            @RequestParam(required = false) Integer lngE6,
            @RequestParam(required = false, defaultValue = "false") boolean coarse) {
        return communityService.resolve(latE6, lngE6, coarse);
    }

    /**
     * 全部已开通社区 —— <b>「附近没有」时的出路</b>。
     *
     * <p>附近为空不能是死路：这一页是新用户的第一屏，停在「暂未开通」而没有下一步，
     * 等于在第一屏劝退。而异地下单在社区团购里是真实高频场景（给父母下单、出差前囤货），
     * 手动选一个远点是**用户的知情选择** —— 与系统把远点伪装成「附近」是两回事。
     */
    @GetMapping("/mp/community")
    public List<CommunityVO> allCommunities(@RequestParam(required = false) String regionCode) {
        return communityService.all(regionCode);
    }

    /**
     * 可选的区域清单 —— <b>只列有已开通社区的</b>。
     *
     * <p>区划全表有 2978 个区县、41352 个街道。把整棵树扔给用户去挑，
     * 十有八九挑到一个一家店都没有的区：那不是「选区域」，那是抽奖。
     */
    @GetMapping("/mp/community/regions")
    public List<RegionOptionVO> openRegions() {
        return communityService.openRegions();
    }

    /**
     * 行政区划的直接下级，**止于区县**（省 → 市 → 区）。{@code parent} 为空取省级。
     *
     * <p><b>与上面那条 {@code /mp/community/regions} 是两件事，别混。</b>
     * 那一条只列「有已开通社区的区」—— 它回答的是「我能在哪儿取货」，
     * 把整棵树扔给用户去挑自提点，十有八九挑到一个一家店都没有的区。
     * 这一条回答的是「我家在哪儿」，那是用户的事实，不是平台的经营范围：
     * 按「已开通」去裁，等于告诉一个住在没开通城市的人「你不住在那儿」。
     *
     * <p><b>止于区县</b>是因为收货地址表就到这一级（{@code usr_address} 的
     * province / city / district 三列），再往下的街道、村是 4 万与 62 万行 ——
     * 那两级属于自提点与经营范围的模型（见 BizRegionController 的聚落注释），
     * 不是地址簿的。区县这一层把 hasChild 压成 false：不压的话端上看到可以再钻，
     * 点进去却是「街道」，而地址表没有那一列可放。
     *
     * <p>游客可访问：填地址前先要登录，但**区划是公共参照数据**，
     * 不该因为没登录就查不到 —— 那会让「先选地址再登录」这条路走不通。
     */
    @GetMapping("/mp/regions")
    public List<MpRegionVO> regions(@RequestParam(required = false) String parent) {
        return regionService.children(parent, true).stream()
                .filter(r -> !"STREET".equals(r.level()) && !"VILLAGE".equals(r.level()))
                // 区县这一级把 hasChild 压成 false（见方法注释：地址表没有街道那一列）
                .map(r -> new MpRegionVO(r.regionCode(), r.parentCode(), r.level(), r.name(),
                        !"DISTRICT".equals(r.level()) && Boolean.TRUE.equals(r.hasChild())))
                .toList();
    }

    /**
     * C 端看到的区划节点：**只有四个字段加一个 hasChild**。
     *
     * <p>不复用 {@code RegionService.RegionVO} 的理由不是洁癖。那个记录里有
     * {@code source} / {@code pending} / {@code auditStatus} / {@code rejectReason} ——
     * 区划提报的审核状态与**驳回理由**。而这个端点是免登录的：直接透传等于把
     * 「谁提报了哪个区、为什么被驳回」发给任何一个打开小程序的人。
     * 端上一个都用不到，也不会有人注意到它们在返回体里。
     *
     * <p>顺带把 {@code latE6/lngE6/rural} 也挡在外面：地址簿不需要区划的坐标，
     * 而 62 万行村级数据的坐标补录是另一条线的事（见 BizRegionController）。
     */
    public record MpRegionVO(String regionCode, String parentCode, String level,
                             String name, boolean hasChild) {
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

    /** 推荐商品（运营位）。游客可见 —— 没登录也该看到平台在推什么 */
    @GetMapping("/mp/goods/promoted")
    public List<GoodsVO> promotedGoods(@RequestParam(required = false) String communityNo,
                                       @RequestParam(required = false) Integer size) {
        return goodsService.promoted(communityNo, size);
    }

    /** 推荐门店（运营位）。用途是新店冷启动，刻意不看历史成绩 */
    @GetMapping("/mp/merchant/promoted")
    public List<MerchantVO> promotedMerchants(@RequestParam(required = false) String communityNo,
                                              @RequestParam(required = false) Integer size) {
        return merchantService.promoted(communityNo, size);
    }

    /**
     * 入驻申请（C-11.x）。提交后进平台审核队列。
     *
     * <p><b>返回的是提交后的完整状态，不是一个 applyNo。</b>
     * 端上拿这个返回值直接替换页面上的申请状态（{@code c-app/src/pages/me/index.vue}），
     * 只给单号的话，状态、主体名、提交时间全是 undefined —— 提交成功却渲染出一张空白卡片，
     * 而且不报错，用户只会以为没提交上。
     *
     * <p>与 B 端 {@code POST /biz/merchant/apply} 同一口径（那边返回提交后的 profile）。
     */
    @PostMapping("/mp/merchant/apply")
    public MerchantApplyVO merchantApply(@RequestBody ApplyReq req) {
        opsService.createApply(new OpsService.SubmitApplyCommand(
                SecurityUtils.currentUserNo(), req.name(), req.subject(),
                req.contactName(), req.contactPhone(), req.category(), req.desc(),
                req.serviceScope(), req.communityNos(), req.licenses(),
                false, req.industry(), req.qualificationItems()));
        return opsService.myApply(SecurityUtils.currentUserNo());
    }

    /**
     * 我的入驻申请状态。<b>此前提交完就查不到了</b> ——
     * 商家不知道审到哪一步，只能打电话问运营。没申请过返回 null，不是错误。
     */
    @GetMapping("/mp/merchant/apply")
    public MerchantApplyVO myMerchantApply() {
        return opsService.myApply(SecurityUtils.currentUserNo());
    }

    /**
     * @param licenses     资质图。**选填** —— 分账主体开户是独立流程（ADR-002），
     *                     逼一个还没通过审核的人先传营业执照只会把人挡在门外
     * @param communityNos 期望覆盖的社区。申请时可空，审核通过时由运营确认
     */
    public record ApplyReq(String name, String subject, String contactName, String contactPhone,
                           String category, String desc, String serviceScope,
                           List<String> communityNos, List<String> licenses,
                           /** 行业。**决定可选的主体类型** —— 线上业态不能选小微 */
                           String industry,
                           /** 结构化资质（V79）。见 B 端 {@code ApplyReq} 的说明 —— 两端同一口径 */
                           List<OpsService.QualificationItem> qualificationItems) {
    }

    @GetMapping("/mp/merchant/{merchantNo}/score")
    public MerchantScoreVO merchantScore(@PathVariable String merchantNo) {
        return merchantService.score(merchantNo);
    }

    @GetMapping("/mp/merchant")
    public PageData<MerchantVO> merchantList(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String communityNo,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size) {
        return merchantService.search(keyword, communityNo, page, Math.min(size, 50));
    }

    @GetMapping("/mp/merchant/{merchantNo}")
    public MerchantVO merchantDetail(@PathVariable String merchantNo) {
        return merchantService.detail(merchantNo);
    }

    private Integer toE6(Double degree) {
        return degree == null ? null : (int) Math.round(degree * 1e6);
    }
}
