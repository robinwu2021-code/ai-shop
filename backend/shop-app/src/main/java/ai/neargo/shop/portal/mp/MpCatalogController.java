package ai.neargo.shop.portal.mp;

import ai.neargo.shop.platform.entity.SysRegion;
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
    private final ai.neargo.shop.product.service.GoodsFavoriteService goodsFavoriteService;
    /** 海报要的店铺码（§7.3）。一店一码、生成一次落库复用 */
    private final ai.neargo.shop.merchant.service.StoreCodeService storeCodeService;
    /** 商家页那个「N 人收藏」（§8.2 批 2）。现算，主体表上没有这一列 */
    private final ai.neargo.shop.user.service.StoreFavoriteService storeFavoriteService;

    public MpCatalogController(CommunityService communityService, GoodsService goodsService,
                               MerchantService merchantService, CategoryService categoryService,
                               ai.neargo.shop.platform.OpsService opsService,
                               ai.neargo.shop.platform.RegionService regionService,
                               ai.neargo.shop.product.service.GoodsFavoriteService goodsFavoriteService,
                               ai.neargo.shop.merchant.service.StoreCodeService storeCodeService,
                               ai.neargo.shop.user.service.StoreFavoriteService storeFavoriteService) {
        this.communityService = communityService;
        this.goodsService = goodsService;
        this.merchantService = merchantService;
        this.categoryService = categoryService;
        this.opsService = opsService;
        this.goodsFavoriteService = goodsFavoriteService;
        this.regionService = regionService;
        this.storeCodeService = storeCodeService;
        this.storeFavoriteService = storeFavoriteService;
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
     * 「输个名字找地方」。**本地优先，地图是补充。**
     *
     * <p>本地那一条带着 {@code communityNo}，选中它才能直接绑到聚落；
     * 地图那一条只有名字与坐标。同名的两条里留本地那条。
     *
     * <p><b>地图不可用时它照样有结果</b>（只是少）—— 端上的搜索框因此不必消失。
     * 整段不渲染等于告诉用户「这儿什么都没有」，而那不是事实。
     *
     * @param city 没有坐标时按城市搜。**city 只是偏好不是约束**
     *             （在深圳搜「福安」会返回福建的福安市），所以有坐标一律围着坐标搜
     */
    @GetMapping("/mp/place/search")
    public List<CommunityService.PlaceHitVO> searchPlaces(
            // 关键词可空：服务层对空关键词返回空表。写成必填的话，
            // 端上首屏（还没输入）拿到的是一个 400，而它本该是「还没搜」
            @RequestParam(required = false) String kw,
            @RequestParam(required = false) Integer latE6,
            @RequestParam(required = false) Integer lngE6,
            @RequestParam(required = false) String city) {
        return communityService.searchPlaces(kw, latE6, lngE6, city);
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
    /**
     * @param level 传 {@code CITY} 时**一次给全国所有市**（约 370 条），忽略 {@code parent}。
     *              城市选择器要按拼音索引与搜索找全国任意一个城市，一个省一次是 34 次往返。
     *              只放行市级，理由见 {@code RegionService#allOfLevel}
     */
    @GetMapping("/mp/regions")
    public List<MpRegionVO> regions(@RequestParam(required = false) String parent,
                                    @RequestParam(required = false) String level) {
        List<ai.neargo.shop.platform.RegionService.RegionVO> src =
                level != null && !level.isBlank()
                        ? regionService.allOfLevel(level, true)
                        : regionService.children(parent, true);
        return src.stream()
                .filter(r -> !SysRegion.LEVEL_STREET.equals(r.level()) && !SysRegion.LEVEL_VILLAGE.equals(r.level()))
                // 区县这一级把 hasChild 压成 false（见方法注释：地址表没有街道那一列）
                .map(r -> new MpRegionVO(r.regionCode(), r.parentCode(), r.level(), r.name(),
                        !SysRegion.LEVEL_DISTRICT.equals(r.level()) && Boolean.TRUE.equals(r.hasChild())))
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

    /**
     * @param regionCode 模糊定位时的兜底筛选（区县码）。{@code communityNo} 在时它不参与 ——
     *                   精确的结论压过粗的那个
     */
    @GetMapping("/mp/goods")
    public PageData<GoodsVO> goodsList(@RequestParam(required = false) String communityNo,
                                       @RequestParam(required = false) String regionCode,
                                       @RequestParam(required = false) String merchantNo,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) String categoryNo,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return goodsService.list(new GoodsService.GoodsQuery(
                communityNo, regionCode, merchantNo, type, categoryNo, keyword, page, Math.min(size, 50)));
    }

    /**
     * @param communityNo 收货地址推出来的社区（可选）。给了就判「卖不卖到那儿」（{@code deliverable}），
     *                    没给就不判 —— 端上只有模糊定位时不传（TDD-C端商品收藏与送达判断 AC6）
     */
    @GetMapping("/mp/goods/{goodsNo}")
    public GoodsVO goodsDetail(@PathVariable String goodsNo,
                               @RequestParam(required = false) String communityNo,
                               @RequestParam(required = false) String storeNo) {
        return goodsService.detailForBuyer(goodsNo, storeNo).withViewer(
                goodsFavoriteService.isFavorited(goodsNo),
                goodsService.deliverableTo(goodsNo, communityNo));
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
                                       @RequestParam(required = false) String regionCode,
                                       @RequestParam(required = false) Integer size) {
        return goodsService.promoted(communityNo, regionCode, size);
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
                req.contactName(), req.contactPhone(), req.referrerPhone(),
                req.category(), req.desc(),
                req.serviceScope(), req.communityNos(), req.licenses(),
                false, req.industry(), req.industryNote(), req.qualificationItems()));
        return opsService.myApply(SecurityUtils.currentUserNo());
    }

    /**
     * 改自己那份还在等审核的入驻意向。
     *
     * <p><b>只有待审核能改</b>：运营开始看了（REVIEWING）就锁，否则他看的与库里存的
     * 不是同一份；驳回后是重新提交一份新的（后端状态机里 REJECTED 是终态）；
     * 已通过的改意向单也改不到商家档案。
     */
    @PostMapping("/mp/merchant/apply/{applyNo}")
    public MerchantApplyVO updateMerchantApply(@PathVariable String applyNo,
                                               @RequestBody ApplyReq req) {
        String userNo = SecurityUtils.currentUserNo();
        opsService.updateApply(applyNo, userNo, new OpsService.SubmitApplyCommand(
                userNo, req.name(), req.subject(),
                req.contactName(), req.contactPhone(), req.referrerPhone(),
                req.category(), req.desc(),
                req.serviceScope(), req.communityNos(), req.licenses(),
                false, req.industry(), req.industryNote(), req.qualificationItems()));
        return opsService.myApply(userNo);
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
                           /**
                            * 推荐人手机号（选填，V353）。**端上只是一个输入框，不带任何奖励文案** ——
                            * 规则只在官网与企微里出现（TDD-C 端裂变与商家招募 §8.3）。
                            */
                           String referrerPhone,
                           String category, String desc, String serviceScope,
                           List<String> communityNos, List<String> licenses,
                           /** 行业。**决定可选的主体类型** —— 线上业态不能选小微 */
                           String industry,
                           /**
                            * 商家自己写的行业（仅 {@code industry = OTHER} 时有值，V360）。
                            * 意向口径，不参与准入判定 —— 归不进七个大类的那句话丢了，
                            * 意向表就只剩已知的东西。
                            */
                           String industryNote,
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

    /**
     * 商家详情。
     *
     * <p><b>在售件数现算</b>（TDD-C 端裂变与商家招募 §7.4）：`mch_entity.goods_count`
     * 那一列<b>线上恒为 0</b> —— 它声明了却没人维护，而商家页要把「这家店在卖什么规模」
     * 说给买家听。现算是一次带条件的 count，这一页本来就是低频的详情页。
     *
     * <p>为什么不顺手修那一列：它同时被商品页的销量、商家列表用着，
     * 改写入口是商品域的事（已记进欠账）—— 在这里现算只影响这一页，
     * 而把一个没人写的快照列改成有人写，是另一件要单独验的事。
     */
    @GetMapping("/mp/merchant/{merchantNo}")
    public MerchantVO merchantDetail(@PathVariable String merchantNo) {
        MerchantVO m = merchantService.detail(merchantNo);
        long onSale = goodsService.list(new GoodsService.GoodsQuery(
                null, null, merchantNo, null, null, null, 1, 1)).total();
        /*
         * 收藏人数同样现算（§8.2 批 2）。**0 时端上不显示** ——
         * 这个功能上线至今线上 0 行，显示「0 人收藏」等于自曝冷启动，
         * 与 §7.4 不显示成交数是同一个取向。判 0 在端上做，这里只给真值。
         */
        return m.withGoodsCount((int) onSale)
                .withFavoriteCount(storeFavoriteService.countByMerchant(merchantNo));
    }

    /**
     * 这家店的小程序码（海报要用，§7.3）。**游客可见** —— 海报本来就是发出去给陌生人看的。
     *
     * <p><b>码是店铺码，不带邀请人。</b> {@code wxacode.getUnlimited} 生成的是
     * <b>永久码且每个 appid 总量有限</b>（十万级），所以 {@code StoreCodeService} 的做法是
     * 一店一码、生成一次落库复用。把 {@code inviterNo} 编进 scene 意味着「每个用户一张永久码」，
     * 用户一多就把额度烧穿 —— 而烧穿之后<b>新入驻的商家再也拿不到店铺码</b>，
     * 代价落在完全无关的地方。所以海报归因到<b>店</b>，邀请归因走小程序内转发那条路。
     *
     * <p>放在这个控制器里而不是 {@code MpStoreController}：那一条的资源是 store，
     * 而这条挂在 merchant 下（与 {@code GET /mp/merchant/&#123;merchantNo&#125;} 同一资源）——
     * 放错了会让那个控制器多装一种资源，闸门当场报。
     *
     * <p>通道未开启或生成失败时 {@code imageBase64} 为 <b>null</b> ——
     * 端上据此画一张不带码的海报，而不是卡在那里等一张永远来不了的图。
     */
    @GetMapping("/mp/merchant/{merchantNo}/acode")
    public StoreAcode merchantAcode(@PathVariable String merchantNo) {
        return new StoreAcode(merchantNo, storeCodeService.acodeBase64(merchantNo, null));
    }

    /** @param imageBase64 小程序码 PNG 的 base64（不含 data: 前缀）；通道未开启时为 null */
    public record StoreAcode(String merchantNo, String imageBase64) {
    }

    private Integer toE6(Double degree) {
        return degree == null ? null : (int) Math.round(degree * 1e6);
    }
}
