package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.service.MerchantGoodsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * B 端商品管理（[API 清单 §3.3]，B-11.3）。
 *
 * <p>作用域全部来自 {@link BizContext#requireMerchantNo()} —— <b>路径与入参里
 * 一律不接受 merchantNo</b>。接受了就等于把「我是谁」交给调用方声明，
 * 而那正是越权访问的入口。
 */
@Profile("api")
@RestController
public class BizGoodsController {

    private final MerchantGoodsService goodsService;
    private final ai.neargo.shop.product.service.CategoryService categoryService;
    private final ai.neargo.shop.spi.product.GoodsVisionPort vision;
    private final ai.neargo.shop.product.service.SpuStdService spuStdService;

    public BizGoodsController(MerchantGoodsService goodsService,
                              ai.neargo.shop.product.service.CategoryService categoryService,
                              ai.neargo.shop.spi.product.GoodsVisionPort vision,
                              ai.neargo.shop.product.service.SpuStdService spuStdService) {
        this.goodsService = goodsService;
        this.categoryService = categoryService;
        this.vision = vision;
        this.spuStdService = spuStdService;
    }

    /**
     * 关掉某一路送货方式会影响的在售商品（P1）：本店货架上只勾了这一路的。
     * 端上关路前的确认框用它列清单 —— 商品不会被自动改动，所以要让商家看见名字。
     */
    @org.springframework.security.access.prepost.PreAuthorize("@perm.canBiz('" + ai.neargo.shop.auth.BizPerms.STORE + "')")
    @org.springframework.web.bind.annotation.GetMapping("/biz/stores/{storeNo}/fulfillment/{channel}/impact")
    public java.util.List<MerchantGoodsService.GoodsBrief> fulfillmentImpact(
            @org.springframework.web.bind.annotation.PathVariable String storeNo,
            @org.springframework.web.bind.annotation.PathVariable String channel) {
        return goodsService.onlyFulfillment(ai.neargo.shop.auth.BizContext.requireMerchantNo(),
                "default".equals(storeNo) ? null : storeNo, channel);
    }

    /**
     * 类目树 —— 商家编辑商品时选类目用。
     *
     * <p>与 C 端的 {@code GET /mp/category/tree} 是**同一份数据的两个入口**，
     * 而不是让 B 端去调 {@code /mp/**}：端上有前缀守卫（C 端只调 /mp、B 端只调 /biz），
     * 它挡的正是「两个端混用同一条路径」——那样将来给 C 端加个社区过滤，
     * 会连带改掉商家的类目选择器，而没有任何测试会发现。
     */
    @GetMapping("/biz/category/tree")
    public List<ai.neargo.shop.product.dto.CategoryVO> categoryTree() {
        return categoryService.tree();
    }

    /**
     * 我的商品列表。
     *
     * @param status ON_SALE / OFF_SALE / AUDITING / REJECTED；空表示全部。
     *               <b>包含下架与被驳回的</b> —— 看不到被驳回的商品，店主就不知道要改什么
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/goods")
    public PageData<GoodsVO> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String categoryNo,
                                  @RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "20") long size) {
        /*
         * `keyword` 此前**写死传 null** —— service 一直支持按标题模糊搜（见接口注释），
         * 只有这个端点没往下传，于是 B 端商品页没有搜索。
         * 商品少的时候看不出来；实测 194 条的账号，找一个商品要滚三十屏。
         */
        /*
         * `categoryNo` 此前也**写死传 null**（与 keyword 是同一种遗漏，那个已经修过）。
         * 类目变必填之后按类目找货是商家的主路径 —— 一个卖 200 件货的店，
         * 没有类目筛就只能靠滚动。
         */
        return goodsService.list(BizContext.requireMerchantNo(), categoryNo, keyword, status,
                page, Math.min(size, 50));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/goods/{goodsNo}")
    public GoodsVO detail(@PathVariable String goodsNo) {
        return goodsService.detail(BizContext.requireMerchantNo(), goodsNo);
    }

    /**
     * 新建 / 编辑。<b>保存后回到待审核并强制下架</b> —— 否则「改成别的东西再卖」能绕开审核。
     *
     * <p><b>请求体里没有 {@code type}</b>：五品类由 {@code categoryNo} 带出来
     * （见 {@code CategoryService#categoryTypeOf}）。老客户端还在发的那个值
     * 由 Jackson 当未知字段忽略，不会 400。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/goods/save")
    public GoodsVO save(@RequestBody SaveGoodsReq req) {
        return goodsService.save(BizContext.requireMerchantNo(), new MerchantGoodsService.SaveCommand(
                req.goodsNo(), req.title(), req.subtitle(),
                req.titleI18n(), req.subtitleI18n(), req.categoryNo(),
                req.cover(), req.images(),
                req.specGroups() == null ? List.of() : req.specGroups().stream()
                        .map(g -> new MerchantGoodsService.SpecGroup(
                                g.name(), g.options(), g.optionCodes(), g.templateNo()))
                        .toList(),
                req.skus() == null ? List.of() : req.skus().stream()
                        .map(s -> new MerchantGoodsService.Sku(
                                s.skuNo(), s.optionValues(), s.price(), s.priceByMarket(), s.stock(),
                                s.originPrice(), s.nominalGram(), s.costPrice()))
                        .toList(),
                req.fulfillments(),
                req.limitPerUser(),
                req.fresh() == null ? null : new MerchantGoodsService.FreshSpec(
                        req.fresh().cutoffAt(), req.fresh().arrivalDesc(),
                        req.fresh().weighed(), req.fresh().origin()),
                req.service() == null ? null : new MerchantGoodsService.ServiceSpec(
                        req.service().durationMin(), req.service().storeName()),
                req.groupBuy() == null ? null : new MerchantGoodsService.GroupBuySpec(
                        req.groupBuy().minCount(), req.groupBuy().price()),
                req.stdNo(), req.detail(), req.detailImages()));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/goods/{goodsNo}/toggle")
    public GoodsVO toggle(@PathVariable String goodsNo, @RequestBody ToggleReq req) {
        return goodsService.toggle(BizContext.requireMerchantNo(), goodsNo,
                Boolean.TRUE.equals(req.onSale()));
    }

    /** 改库存。<b>不触发重审</b> —— 补货是每天都在做的事。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/goods/{goodsNo}/stock")
    public GoodsVO stock(@PathVariable String goodsNo, @RequestBody StockReq req) {
        return goodsService.saveStock(BizContext.requireMerchantNo(), goodsNo,
                req.skuNo(), req.stock() == null ? 0 : req.stock());
    }

    /**
     * 设置**当前门店**的库存（多门店）。门店取请求头 {@code X-Store-No}，不传用默认店。
     *
     * <p>⚠️ 第一次对某个 SKU 调用它，就把这个 SKU 整体切换成「按店管理」——
     * 此后没设过库存的门店卖不出这件商品。界面上要把这句话说清楚。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/goods/{goodsNo}/store-stock")
    public GoodsVO storeStock(@PathVariable String goodsNo, @RequestBody StockReq req) {
        var ctx = BizContext.current();
        return goodsService.saveStoreStock(ctx.requireMerchantNo(), ctx.currentStoreNo(), goodsNo,
                req.skuNo(), req.stock() == null ? 0 : req.stock());
    }

    /**
     * 提交审核：草稿 → 待审（批 D）。
     *
     * <p>此前是「保存即提审」，商家填一半点保存就进了运营的待审队列。
     * 重复点击无副作用 —— 已在审的再点一次不该报错。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/goods/{goodsNo}/submit")
    public GoodsVO submit(@PathVariable String goodsNo) {
        return goodsService.submitForAudit(BizContext.requireMerchantNo(), goodsNo);
    }

    /**
     * 只改截单与到货说明（生鲜）。<b>不触发重审、不下架</b> ——
     * 生鲜商家每天晚上定明天的截单，走 {@code /save} 的话改一次等于停一天生意。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/goods/{goodsNo}/presale")
    public GoodsVO presale(@PathVariable String goodsNo, @RequestBody PresaleReq req) {
        return goodsService.savePresaleCutoff(BizContext.requireMerchantNo(), goodsNo,
                req.cutoffAt(), req.arrivalDesc());
    }

    /** @param cutoffAt 当天几点前下单（毫秒时间戳）。与「到点」是两件事，见词典 §12 */
    public record PresaleReq(Long cutoffAt, String arrivalDesc) {
    }

    /**
     * 设置**当前门店**的售价（多门店）。门店取 {@code X-Store-No}，不传用默认店。
     *
     * <p>与 {@code store-stock} 同形状，但**回退方向相反**：没设过价的门店按主体价卖，
     * 而没设过库存的门店按 0 卖。挂 {@code biz:goods} 而不是 {@code biz:stock} ——
     * 改价是定价权，与补货不是一回事。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/goods/{goodsNo}/store-price")
    public GoodsVO storePrice(@PathVariable String goodsNo, @RequestBody StorePriceReq req) {
        var ctx = BizContext.current();
        return goodsService.saveStorePrice(ctx.requireMerchantNo(), ctx.currentStoreNo(), goodsNo,
                req.skuNo(), req.price(), req.originPrice());
    }

    /** @param price 空 = 取消本店单独定价，回到主体价（合法操作，不是漏填） */
    public record StorePriceReq(String skuNo, Long price, Long originPrice) {
    }

    /**
     * 标准品搜索 —— 建品页「从标准品开始」用（TDD-标准品库）。
     *
     * <p>按标题与别名模糊匹配，只返回启用中的。<b>搜不到不是错误</b>：
     * 标准库对「张姐家的酱菜」永远无效，而那类货正是这个平台的一部分主力 ——
     * 端上必须让「搜不到 → 直接自建」这条路一样顺，不能因为多了标准品，
     * 自建品反而变成一个要先失败一次才能走到的分支。
     *
     * <p>判 {@code biz:goods}：它是建品链路的一环，只能改库存的角色用不上。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/spu-std")
    public List<ai.neargo.shop.product.dto.SpuStdVO> spuStd(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryNo,
            @RequestParam(defaultValue = "20") int limit) {
        return spuStdService.search(keyword, categoryNo, limit);
    }

    /**
     * 规格模板。<b>判 {@code biz:goods} 而不是 {@code biz:stock}</b> ——
     * 它是建品链路的一环，只能改库存的角色（配送员、客服）不该读写它。
     *
     * <p>这两条此前**一个权限注解都没有**，而同一个控制器里其余端点都判了 ——
     * 于是任何持有 B 端会话的子账号都能给这家店建规格模板。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/spec-templates")
    public List<SpecTemplateVO> specTemplates(@RequestParam(required = false) String categoryType,
                                              @RequestParam(required = false) String categoryNo) {
        return goodsService.specTemplates(BizContext.requireMerchantNo(), categoryType, categoryNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/spec-templates")
    public SpecTemplateVO saveSpecTemplate(@RequestBody SaveTemplateReq req) {
        return goodsService.saveSpecTemplate(BizContext.requireMerchantNo(), req.name(),
                req.options() == null ? List.of() : req.options().stream()
                        .map(o -> new MerchantGoodsService.SpecOption(o.code(), o.label()))
                        .toList());
    }

    /**
     * 拍照识别商品。
     *
     * <p><b>一期没有视觉模型，恒返回「没认出来」（confidence=0）。</b>
     *
     * <p>为什么保留这个接口而不是从契约里删掉：B 端拍照录商品是<b>已经做完的交互</b>，
     * 契约里写得很清楚「低于阈值时页面不预填，只提示没认出来」——
     * 也就是说这条路径本来就设计成识别失败也能继续手填。返回 0 置信度是这个设计里
     * <b>合法的一档</b>，前端会正确降级；而删掉接口会让那个页面直接 404。
     *
     * <p>接上模型时只改这一个方法。在那之前，这里不该假装认出了什么。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/goods/recognize")
    public GoodsGuessVO recognize(@RequestBody RecognizeReq req) {
        /*
         * 把**候选类目**喂给模型，而不是让它自由发挥：不给列表时它会返回
         * 「日用品」这种不存在的编号，而查无此项的 categoryNo 落进草稿之后，
         * 商家要到点保存那一刻才撞上类目校验 —— 那时他已经不记得是谁填的了。
         *
         * 只给**挂得住商品的层级**（叶子/二级），一级大类不参与：把「食品生鲜」
         * 选给一个商品没有任何信息量，却会挤掉真正该选的那一档。
         */
        /*
         * 摊平成「编号 → 中文路径」。**只收二级与三级** ——
         * 一级大类（食品生鲜/日用百货）选给一个商品没有任何信息量，
         * 却会占掉模型本该选中真正那一档的机会。
         * 叶子的 children 是空列表不是 null（见 CategoryVO 注释），所以不必判空。
         */
        var categories = new java.util.LinkedHashMap<String, String>();
        for (var lv1 : categoryService.tree()) {
            for (var lv2 : lv1.children()) {
                if (lv2.children().isEmpty()) {
                    categories.put(lv2.categoryNo(), lv1.name() + "/" + lv2.name());
                } else {
                    for (var lv3 : lv2.children()) {
                        categories.put(lv3.categoryNo(),
                                lv1.name() + "/" + lv2.name() + "/" + lv3.name());
                    }
                }
            }
        }
        var guess = vision.recognize(req.imageUrl(), categories);
        if (guess == null) {
            // 识别不出来不是错误。confidence=0 时端上只提示、不预填（见 b-app shoot()）
            return new GoodsGuessVO("", "", "NORMAL", "", 0d);
        }
        return new GoodsGuessVO(guess.title(), guess.subtitle(), guess.type(),
                guess.categoryNo(), guess.confidence());
    }

    /**
     * 按商品名与主图**生成图文详情正文**（B 端「自动生成」按钮）。
     *
     * <p>返回的永远是**草稿**：端上把它填进那个可编辑的 textarea，商家改完再保存。
     * 这里不直接落库 —— 模型不知道这家店真实的产地与保质期，
     * 一键写进商品详情等于替商家做了他没做过的承诺。
     *
     * <p>生成不出来时返回空串而不是报错：这个按钮是省事的捷径，
     * 不是必经步骤，模型不可达时商家照样能自己写。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/goods/describe")
    public DescribeVO describe(@RequestBody DescribeReq req) {
        // 类目路径喂中文名而不是编号：模型认得「食品生鲜/水果」，不认得 CAT120
        String category = "";
        if (req.categoryNo() != null && !req.categoryNo().isBlank()) {
            for (var lv1 : categoryService.tree()) {
                for (var lv2 : lv1.children()) {
                    if (lv2.categoryNo().equals(req.categoryNo())) {
                        category = lv1.name() + "/" + lv2.name();
                    }
                    for (var lv3 : lv2.children()) {
                        if (lv3.categoryNo().equals(req.categoryNo())) {
                            category = lv1.name() + "/" + lv2.name() + "/" + lv3.name();
                        }
                    }
                }
            }
        }
        String text = vision.describe(req.imageUrl(), req.title(), req.subtitle(), category);
        return new DescribeVO(text == null ? "" : text);
    }

    public record DescribeReq(String imageUrl, String title, String subtitle, String categoryNo) {
    }

    /** 空串 = 没生成出来。端上据此提示，而不是把空白填进详情框 */
    public record DescribeVO(String detail) {
    }

    /**
     * <b>没有 {@code type} 字段</b>：五品类由 {@code categoryNo} 派生（P1-1）。
     *
     * <p>留一个「收下但忽略」的字段看着更兼容，其实更糟：契约对齐守卫
     * （{@code wire-alignment.test.ts}）会因为「后端收、前端不发」一直红，
     * 而下一个人读到它会以为这个值还起作用。老客户端仍在发的那个 {@code type}
     * 由 Jackson 按未知字段忽略掉（本仓库没开 FAIL_ON_UNKNOWN_PROPERTIES），
     * 不会 400。
     */
    public record SaveGoodsReq(String goodsNo, String title, String subtitle,
                               Map<String, String> titleI18n, Map<String, String> subtitleI18n,
                               String categoryNo, String cover, List<String> images,
                               List<SpecGroupReq> specGroups, List<SkuReq> skus,
                               List<String> fulfillments,
                               Integer limitPerUser, FreshReq fresh, ServiceReq service,
                               GroupBuyReq groupBuy,
                               /**
                                * 引用的标准品；不传 = 自建品。
                                *
                                * <p>传了它，服务端会用标准品的 {@code categoryNo} 与
                                * {@code optionCode} <b>覆盖</b>请求里的值 —— 端上只是「填充」，
                                * 而填充过的表单商家能随便改。
                                */
                               String stdNo,
                               /** 图文详情正文（纯文本）。不传 = 不改，传空串 = 清空 */
                               String detail,
                               /** 详情区长图。不传 = 不改，传空数组 = 清空 */
                               List<String> detailImages) {
    }

    /** 生鲜段。留空 = 不改；只在品类是 FRESH 时写入 */
    public record FreshReq(Long cutoffAt, String arrivalDesc, Boolean weighed, String origin) {
    }

    /** 服务段。留空 = 不改；只在品类是 SERVICE 时写入 */
    public record ServiceReq(Integer durationMin, String storeName) {
    }

    /** 拼团档。两个值要么都给要么都不给 —— 缺一个开不出团，而界面上看着是配着的 */
    public record GroupBuyReq(Integer minCount, Long price) {
    }

    public record SpecGroupReq(String name, List<String> options, List<String> optionCodes,
                               String templateNo) {
    }

    /** @param originPrice 划线价；@param nominalGram 标称重量（克）。两者都是「留空 = 不改」 */
    public record SkuReq(String skuNo, List<String> optionValues, long price,
                         Map<String, Long> priceByMarket, int stock,
                         Long originPrice, Integer nominalGram,
                         /** 成本价（最小货币单位）。不传 = 不改，&lt;= 0 = 清空 */
                         Long costPrice) {
    }

    public record ToggleReq(Boolean onSale) {
    }

    public record StockReq(String skuNo, Integer stock) {
    }

    public record SaveTemplateReq(String name, List<OptionReq> options) {
    }

    public record OptionReq(String code, String label) {
    }

    public record RecognizeReq(String imageUrl) {
    }

    /** 对齐 b-app {@code GoodsGuess}。全部是建议值，店主可改可弃。 */
    /**
     * @param subtitle   一句话卖点。**建议值**，店主可改可弃
     * @param categoryNo 类目编号。已按候选表校验过 —— 模型给的编号不在表里时是空串，
     *                   不会把一个查无此项的编号塞进草稿（那样商家要到保存那刻才撞上校验）
     */
    public record GoodsGuessVO(String title, String subtitle, String type,
                               String categoryNo, double confidence) {
    }
}
