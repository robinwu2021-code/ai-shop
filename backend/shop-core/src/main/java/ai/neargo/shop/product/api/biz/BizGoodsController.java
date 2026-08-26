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
    /** 规格库：商家自定义规格落在这里（V195 的 MERCHANT 覆盖层） */
    private final ai.neargo.shop.product.service.SpecLibraryService specLibrary;
    private final ai.neargo.shop.product.service.CategoryService categoryService;
    private final ai.neargo.shop.spi.product.GoodsVisionPort vision;
    private final ai.neargo.shop.product.service.SpuStdService spuStdService;

    public BizGoodsController(MerchantGoodsService goodsService,
                              ai.neargo.shop.product.service.CategoryService categoryService,
                              ai.neargo.shop.spi.product.GoodsVisionPort vision,
                              ai.neargo.shop.product.service.SpuStdService spuStdService,
                              ai.neargo.shop.product.service.SpecLibraryService specLibrary) {
        this.specLibrary = specLibrary;
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
                                s.originPrice(), s.nominalGram(), s.costPrice(),
                                s.barcode(), s.merchantSkuCode(), s.saleUnit()))
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
                req.stdNo(), req.detail(), req.detailImages(),
                req.params() == null ? null : req.params().stream()
                        .map(x -> new MerchantGoodsService.GoodsParam(
                                x.dimNo(), x.name(), x.valueNo(), x.code(), x.label()))
                        .toList()));
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

    /**
     * 这一类的**商品参数**（产地、保质期、材质…）。
     *
     * <p>与 {@code /biz/spec-templates} 分成两条端点，而不是加个 usage 参数：
     * 它们在界面上是两块、语义也不同（一个分 SKU 一个不分），
     * 合成一条的话端上每次都要先过滤一遍，而漏过滤的后果是
     * 「产地」被当成规格建出来 —— 那正是这一期要消灭的东西。
     */
    /**
     * 能加进这一类的**商品参数**候选（本类目已配 + 平台通用 + 自建）。
     *
     * <p>与 {@code /biz/spec-props} 的差别是视角：那个回答「这一类有哪些参数」，
     * 这个回答「还能加哪些」—— 与销售规格那侧的 spec-templates / spec-dims 同一对关系。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/pickable-props")
    public List<SpecTemplateVO> pickableProps(@RequestParam(required = false) String categoryNo) {
        return specLibrary.pickableProps(BizContext.requireMerchantNo(), categoryNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/spec-props")
    public List<SpecTemplateVO> specProps(@RequestParam(required = false) String categoryNo) {
        return specLibrary.propsForCategory(BizContext.requireMerchantNo(), categoryNo);
    }

    /**
     * 在某个维度下加一个<b>自己的</b>规格值：「我这袋是 750g，平台没这一档」。
     *
     * <p>它挂在<b>平台维度</b>上，所以与平台值天然同轴 —— 于是「谁家 750g 的米更便宜」
     * 这种问题第一次成立。撞上平台已有的那一档时不新建，直接把那一档返回给他。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/spec-values")
    public AddSpecValueResp addSpecValue(@RequestBody AddSpecValueReq req) {
        var v = specLibrary.addMerchantValue(BizContext.requireMerchantNo(), req.dimNo(),
                req.label(), req.numericValue());
        /*
         * **只回端上真用得着的三样。**把 ops 那个胖 VO（scope/entityNo/sort/status/
         * merchantCount…）原样发给商家端，等于让契约去接一堆它永远不读的字段 ——
         * 而契约守卫数的正是这个差集。
         */
        return new AddSpecValueResp(v.valueNo(), v.code(), v.label());
    }

    /**
     * 「加一个规格组」能挑的维度：本类目已配的 → 平台通用 → 这家店自建的。
     *
     * <p>与 {@code /biz/spec-templates} 的差别是范围：那个只给本类目配好的几条
     * （选完类目自动预填用它），这个多给平台通用维度 —— 让商家<b>先看后挑</b>，
     * 而不是像从前那样对着一个空输入框凭记忆敲维度名。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/spec-dims")
    public List<ai.neargo.shop.product.dto.SpecTemplateVO> pickableDims(
            @RequestParam(required = false) String categoryNo) {
        return specLibrary.pickableDims(BizContext.requireMerchantNo(), categoryNo);
    }

    /**
     * 「我的规格」：这家店自己建的维度 + 用量 + 配额。
     *
     * <p>此前商家<b>只能建、不能管</b> —— 建品页里输一个名字就落进规格库，
     * 之后没有任何地方看得到它。建错了（打错字、想换个叫法）只能一直留着，
     * 还占着配额，而配额用完那句报错也说不清是被什么占了。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/my-spec-dims")
    public List<ai.neargo.shop.product.service.SpecLibraryService.MerchantDimVO> myDims() {
        return specLibrary.myDims(BizContext.requireMerchantNo());
    }

    /**
     * 这家店按**货架类目**能用到的规格 —— 「我的规格」那一页的主体。
     *
     * <p>不给平台那 13 个通用维度：一家只卖蔬菜和肉的店看到「尺码」「口径」「时长」
     * 是纯噪音，而噪音会让他觉得这一页与自己无关。按他真正摆出来的类目给，
     * 每一行他都认得。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/store-spec-dims")
    public List<ai.neargo.shop.product.service.SpecLibraryService.StoreCategorySpecVO> dimsByStore(
            @RequestParam(required = false) String storeNo) {
        return specLibrary.dimsByStore(BizContext.requireMerchantNo(),
                storeNo == null || storeNo.isBlank() ? BizContext.current().currentStoreNo() : storeNo);
    }

    /**
     * 某个维度下平台有的全部档位 —— 给「＋」那个弹框做候选。
     *
     * <p>类目通常只裁了其中几档，而商家要加的往往正是没裁进来的那一档。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/spec-dims/{dimNo}/values")
    public List<ai.neargo.shop.product.dto.SpecTemplateVO.Option> valuesOfDim(
            @PathVariable String dimNo) {
        return specLibrary.valuesOfDim(BizContext.requireMerchantNo(), dimNo);
    }

    /**
     * 保存本店对某个类目规格的覆盖：**用哪几个、什么顺序、叫什么**。
     *
     * <p>改名只改展示（dimNo 不变），所以跨店比价照常成立。
     * 传空数组 = 清掉覆盖、完全跟平台走。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/spec-override/{categoryNo}")
    public List<ai.neargo.shop.product.dto.SpecTemplateVO> saveOverrides(
            @PathVariable String categoryNo, @RequestBody SpecOverrideReq req) {
        String merchantNo = BizContext.requireMerchantNo();
        specLibrary.saveOverrides(merchantNo, categoryNo,
                req.dims() == null ? List.of() : req.dims().stream()
                        .map(d -> new ai.neargo.shop.product.service.SpecLibraryService.OverrideCommand(
                                d.dimNo(), !Boolean.FALSE.equals(d.enabled()), d.label(),
                                d.values() == null ? List.of() : d.values().stream()
                                        .map(v -> new ai.neargo.shop.product.service.SpecLibraryService
                                                .ValueOverrideCommand(v.code(),
                                                !Boolean.FALSE.equals(v.enabled())))
                                        .toList()))
                        .toList());
        // 回最新的合并结果：端上照它重渲染，省一次往返，也免得两边各算一遍合并规则
        return specLibrary.templatesForCategory(merchantNo, categoryNo);
    }

    public record SpecOverrideReq(List<DimOverrideReq> dims) {
    }

    public record DimOverrideReq(String dimNo, Boolean enabled, String label,
                                 List<ValueOverrideReq> values) {
    }

    public record ValueOverrideReq(String code, Boolean enabled) {
    }

    /** 改名。**不影响已建商品** —— 商品存的是规格快照 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/my-spec-dims/{dimNo}/rename")
    public ai.neargo.shop.product.service.SpecLibraryService.SpecDimVO renameMyDim(
            @PathVariable String dimNo, @RequestBody RenameDimReq req) {
        return specLibrary.renameMerchantDim(BizContext.requireMerchantNo(), dimNo, req.name());
    }

    /**
     * 停用 / 启用。**停用不是删除** —— 历史商品的规格组要靠它解释自己是什么，
     * 真删之后那些规格就成了没有出处的字符串。停用后只是建品时挑不到。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/my-spec-dims/{dimNo}/archive")
    public ai.neargo.shop.product.service.SpecLibraryService.SpecDimVO archiveMyDim(
            @PathVariable String dimNo, @RequestBody ArchiveDimReq req) {
        return specLibrary.archiveMerchantDim(BizContext.requireMerchantNo(), dimNo,
                Boolean.TRUE.equals(req.archived()));
    }

    public record RenameDimReq(String name) {
    }

    public record ArchiveDimReq(Boolean archived) {
    }

    /**
     * 自建一个规格维度（平台没有的，如「辣度」）。
     *
     * <p><b>只在这家店可见，不参与跨店聚合</b> —— 端上要把这句话说给商家听。
     * 与平台维度重名时直接返回平台那个：他要的是「按这个维度分规格」，
     * 而不是拥有一个自己的颜色维度 —— 后者只会让他的货从聚合里掉出去。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/spec-dims")
    public ai.neargo.shop.product.dto.SpecTemplateVO addSpecDim(@RequestBody AddSpecDimReq req) {
        var d = specLibrary.addMerchantDim(BizContext.requireMerchantNo(),
                req.name(), req.labels(), req.usageType());
        /*
         * 回**规格模板**的形状而不是规格库的胖 VO：端上拿到它就往规格组里塞，
         * 与「套用模板」走的是同一段代码 —— 两种形状会让那段代码分叉。
         */
        return new ai.neargo.shop.product.dto.SpecTemplateVO(d.dimNo(),
                ai.neargo.shop.product.entity.PrdSpecDim.MERCHANT, null, null, d.name(),
                d.values().stream()
                        .map(v -> new ai.neargo.shop.product.dto.SpecTemplateVO.Option(v.code(), v.label()))
                        .toList(),
                // 自建维度不是主维度：主维度是类目绑定上的判据，商家自建的没有绑定
                BizContext.requireMerchantNo(), false);
    }

    /** @param code 平台值有码，自有值暂时没有（提升为平台值时才发） */
    public record AddSpecValueResp(String valueNo, String code, String label) {
    }

    public record AddSpecValueReq(String dimNo, String label, java.math.BigDecimal numericValue) {
    }

    /** @param labels 首批取值，可为空 —— 建完维度再一个个加也行 */
    /**
     * @param usageType {@code SALE}（默认，销售规格）/ {@code PROP}（商品参数）。
     *                  不传按 SALE —— 老客户端建的一直是销售规格，行为不变。
     */
    public record AddSpecDimReq(String name, List<String> labels, String usageType) {
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
                               List<String> detailImages,
                               /** 商品参数（产地/保质期/材质…）。不传 = 不改，传空数组 = 清空 */
                               List<GoodsParamReq> params) {
    }

    /** 一条商品参数。量纲型（功率、净重）平台不枚举值，那时只有 label */
    /**
     * @param name 维度名（「产地」）。**端上原样带上来当快照存** ——
     *             与 specGroups 里的组名同一口径：商家事后改本店叫法，
     *             已经建好的商品不该跟着变。
     */
    public record GoodsParamReq(String dimNo, String name, String valueNo, String code, String label) {
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
                         Long costPrice,
                         /**
                          * 商品条码 EAN-13 / UPC。<b>不传 = 不改，传空串 = 清空</b>。
                          * 与 ERP、收银秤、供应商发货单的通用键 —— 平台生成的 skuNo 它们都不认识。
                          */
                         String barcode,
                         /** 商家自有货号。他 ERP 里的主键，在他自己的命名空间里唯一 */
                         String merchantSkuCode,
                         /** 计量单位（件/斤/kg/份）。称重品与计件品的分界 */
                         String saleUnit) {
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
