package ai.neargo.shop.product.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdGoodsDraft;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.entity.PrdSpecTemplate;
import ai.neargo.shop.product.mapper.ProductMappers.CommunityPoolMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecTemplateMapper;
import ai.neargo.shop.product.service.GoodsService;
import ai.neargo.shop.product.service.MerchantGoodsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link MerchantGoodsService} 实现。 */
@Service
public class MerchantGoodsServiceImpl implements MerchantGoodsService {

    private static final Logger log = LoggerFactory.getLogger(MerchantGoodsServiceImpl.class);

    /**
     * 类目资质闸门是否**真的拦人**。默认 {@code false} —— 只展示、不限制。
     *
     * <p>为什么默认关：受理入口（B 端传证 + 运营按证授码）刚铺，存量商家的授权码
     * 还没补齐。这时候闸门拦住的不是无证经营，是平台自己还没建好的那条路 ——
     * 线上 379 件商品里 267 件落在带门槛的类目下，闸门开着等于让它们全都上不了架。
     *
     * <p>关掉的只是「拦」，不是「判」：判据照跑，命中时打一条 WARN，
     * 那是开闸前估影响面的唯一依据。要开就把 {@code SHOP_CATEGORY_GATE_ENFORCE}
     * 设成 true，**同时**把 b-app 的 {@code ENFORCE_CATEGORY_GATE} 打开 ——
     * 两边不同步的话，端上要么拦一个后端会放的，要么放一个后端会拒的。
     */
    /**
     * 类目资质校验是否真的拦人。
     *
     * <p><b>从 @Value 换成读 sys_config</b>（V209）：配置项要重启才生效，而这是一个
     * 运营决定 —— 「暂时别拦」不该变成一次发版。同一个开关也管着门店摆货架那条路
     * （StoreCategoryServiceImpl），此前那条不受任何开关控制，一直在拦。
     */
    private boolean gateEnforced() {
        return switchPort.bool("category.gate.enforce", false);
    }

    private static final String AUDITING = "AUDITING";
    /** 草稿：**不进待审队列**，也上不了架。批 D 之前不存在这个状态，保存即提审 */
    private static final String DRAFT = "DRAFT";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";
    /** 一期只做单市场（CN）；priceByMarket 里的其余市场各写一行 SKU。 */
    private static final String HOME_MARKET = "CN";

    /** 市场主数据。**只用来校验 market 取值**，见 marketsOf 的注释 */
    private final ai.neargo.shop.spi.pay.MarketPort marketPort;

    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;
    private final SpecTemplateMapper templateMapper;
    private final GoodsService goodsService;
    private final CommunityPoolMapper poolMapper;
    private final ai.neargo.shop.spi.user.MerchantQueryPort merchantPort;
    /** 建池时算「哪家店离这个社区最近」要它给社区坐标 */
    private final ai.neargo.shop.spi.user.CommunityQueryPort communityQueryPort;
    /** 平台开关（V209）—— 类目闸门开不开由运营在界面上定，不再是一条配置 */
    private final ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort;
    /** 禁售词。走 SPI 不直连 platform 的表 —— product 与 platform 是兄弟模块 */
    private final ai.neargo.shop.spi.platform.BannedWordPort bannedWords;
    private final ai.neargo.shop.product.mapper.ProductMappers.GoodsDraftMapper draftMapper;

    /**
     * 「正在换版」的重入标志。发布/过审换版要把草稿**原样走一遍 save() 全链路**
     * （applyStd 的权威收敛、字段应用、saveSkus、门店行 —— 少任何一段都会漂移），
     * 而 save() 对在售商品会转草稿分支、对提审会置 AUDITING —— 换版线程内要跳过这两段。
     * 用 ThreadLocal 而不是把 2500 行的 save() 拆两半：拆法属于控制器粒度那类
     * 单独重构，不该夹在双版本这一步里。
     */
    private static final ThreadLocal<Boolean> PUBLISHING = new ThreadLocal<>();
    private final ai.neargo.shop.spi.user.AdmissionPort admissionPort;
    private final ObjectMapper json;

    private final ai.neargo.shop.product.service.CategoryService categoryService;
    /** 标准品：引用建品时用它把类目与 optionCode 拉回权威值 */
    private final ai.neargo.shop.product.service.SpuStdService spuStdService;
    /** 门店级库存。**只有商家显式设置过才有行** —— 见 saveStoreStock 的说明 */
    private final ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper storeStockMapper;
    /**
     * 改库存走它，不自己 update。
     *
     * <p>商家有两个改库存的入口（这一页与库存页），收进 Port 之后
     * <b>真相源在哪它就落到哪</b> —— 入口可以有两个，账只能有一本。
     */
    private final ai.neargo.shop.spi.product.StockPort stockPort;
    /** 门店级上架关系。与库存同一套「有行按店算、无行回退主体」的语义 */
    private final ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper storeGoodsMapper;
    /** 门店货架。商品域只用它回答两个问题：本店有没有这一类、把这一类加进去 */
    private final ai.neargo.shop.spi.user.StoreCategoryPort storeCategoryPort;
    /** 建成 SKU 之后往外发一条 —— 进销存靠它把这个 SKU 放上账。见 ProductEvents.SkuUpserted */
    private final ai.neargo.shop.event.OutboxEventBus events;
    /** 门店级售价。**无行回退主体价**（与库存的「无行视为 0」相反，见 PrdStorePrice） */
    private final ai.neargo.shop.product.mapper.ProductMappers.StorePriceMapper storePriceMapper;
    /** 规格库（V195）：类目级规格从这里来，SKU 的值编号也靠它反查 */
    private final ai.neargo.shop.product.service.SpecLibraryService specLibrary;

    public MerchantGoodsServiceImpl(ai.neargo.shop.spi.product.StockPort stockPort,
                                    GoodsMapper goodsMapper, SkuMapper skuMapper,
                                    SpecTemplateMapper templateMapper,
                                    GoodsService goodsService, CommunityPoolMapper poolMapper,
                                    ai.neargo.shop.spi.user.MerchantQueryPort merchantPort,
                                    ai.neargo.shop.spi.user.CommunityQueryPort communityQueryPort,
                                    ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort,
                                    ai.neargo.shop.spi.platform.BannedWordPort bannedWords,
                                    ai.neargo.shop.product.mapper.ProductMappers.GoodsDraftMapper draftMapper,
                                    ai.neargo.shop.spi.user.AdmissionPort admissionPort,
                                    ai.neargo.shop.product.service.CategoryService categoryService,
                                    ai.neargo.shop.product.service.SpuStdService spuStdService,
                                    ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper storeStockMapper,
                                    ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper storeGoodsMapper,
                                    ai.neargo.shop.spi.user.StoreCategoryPort storeCategoryPort,
                                    ai.neargo.shop.product.mapper.ProductMappers.StorePriceMapper storePriceMapper,
                                    ai.neargo.shop.product.service.SpecLibraryService specLibrary,
                                    ai.neargo.shop.event.OutboxEventBus events,
                                    ai.neargo.shop.spi.pay.MarketPort marketPort,
                                    ObjectMapper json) {
        this.marketPort = marketPort;
        this.specLibrary = specLibrary;
        this.storePriceMapper = storePriceMapper;
        this.storeCategoryPort = storeCategoryPort;
        this.events = events;
        this.storeStockMapper = storeStockMapper;
        this.storeGoodsMapper = storeGoodsMapper;
        this.categoryService = categoryService;
        this.spuStdService = spuStdService;
        this.poolMapper = poolMapper;
        this.merchantPort = merchantPort;
        this.communityQueryPort = communityQueryPort;
        this.switchPort = switchPort;
        this.bannedWords = bannedWords;
        this.draftMapper = draftMapper;
        this.admissionPort = admissionPort;
        this.stockPort = stockPort;
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.templateMapper = templateMapper;
        this.goodsService = goodsService;
        this.json = json;
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public List<GoodsBrief> onlyFulfillment(String merchantNo, String storeNo, String channel) {
        if (merchantNo == null || channel == null || channel.isBlank()) {
            return List.of();
        }
        List<PrdGoods> goods = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getEntityNo, merchantNo)
                        .eq(PrdGoods::getOnSale, true)
                        .orderByDesc(PrdGoods::getId)));
        if (goods.isEmpty()) {
            return List.of();
        }
        // 货架：有本店行且下架的不算（那件货在这家店本来就没卖）
        java.util.Set<String> offAtStore = new java.util.HashSet<>();
        if (storeNo != null && !storeNo.isBlank()) {
            DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.selectList(
                            Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                                    .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo, storeNo)
                                    .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getOnSale, false)))
                    .forEach(r -> offAtStore.add(r.getGoodsNo()));
        }
        java.util.Set<String> merchantConfigurable = java.util.Set.of(
                ai.neargo.shop.common.Fulfillments.STORE_PICKUP, ai.neargo.shop.common.Fulfillments.NEIGHBOR_PICKUP,
                ai.neargo.shop.common.Fulfillments.MERCHANT_DELIVERY, ai.neargo.shop.common.Fulfillments.EXPRESS);
        List<GoodsBrief> out = new ArrayList<>();
        for (PrdGoods g : goods) {
            if (offAtStore.contains(g.getGoodsNo())) {
                continue;
            }
            // 只看商家可配的四路：服务类两值是商品属性，不受店铺开关影响
            List<String> ways = readList(g.getFulfillments()).stream().filter(merchantConfigurable::contains).toList();
            if (ways.size() == 1 && ways.get(0).equals(channel)) {
                out.add(new GoodsBrief(g.getGoodsNo(), g.getTitle()));
            }
        }
        return out;
    }

    @Override
    public PageData<GoodsVO> list(String merchantNo, String categoryNo, String keyword, String status,
                                  String storeNo, long page, long size) {
        /*
         * merchantNo 为空 = **跨商家查**，给平台审核队列/商品池用。
         * 不做这个判空的话 MyBatis-Plus 会生成 `entity_no = null`，一行都查不到 ——
         * 而平台侧看到的是「没有待审商品」，与「审完了」长得一模一样。
         */
        var w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(merchantNo != null && !merchantNo.isBlank(), PrdGoods::getEntityNo, merchantNo)
                .eq(categoryNo != null && !categoryNo.isBlank(), PrdGoods::getCategoryNo, categoryNo)
                .like(keyword != null && !keyword.isBlank(), PrdGoods::getTitle, keyword);
        /*
         * 「缺货」是**第五个筛**（B-4.1 写了三筛：在售/下架/缺货，而代码里一直只有四态
         * 都不含它）。它落不到 prd_goods 的任何一列上 —— 库存在 SKU 上 ——
         * 所以先把缺货的 goodsNo 圈出来再拼进 IN。
         */
        if (OUT_OF_STOCK.equals(status)) {
            List<String> nos = outOfStockGoodsNos(merchantNo);
            if (nos.isEmpty()) {
                return PageData.empty(page, size);
            }
            w.in(PrdGoods::getGoodsNo, nos);
        }
        applyStatus(w, status);
        // 新建的排在前面：店主刚录完一件商品，第一件事是看它在不在
        w.orderByDesc(PrdGoods::getId);
        /*
         * **豁免数据域是必须的，不是没做**：这个方法与 B 端商家商品列表共用
         * （BizGoodsController#list），而 B 端会话的维度是 SELF ——
         * prd_goods 只有 MERCHANT 锚点，接上就是 1=0，商家的商品列表当场全空。
         *
         * 运营端的待审队列已经拆到 auditQueue()，那一条**是接数据域的**。
         */
        Page<PrdGoods> p = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectPage(Page.of(page, size), w));
        return PageData.of(toVOs(p.getRecords(), storeNo), p.getTotal(), page, size);
    }

    /** 对外的「缺货」筛选值。库里没有这个状态，它是按 SKU 可用量算出来的 */
    private static final String OUT_OF_STOCK = "OUT_OF_STOCK";

    /**
     * 缺货商品：<b>所有 SKU 的可用量（stock − locked）都 ≤ 0</b>。
     *
     * <p>「有一个规格缺货」不算缺货 —— 那件商品照样卖得出去，把它列进来会让
     * 一个只是某个规格断货的店看到一屏「缺货」，而店主没有可做的动作。
     *
     * <p>⚠️ 口径按**主体总量**，不看门店库存。已转店级管理的 SKU 在某家店可能是 0
     * 而主体还有货 —— 那属于「这家店没配」，与缺货是两件事（同
     * {@code outOfStockCountByStore} 的注释）。真要按店筛，得先决定
     * 「没配过的店算不算这件商品的经营范围」。
     */
    private List<String> outOfStockGoodsNos(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return List.of();
        }
        List<PrdSku> rows = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getEntityNo, merchantNo)
                        .eq(PrdSku::getMarket, HOME_MARKET)));
        Map<String, Boolean> anyInStock = new LinkedHashMap<>();
        for (PrdSku s : rows) {
            boolean has = nz(s.getStock()) - nz(s.getLockedStock()) > 0;
            anyInStock.merge(s.getGoodsNo(), has, (a, b) -> a || b);
        }
        return anyInStock.entrySet().stream().filter(e -> !e.getValue())
                .map(Map.Entry::getKey).toList();
    }

    /**
     * 一批商品行 → VO。<b>批量组装，不逐行 detail()</b>。
     *
     * <p>这里原先是 {@code .map(this::toVO)}，而 toVO 每行都要
     * {@code goodsService.detail()} 重新查一遍商品、SKU、商家、限时特价，
     * 再加上门店库存投影 —— 一页 20 条接近 100 次往返。同一个类里的
     * {@code listForOps} 一直是批量写法。
     */
    private List<GoodsVO> toVOs(List<PrdGoods> rows) {
        return toVOs(rows, null);
    }

    /**
     * @param storeNo 非空时每行带上「本店上不上架」。判据复用
     *                {@link #loadStoreProjection} —— 与运营端的门店商品投影**同一套语义**，
     *                不另写一份：两处各算一遍迟早出现「B 端说本店在售、运营端说未上架」，
     *                而两个看起来都对。
     */
    private List<GoodsVO> toVOs(List<PrdGoods> rows, String storeNo) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> goodsNos = rows.stream().map(PrdGoods::getGoodsNo).toList();
        Map<String, GoodsVO> base = goodsService.detailAll(goodsNos);
        StoreProjection proj = storeNo == null || storeNo.isBlank()
                ? null : loadStoreProjection(storeNo, goodsNos, List.of());
        return rows.stream()
                .map(g -> {
                    GoodsVO b = base.get(g.getGoodsNo());
                    if (b == null) {
                        return null;
                    }
                    // 一条店级行都没有 → null（未按店管理，跟随主体级），不是 false
                    Boolean storeOnSale = proj == null || !proj.managedGoods().contains(g.getGoodsNo())
                            ? null : proj.onSaleAtStore().contains(g.getGoodsNo());
                    return merchantView(g, b, storeOnSale);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public PageData<GoodsVO> auditQueue(long page, long size) {
        /*
         * ★ 这里**没有** executeWithoutScope —— 批③ 的核心就是这一行的缺席：
         * 配了商家域的审核员只看到自己负责那几家的待审商品。
         * 会话不带任何维度（超管 / ALL）时 DataScopeHandler 直接放行，与今天一致。
         */
        var w = Wrappers.<PrdGoods>lambdaQuery();
        applyStatus(w, "PENDING");
        w.orderByDesc(PrdGoods::getId);
        Page<PrdGoods> p = goodsMapper.selectPage(Page.of(page, size), w);
        return PageData.of(toVOs(p.getRecords()), p.getTotal(), page, size);
    }

    @Override
    public PageData<ai.neargo.shop.product.dto.OpsGoodsListVO> listForOps(
            String merchantNo, String categoryNo, String keyword, String status,
            String storeNo, long page, long size) {
        /*
         * 门店投影（P-11.2.1e）：带 storeNo 时查询范围**强制收敛到该店的主体**——
         * 商品挂主体不挂门店（ADR-011 双键模型），「门店的商品」= 主体商品池 ×
         * 该店库存/在售的投影，不是给商品加 store_no。
         */
        if (storeNo != null && !storeNo.isBlank()) {
            String entityNo = merchantPort.entityOfStores(List.of(storeNo)).get(storeNo);
            if (entityNo == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            merchantNo = entityNo;
        }
        var w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(merchantNo != null && !merchantNo.isBlank(), PrdGoods::getEntityNo, merchantNo)
                .eq(categoryNo != null && !categoryNo.isBlank(), PrdGoods::getCategoryNo, categoryNo)
                .like(keyword != null && !keyword.isBlank(), PrdGoods::getTitle, keyword);
        applyStatus(w, status);
        w.orderByDesc(PrdGoods::getId);
        /*
         * ★ 接数据域（批③）：这个方法**只有运营调**（GET /ops/goods），
         * 配了商家域的运营只看到自己负责那几家的商品。
         * 上面按 storeNo 收敛出的 merchantNo 是**用户给的过滤条件**，
         * 与数据域是两件事 —— 传一个不属于自己域的 storeNo，过滤条件成立而数据域拒绝，
         * 结果是空列表，正确。
         */
        Page<PrdGoods> p = goodsMapper.selectPage(Page.of(page, size), w);
        if (p.getRecords().isEmpty()) {
            return PageData.empty(page, size);
        }

        List<String> goodsNos = p.getRecords().stream().map(PrdGoods::getGoodsNo).toList();
        /*
         * **不按 market 过滤**——与 GoodsServiceImpl.loadSkus() 的关键差别。那边只要 CN
         * 一行给买家看；这里要把同一个 skuNo 在各市场的行都捞出来，按 skuNo 分组、
         * 组内再按 market 摊成一张价格表，运营才看得出"这件商品缺了哪个市场的价"。
         */
        Map<String, List<PrdSku>> skusByGoods = skuMapper.selectList(
                        Wrappers.<PrdSku>lambdaQuery().in(PrdSku::getGoodsNo, goodsNos)).stream()
                .collect(java.util.stream.Collectors.groupingBy(PrdSku::getGoodsNo));

        Set<String> merchantNos = p.getRecords().stream().map(PrdGoods::getEntityNo).collect(java.util.stream.Collectors.toSet());
        Map<String, ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief> merchants = merchantPort.findAll(merchantNos);

        // 类目名批量拼——prd_goods 只存 categoryNo，名字要跟类目表对一遍。总量有限，一次性取全表比按需查 N 次划算
        Map<String, String> categoryNames = categoryService.list(null, null, true).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ai.neargo.shop.product.dto.OpsCategoryVO::categoryNo,
                        ai.neargo.shop.product.dto.OpsCategoryVO::name));

        StoreProjection projection = storeNo == null || storeNo.isBlank()
                ? null
                : loadStoreProjection(storeNo, goodsNos,
                        skusByGoods.values().stream().flatMap(List::stream).map(PrdSku::getSkuNo).distinct().toList());

        List<ai.neargo.shop.product.dto.OpsGoodsListVO> rows = p.getRecords().stream()
                .map(g -> toOpsListVO(g, skusByGoods.getOrDefault(g.getGoodsNo(), List.of()),
                        merchants, categoryNames, projection))
                .toList();
        return PageData.of(rows, p.getTotal(), page, size);
    }

    /**
     * 门店投影的两张覆盖表，语义都是「有任意行即按店管理，没有行的店视为 0 / 未上架」。
     *
     * @param managedGoods   已转店级管理的商品（任意门店有行）
     * @param onSaleAtStore  该店在售的商品
     * @param managedSkus    已启用分店库存的 SKU（任意门店有行）
     * @param stockAtStore   该店的可用库存（stock - locked）
     */
    private record StoreProjection(Set<String> managedGoods, Set<String> onSaleAtStore,
                                   Set<String> managedSkus, Map<String, Integer> stockAtStore) {
    }

    private StoreProjection loadStoreProjection(String storeNo, List<String> goodsNos, List<String> skuNos) {
        List<ai.neargo.shop.product.entity.PrdStoreGoods> goodsRows = DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectList(Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                        .in(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, goodsNos)));
        Set<String> managedGoods = goodsRows.stream()
                .map(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> onSaleAtStore = goodsRows.stream()
                .filter(r -> storeNo.equals(r.getStoreNo()) && Boolean.TRUE.equals(r.getOnSale()))
                .map(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo)
                .collect(java.util.stream.Collectors.toSet());

        List<PrdStoreStock> stockRows = skuNos.isEmpty() ? List.of()
                : DataScopeContext.executeWithoutScope(() ->
                        storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                                .in(PrdStoreStock::getSkuNo, skuNos)));
        Set<String> managedSkus = stockRows.stream().map(PrdStoreStock::getSkuNo)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> stockAtStore = stockRows.stream()
                .filter(r -> storeNo.equals(r.getStoreNo()))
                .collect(java.util.stream.Collectors.toMap(PrdStoreStock::getSkuNo,
                        r -> Math.max(nz(r.getStock()) - nz(r.getLockedStock()), 0), (a, b) -> a));
        return new StoreProjection(managedGoods, onSaleAtStore, managedSkus, stockAtStore);
    }

    private ai.neargo.shop.product.dto.OpsGoodsListVO toOpsListVO(
            PrdGoods g, List<PrdSku> skus,
            Map<String, ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief> merchants,
            Map<String, String> categoryNames,
            StoreProjection projection) {
        Map<String, String> titleI18n = readMap(g.getTitleI18n());
        var merchant = merchants.get(g.getEntityNo());

        Map<String, List<PrdSku>> byLogicalSku = skus.stream()
                .collect(java.util.stream.Collectors.groupingBy(PrdSku::getSkuNo, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<ai.neargo.shop.product.dto.OpsGoodsListVO.OpsSkuVO> skuVOs = byLogicalSku.values().stream()
                .map(rows -> {
                    PrdSku any = rows.get(0);
                    Map<String, Long> prices = rows.stream()
                            .filter(r -> r.getPrice() != null)
                            .collect(java.util.stream.Collectors.toMap(PrdSku::getMarket, PrdSku::getPrice, (a, b) -> a));
                    Integer storeStock = projection == null || !projection.managedSkus().contains(any.getSkuNo())
                            ? null
                            : projection.stockAtStore().getOrDefault(any.getSkuNo(), 0);
                    return new ai.neargo.shop.product.dto.OpsGoodsListVO.OpsSkuVO(
                            any.getSkuNo(), readList(any.getOptionValues()), any.getSpec(),
                            prices, any.getStock() == null ? 0 : any.getStock(), storeStock);
                })
                .toList();

        Boolean storeOnSale = projection == null || !projection.managedGoods().contains(g.getGoodsNo())
                ? null
                : projection.onSaleAtStore().contains(g.getGoodsNo());

        return new ai.neargo.shop.product.dto.OpsGoodsListVO(
                g.getGoodsNo(),
                new ai.neargo.shop.product.dto.OpsGoodsListVO.TitleVO(g.getTitle(), titleI18n.get("en"), titleI18n.get("ar")),
                g.getCover(), g.getEntityNo(), merchant == null ? g.getEntityNo() : merchant.merchantName(),
                g.getCategoryNo(), g.getCategoryNo() == null ? null : categoryNames.get(g.getCategoryNo()),
                opsStatusOf(g), skuVOs, storeOnSale);
    }

    /**
     * {@code PENDING/ON_SALE/OFF_SALE/REJECTED} → 商家侧那四个状态码，和 GoodsVO.status 同一套口径。
     * <b>待审对外叫 PENDING</b>（词典 §11），库里那列仍是 AUDITING。
     *
     * <p><b>故意不复用同名的 {@link #statusOf}</b>：那个按"当前门店"算在售与否
     * （读 {@code BizContext.currentStoreNo()}），运营端跨商家浏览没有"当前门店"这个概念——
     * 这里只看 {@code prd_goods.on_sale} 这个主体级字段，多门店的细分展示留给以后真要做门店维度筛选时再加。
     */
    private String opsStatusOf(PrdGoods g) {
        // 草稿对运营原样下发：他要能一眼认出「这不是等我审的」
        if (DRAFT.equals(g.getAuditStatus())) {
            return DRAFT;
        }
        if (AUDITING.equals(g.getAuditStatus())) {
            return AUDITING;
        }
        if (REJECTED.equals(g.getAuditStatus())) {
            return REJECTED;
        }
        return Boolean.TRUE.equals(g.getOnSale()) ? "ON_SALE" : "OFF_SALE";
    }

    private Map<String, String> readMap(String json1) {
        if (json1 == null || json1.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(json1, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 商品参数 JSON → VO。读不动就当没有 —— 一条脏数据不该让整个商品详情 500 */
    private List<GoodsVO.GoodsParamVO> readParams(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<GoodsVO.GoodsParamVO>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public GoodsVO detail(String merchantNo, String goodsNo) {
        return toVO(mine(merchantNo, goodsNo));
    }

    // ---------------------------------------------------------------- 保存

    @Override
    @Transactional
    public GoodsVO save(String merchantNo, SaveCommand cmd) {
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (cmd.skus() == null || cmd.skus().isEmpty()) {
            // 没有 SKU 的商品价格无从谈起，上架后 C 端会显示 ¥0
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **类目必填**（P1-1 收尾）。它此前是选填的，而品类是必填的 —— 现在两者调了个个。
         *
         * 不强制的后果不是报错，是**静默走回落**：没类目就派生不出形态，
         * 商品落进「新建默认 NORMAL」，而商家以为自己建的是生鲜。
         * 端上已经用 `missing` 把按钮灰掉了，但那只挡住了界面这一条路 ——
         * 判据必须同时在服务端成立，否则下一个接进来的客户端照样能绕。
         *
         * 顺带堵掉「查无此类目」：`categoryTypeOf` 对不存在的编号返回 null，
         * 兜底成 NORMAL 会把一条错误数据静默转成一条合法数据。
         */
        if (cmd.categoryNo() == null || cmd.categoryNo().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * ★★ 引用标准品时的**权威收敛** —— 这是整个标准品库存在的理由（TDD-标准品库 §3.2）。
         *
         * 端上「从标准品开始」只是把字段**填进表单**，而填充过的表单商家能随便改。
         * 标题、图、规格文案改了没关系（「李婶家的农夫山泉」是合理的差异化），
         * 但两样不能改：
         *   ① **类目** —— 它决定形态（生鲜要截单、服务不发货）。改了就不是这个标准品了，
         *      而 std_no 还挂着，溯源会说谎；
         *   ② **optionCode** —— 跨店可比全靠它。能被改掉的话，标准品退化成一个填表助手，
         *      而 optionCode（B-4.5）「一期只写入不消费」要消费的那个前提又落空了。
         *
         * 所以收敛落在**服务端**，不落在端上：端上算错、老客户端不认这个字段、
         * 或者有人直接构造请求，都写不进一条破坏可比性的数据。
         */
        cmd = applyStd(cmd);
        boolean isNew = cmd.goodsNo() == null || cmd.goodsNo().isBlank();
        PrdGoods g = isNew ? newGoods(merchantNo) : mine(merchantNo, cmd.goodsNo());
        /*
         * **双版本（TDD-商品规格与发布 §3.3）：在售商品的编辑只落草稿，线上一个字节不动。**
         *
         * 这条推翻 V247 的「保存即自动下架送审」—— 那是单版本下的最优解，
         * 代价是审核/编辑期间线上是空的。现在买家继续看旧版完整内容，
         * 改动等发布（提交→[审核]→事务换版）才生效。
         * 草稿存的是 applyStd 之后的命令 —— 标准品的权威收敛不能因为绕到草稿就失效。
         *
         * 未上架的商品（草稿态/已下架/审核中）不走这里：它们没有「线上版」要保护，
         * 直接写主行，行为与从前逐字相同。
         */
        if (!isNew && Boolean.TRUE.equals(g.getOnSale()) && PUBLISHING.get() == null) {
            return saveAsDraft(g, cmd);
        }

        g.setTitle(cmd.title());
        g.setSubtitle(cmd.subtitle());
        g.setTitleI18n(writeMap(cmd.titleI18n()));
        g.setSubtitleI18n(writeMap(cmd.subtitleI18n()));
        /*
         * **品类由类目派生，商家填不了。**
         *
         * 这里原先取的是 cmd.type() —— 于是建品页有两个分类控件，商家把同一件事
         * 填两遍，而且可以互相矛盾（「叶菜」类目 + NORMAL 品类），只提示不阻断。
         * 矛盾的代价要到下单那一刻才显形：生鲜要截单、服务不发货，而这件商品
         * 声称自己是日用品。
         *
         * 类目缺省时的两种回落**刻意不同**：
         *   - 新建：NORMAL。没归类的商品当日用品处理，是最不容易出错的一档
         *   - 编辑：保留原值。不这么写的话，任何一次「只改标题」的保存
         *     都会把一件生鲜悄悄变成日用品
         *
         * 类目号查无此项时 categoryTypeOf 返回 null，走的也是这条回落 ——
         * 与「没填类目」同样处理，不把一个错误的编号变成一件合法的日用品。
         */
        String derivedType = categoryService.categoryTypeOf(cmd.categoryNo());
        if (derivedType == null) {
            // 类目已经必填（见上），走到这里只剩「传了一个查无此项的编号」——
            // 兜底成 NORMAL 等于把一条错误数据静默转成一条合法数据
            throw BizException.of(ErrorCode.CATEGORY_NOT_FOUND);
        }
        /*
         * **归档类目不能被新商品选中**（降二级之后这条从理论问题变成了现在就能踩：
         * 原三级类目整批归档，端上老缓存里还留着它们的编号）。
         *
         * 但<b>已经在里面的商品照旧能保存</b> —— 否则运营归档一个类目，
         * 会把底下所有商品一起变成改不动的死数据，商家连「挪到别的类目」这个
         * 自救动作都做不了。判据因此是「有没有换到一个归档类目」，不是「类目归没归档」。
         */
        boolean categoryChanged = isNew || !cmd.categoryNo().equals(g.getCategoryNo());
        if (categoryChanged && !categoryService.isActive(cmd.categoryNo())) {
            throw BizException.of(ErrorCode.CATEGORY_NOT_FOUND);
        }
        g.setType(derivedType);
        /*
         * 类目：**空串按「不归类」处理，不是「归到一个叫空的类目」**。
         * 不做这层转换的话，库里会出现 category_no='' 的商品 ——
         * 它既不出现在任何类目筛选里，也不为空，查起来看不出哪里不对。
         *
         * 这里不校验经营资质：那是上架时的事（见 requireCategoryAuthorized 的注释）。
         */
        if (cmd.fulfillments() != null) {
            /*
             * 履约方式此前是「有字段没入口」：建商品时写死 ["STORE_PICKUP"]，商家改不了，
             * 于是「这件商品支持怎么送」在商品侧从未被真正表达过。
             *
             * 空数组要拒掉而不是当成「不改」：一件一种履约都不支持的商品谁也买不了，
             * 而它在列表里看起来与正常商品毫无区别。不传（null）才是「不改」。
             */
            if (cmd.fulfillments().isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            for (String f : cmd.fulfillments()) {
                if (!Fulfillments.isValid(f)) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            /*
             * 商品勾的送货方式 ⊆ 商家启用集（方案 v4 的上架校验）：
             * 越界的勾选会让商品详情承诺一条店里根本没开的路，下单时才被闸拦 ——
             * 错要在写入口报，不是让买家替商家发现。
             *
             * 用主体级并集（storeNo=null）：商品挂主体，哪家店能履约是下单时按门店判的。
             * 服务类两值（STORE_VERIFY/APPOINTMENT）不受此限 —— 那不是门店开关管的事。
             * 空集 = 未迁移到 channel 模型，放行（只读兼容期约定）。
             */
            java.util.Set<String> enabled = merchantPort.enabledFulfillments(merchantNo, null);
            if (!enabled.isEmpty()) {
                for (String f : cmd.fulfillments()) {
                    if (!Fulfillments.SERVICE_LIKE.contains(f) && !enabled.contains(f)) {
                        throw BizException.of(ErrorCode.FULFILLMENT_NOT_SUPPORTED);
                    }
                }
            }
            g.setFulfillments(writeJson(cmd.fulfillments()));
        }
        if (cmd.categoryNo() != null) {
            g.setCategoryNo(cmd.categoryNo().isBlank() ? null : cmd.categoryNo());
        }
        applyOptional(g, cmd);
        g.setCover(cmd.cover() == null ? "" : cmd.cover());
        /*
         * **不传 = 不改**，与紧邻的 fulfillments 同一条规矩。
         *
         * 这里原先是无条件覆盖，而 writeJson(null) 返回的是 "[]" —— 于是
         * 「端上没带 images」被当成了「把轮播图全删掉」。b-app 的提交体里
         * 恰好从来没有这一项（契约 GoodsDraft.images 是有的，页面没填），
         * 结果是**改一次标题，详情页的轮播图全没了**，且不报错：
         * C 端只剩封面，看着像商家本来就没传图。
         *
         * 要清空轮播图请显式传空数组 —— 与「不传」分开，理由同 fulfillments。
         */
        if (cmd.images() != null) {
            g.setImages(writeJson(cmd.images()));
        }
        /*
         * 图文详情：**不传 = 不改**，与 images 同一口径 ——
         * 无条件覆盖的话，任何一次只改标题的保存都会把详情清空，且不报错。
         */
        if (cmd.detail() != null) {
            g.setDetail(cmd.detail().isBlank() ? null : cmd.detail());
        }
        /*
         * 详情图，与 detail 同一口径：**不传 = 不改**，传空数组 = 清空。
         * 无条件覆盖的话，任何一次只改标题的保存都会把详情图清空，且不报错。
         */
        if (cmd.params() != null) {
            /*
             * 商品参数（V250）。**不传 = 不改，传空数组 = 清空** —— 与 detailImages 同一口径。
             *
             * <p>只留有 label 的：端上把一个参数清空时发来的是 label 为空的那一条，
             * 而「这一项没填」与「这一项被删掉」在展示上是同一件事，存两种没有意义。
             */
            List<GoodsParam> kept = cmd.params().stream()
                    .filter(x -> x != null && x.label() != null && !x.label().isBlank())
                    .toList();
            g.setParams(kept.isEmpty() ? null : writeJson(kept));
        }
        if (cmd.detailImages() != null) {
            g.setDetailImages(cmd.detailImages().isEmpty() ? null : writeJson(cmd.detailImages()));
        }
        g.setSpecGroups(writeSpecGroups(cmd.specGroups()));
        /*
         * 溯源。**不传 = 脱离标准品**（置空），与其余字段的「不传 = 不改」相反 ——
         * 商家在编辑页点「脱离标准品」之后，端上就是不再带这个字段，
         * 而「不改」会让他脱不掉：商品继续被收敛，界面上却已经不显示来源了。
         */
        g.setStdNo(cmd.stdNo() == null || cmd.stdNo().isBlank() ? null : cmd.stdNo());
        /*
         * **改动后回到待审核，并强制下架。**
         *
         * 不这样做的话，「上架一件白菜 → 审核通过 → 改成别的东西继续卖」是一条通路，
         * 而审核在这条路上完全不起作用。代价是商家改个错别字也要重审 ——
         * 一期审核是人工的，这个代价由平台承担，不该由买家承担。
         *
         * <b>但新建与改草稿不进队列</b>（批 D）：保存即提审的后果是运营队列里
         * 混着半成品，而商家那边看到「审核中」，既不敢改也不知道在等什么。
         * 判据是「这件商品此刻在不在审核轴上」：
         *   - 新建 / 当前是草稿 → 仍是草稿，要商家显式点「提交审核」
         *   - 已过审、已驳回、在审中 → 照旧回 AUDITING（那条路一个字没动）
         */
        boolean stayDraft = isNew || DRAFT.equals(g.getAuditStatus());
        if (PUBLISHING.get() == null) {
            g.setAuditStatus(stayDraft ? DRAFT : AUDITING);
        }
        /*
         * **先把「它本来在卖」记下来，再下架送审**（V247）。
         * 不记的话，改一个在售商品的错别字就等于把它永久下架 ——
         * 过审后没有任何一处会把它放回去。
         */
        if (PUBLISHING.get() == null) {
            g.setPendingOnSale(Boolean.TRUE.equals(g.getOnSale()));
            g.setOnSale(false);
        }

        if (isNew) {
            DataScopeContext.executeWithoutScope(() -> goodsMapper.insert(g));
        } else {
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        }
        publishSkuUpserted(merchantNo, g,
                saveSkus(merchantNo, g.getGoodsNo(), cmd.skus(), cmd.specGroups()));
        /*
         * **建品时把这一类自动加进本店货架**（TDD-品类约束全链路 §4.2）。
         *
         * 选一个本店还没摆的类目不是错误，是「他要开始卖这个了」。让商家先去
         * 「我的类目」勾一遍再回来建品，是把一个系统能自己完成的动作变成了两趟。
         *
         * 这里<b>不校验经营资质</b>：那是上架时的事（见 requireCategoryAuthorized），
         * 草稿归到一个还没批下来的类目下是合法的 —— 他可能正在申请。
         */
        String ctxStore = ai.neargo.shop.auth.BizContext.current().currentStoreNo();
        if (ctxStore != null && !ctxStore.isBlank()) {
            storeCategoryPort.ensure(merchantNo, ctxStore, g.getCategoryNo());
        }
        /*
         * 免审直通（goods.audit=off）：编辑已过审商品那条路（stayDraft=false，
         * 正常要 AUDITING 等人）改成当场编译过审，pendingOnSale 立刻兑现 ——
         * 商家改个错别字不用再等一轮审。编译失败（80017）抛给他本人，
         * 整个 save 事务回滚，线上停在改动前的完整旧版。
         * 放在 saveSkus 之后：编译要读的 SKU 快照这时才落库。
         */
        if (PUBLISHING.get() != null) {
            /*
             * 换版收尾：编译 + **保持进场时的在售态**（不是无脑置 true）。
             * 正常路径商品本来在售，保持即在售；但运营强制下架之后商家走
             * 「已核对差异，仍要发布」——内容可以更新，**商品不能被这次发布抬回架**，
             * 处置的牙齿就是下架本身。要回架得他显式再点上架，那一步有自己的校验。
             * 失败（80017/乐观锁）整个发布事务回滚，线上停在完整旧版。
             */
            bakeForPublish(g);
            g.setAuditStatus(APPROVED);
            g.setPendingOnSale(false);
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
            syncPool(g, Boolean.TRUE.equals(g.getOnSale()));
            return toVO(g);
        }
        if (!stayDraft && !auditRequired()) {
            bakeForPublish(g);
            g.setAuditStatus(APPROVED);
            if (Boolean.TRUE.equals(g.getPendingOnSale())) {
                g.setOnSale(true);
            }
            g.setPendingOnSale(false);
            log.info("[免审] goods.audit=off，保存即过审：goods={} merchant={}", g.getGoodsNo(), merchantNo);
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
            syncPool(g, Boolean.TRUE.equals(g.getOnSale()));
            return toVO(g);
        }
        // 改动后强制下架，池要跟着撤 —— 否则改成别的东西之后，旧条目还挂在买家的社区列表里
        syncPool(g, false);
        return toVO(g);
    }

    /**
     * 引用标准品时把<b>类目与 optionCode 拉回标准品的值</b>，其余原样。
     *
     * <p>不引用（{@code stdNo} 为空）时原样返回，自建品链路一个字节都不变 ——
     * 标准库对「张姐家的酱菜」永远无效，而那类货是这个平台的一部分主力。
     *
     * <p><b>查无此标准品报错而不是忽略</b>：忽略的话，端上传了个失效的 stdNo，
     * 商品照样建出来、只是没了收敛，而 std_no 那一列还写着它 —— 一条自称
     * 「来自标准品」却不受标准品约束的数据，比没有标准品更糟。
     *
     * <p>规格组按<b>顺序位置</b>对齐：标准品有几组就收敛几组，商家<b>追加</b>的规格组
     * （比如「是否加冰」）保持原样、没有 code、不参与聚合 —— 这是刻意留的自由度。
     */
    private SaveCommand applyStd(SaveCommand cmd) {
        if (cmd.stdNo() == null || cmd.stdNo().isBlank()) {
            return cmd;
        }
        var std = spuStdService.find(cmd.stdNo());
        if (std == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        List<SpecGroup> merged = new ArrayList<>();
        List<SpecGroup> given = cmd.specGroups() == null ? List.of() : cmd.specGroups();
        for (int i = 0; i < given.size(); i++) {
            SpecGroup g = given.get(i);
            if (i >= std.specGroups().size()) {
                // 商家追加的组：没有对应的标准组，原样保留
                merged.add(g);
                continue;
            }
            var stdGroup = std.specGroups().get(i);
            /*
             * 只覆盖 code，不覆盖 name 与 options 文案 —— 后者是展示，
             * 商家把「重量」叫成「份量」不影响任何聚合。
             *
             * 选项数量对不上时以标准品的 code 列表为准截断/补齐：
             * 商家删掉一个规格选项是合法的（他就是不卖 5 斤装），
             * 而那一格的 code 也就跟着不需要了。
             */
            List<String> codes = stdGroup.optionCodes() == null ? List.of() : stdGroup.optionCodes();
            int n = g.options() == null ? 0 : g.options().size();
            List<String> aligned = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                aligned.add(j < codes.size() ? codes.get(j) : null);
            }
            merged.add(new SpecGroup(g.name(), g.options(), aligned, g.templateNo()));
        }
        return new SaveCommand(cmd.goodsNo(), cmd.title(), cmd.subtitle(),
                cmd.titleI18n(), cmd.subtitleI18n(),
                // ★ 类目以标准品为准 —— 形态因此也跟着回到标准品那一档
                std.categoryNo(),
                cmd.cover(), cmd.images(), merged, cmd.skus(), cmd.fulfillments(),
                cmd.limitPerUser(), cmd.fresh(), cmd.service(), cmd.groupBuy(), cmd.stdNo(),
                cmd.detail(), cmd.detailImages(), cmd.params());
    }

    /**
     * 品类差异字段与几个通用可选字段。<b>一律「不传 = 不改」</b>，与 fulfillments / images 同一条规矩。
     *
     * <p>这些列此前<b>只有 DevSeeder 和测试写得进去</b>：{@code SaveCommand} 里根本没有对应参数，
     * 而 {@code PrdGoods} 的类注释写着「五品类共用一张表，差异字段按 type 各用各的」——
     * 商家能选类目（从而定下品类），却一个差异字段都填不了。后果不是报错，是
     * <b>生鲜没有截单时间、服务没有时长、「可开团的商品」那一栏恒为空</b>。
     *
     * <p><b>按派生出的品类分段写</b>，不是照单全收：一件大米带上「服务时长 90 分钟」
     * 不会报错，但它会出现在服务类的详情模板里。端上按品类切换字段区，
     * 服务端再按品类校一次 —— 端上少一次判断不该让库里多一条脏数据。
     */
    /**
     * 在售商品的编辑落草稿（一件商品至多一份，upsert）。
     *
     * <p>{@code baseVersion} 只在**新建草稿**时记 —— 它是「编辑从哪一版线上开始」，
     * 续改同一份草稿不刷新它：刷新了的话，中途别人改过线上这件事就被洗掉了，
     * 发布时的冲突检查（对比线上 updated_at）会放过一次该拦的覆盖。
     */
    /** 「有未发布修改」标识的判据：草稿行存在与否，不比内容（保存时内容相同即删行） */
    private Boolean hasDraft(String goodsNo) {
        Long n = DataScopeContext.executeWithoutScope(() ->
                draftMapper.selectCount(Wrappers.<PrdGoodsDraft>lambdaQuery()
                        .eq(PrdGoodsDraft::getGoodsNo, goodsNo)));
        return n != null && n > 0;
    }

    private GoodsVO saveAsDraft(PrdGoods live, SaveCommand cmd) {
        String payload = writeJson(cmd);
        PrdGoodsDraft row = DataScopeContext.executeWithoutScope(() ->
                draftMapper.selectOne(Wrappers.<PrdGoodsDraft>lambdaQuery()
                        .eq(PrdGoodsDraft::getGoodsNo, live.getGoodsNo()).last("limit 1")));
        if (row == null) {
            row = new PrdGoodsDraft();
            row.setGoodsNo(live.getGoodsNo());
            row.setEntityNo(live.getEntityNo());
            row.setBaseVersion(live.getVersion());
            row.setStatus(PrdGoodsDraft.EDITING);
            row.setPayload(payload);
            PrdGoodsDraft ins = row;
            DataScopeContext.executeWithoutScope(() -> draftMapper.insert(ins));
        } else if (!payload.equals(row.getPayload())) {
            row.setPayload(payload);
            row.setStatus(PrdGoodsDraft.EDITING);
            PrdGoodsDraft upd = row;
            DataScopeContext.executeWithoutScope(() -> draftMapper.updateById(upd));
        }
        // 线上不动，回的是线上版 —— 端上编辑页读草稿另有入口（发布链路那一步）
        return toVO(live);
    }

    @Override
    @Transactional
    public GoodsVO publishDraft(String merchantNo, String goodsNo, Long confirmVersion) {
        PrdGoods live = mine(merchantNo, goodsNo);
        PrdGoodsDraft draft = DataScopeContext.executeWithoutScope(() ->
                draftMapper.selectOne(Wrappers.<PrdGoodsDraft>lambdaQuery()
                        .eq(PrdGoodsDraft::getGoodsNo, goodsNo).last("limit 1")));
        if (draft == null) {
            // 没草稿没什么可发布 —— 这不是错误路径的兜底，是端上按钮态漏了
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 冲突检查：草稿基于的那一版线上被人改过（运营强改/多端编辑，
         * 也包括商家**自己**的 toggle / 改截单 —— version 每次 UPDATE 都增）→ 拒。
         * 静默覆盖的话，运营刚做的强制处置会被商家一次发布洗掉，而且双方都不知道。
         *
         * <p><b>出路</b>（没有出路的话，生鲜商家每天改截单就把自己的草稿锁死了）：
         * 预览接口把此刻线上的 version 随差异一起下发；商家看过「以此刻线上为基准」
         * 的完整 diff 后显式确认，端上原样带回 —— **对得上此刻的 version 才放行**，
         * 确认之后线上又变了照样拒（等值比较天然做到）。另一条出路是 discardDraft。
         */
        boolean stale = draft.getBaseVersion() != null
                && !draft.getBaseVersion().equals(live.getVersion());
        if (stale && !live.getVersion().equals(confirmVersion)) {
            throw BizException.of(ErrorCode.GOODS_DRAFT_STALE);
        }
        if (stale) {
            // 确认过的冲突就地重新基线：审核开时草稿还要活到过审那一刻，
            // 行上记录的「基于哪一版」应当反映商家确认过的这一版
            draft.setBaseVersion(live.getVersion());
        }
        if (auditRequired()) {
            /*
             * 审核开：**线上继续卖旧版** —— 这正是双版本对 V247 的全部改进。
             * live 行只把 audit_status 置 AUDITING（进现有审核队列，队列代码零改动），
             * on_sale 一个字节不动。过审回调里换版（见 audit 方法的草稿分支）。
             */
            draft.setStatus(PrdGoodsDraft.SUBMITTED);
            DataScopeContext.executeWithoutScope(() -> draftMapper.updateById(draft));
            live.setAuditStatus(AUDITING);
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(live));
            return toVO(live);
        }
        return swapFromDraft(live, draft);
    }

    @Override
    @Transactional
    public GoodsVO discardDraft(String merchantNo, String goodsNo) {
        // mine() 先核归属 —— 草稿表只有 goods_no，不核的话拿别家的单号也删得掉
        PrdGoods live = mine(merchantNo, goodsNo);
        // 幂等：purge 对不存在的行是 0 行 no-op，重复点「放弃」不该报错
        DataScopeContext.executeWithoutScope(() -> draftMapper.purge(live.getGoodsNo()));
        return toVO(live);
    }

    @Override
    public SaveCommand draftOf(String merchantNo, String goodsNo) {
        // mine() 先把归属核了：草稿表只有 goods_no，不核的话拿别家的单号也读得到
        PrdGoods live = mine(merchantNo, goodsNo);
        PrdGoodsDraft draft = DataScopeContext.executeWithoutScope(() ->
                draftMapper.selectOne(Wrappers.<PrdGoodsDraft>lambdaQuery()
                        .eq(PrdGoodsDraft::getGoodsNo, live.getGoodsNo()).last("limit 1")));
        if (draft == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(draft.getPayload(), SaveCommand.class);
        } catch (Exception e) {
            // 按没有草稿处理：让商家从线上版重新编辑。warn 点名，别静默
            log.warn("[草稿] payload 解析失败，按无草稿返回：goods={}", goodsNo, e);
            return null;
        }
    }

    @Override
    public ai.neargo.shop.product.dto.PublishPreviewVO publishPreview(String merchantNo, String goodsNo) {
        PrdGoods live = mine(merchantNo, goodsNo);
        PrdGoodsDraft draft = DataScopeContext.executeWithoutScope(() ->
                draftMapper.selectOne(Wrappers.<PrdGoodsDraft>lambdaQuery()
                        .eq(PrdGoodsDraft::getGoodsNo, goodsNo).last("limit 1")));
        if (draft == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return buildPreview(live, draft);
    }

    @Override
    public ai.neargo.shop.product.dto.PublishPreviewVO draftPreviewForOps(String goodsNo) {
        /*
         * 审核员的草稿审阅视图（双版本欠口）：审核开着时，队列里那件 AUDITING 商品
         * **线上照卖旧版**，detailForOps 给的也是旧版 —— 不看这份 diff 的话，
         * 审核员批准的是一个自己从没看过的版本。
         *
         * <p>requireByNoInScope 先过数据域：配了商家域的审核员只看得到自己负责那几家。
         * 只认 SUBMITTED —— 过审那一刻换版的就是它（EDITING 是商家还没交的草稿，
         * 不该被审、更不该被剧透）。没有草稿返回 null：新建提审等老链路走的是
         * 「审内容本身」，那条路上没有 diff 可看，是常态不是错误。
         */
        PrdGoods live = requireByNoInScope(goodsNo);
        PrdGoodsDraft draft = DataScopeContext.executeWithoutScope(() ->
                draftMapper.selectOne(Wrappers.<PrdGoodsDraft>lambdaQuery()
                        .eq(PrdGoodsDraft::getGoodsNo, live.getGoodsNo())
                        .eq(PrdGoodsDraft::getStatus, PrdGoodsDraft.SUBMITTED)
                        .last("limit 1")));
        if (draft == null) {
            return null;
        }
        return buildPreview(live, draft);
    }

    /** 草稿 vs 线上的字段级 diff。商家发布确认页与运营审核抽屉共用 —— 两边看的必须是同一份 */
    private ai.neargo.shop.product.dto.PublishPreviewVO buildPreview(PrdGoods live, PrdGoodsDraft draft) {
        SaveCommand cmd;
        try {
            cmd = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(draft.getPayload(), SaveCommand.class);
        } catch (Exception e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean stale = draft.getBaseVersion() != null
                && !draft.getBaseVersion().equals(live.getVersion());

        // dry-run 烘焙草稿的规格 —— 与真发布同一套规则（bakeSpecs），否则预览会说谎
        String draftGroupsJson = writeSpecGroups(cmd.specGroups());
        List<String> blocked = List.of();
        String bakedGroups = draftGroupsJson;
        if (draftGroupsJson != null && !draftGroupsJson.isBlank()) {
            BakedSpecs baked = bakeSpecs(live.getEntityNo(), cmd.categoryNo(), draftGroupsJson);
            if (baked != null) {
                blocked = baked.unresolved().stream().distinct().toList();
                bakedGroups = baked.specGroupsJson();
            }
        }

        List<ai.neargo.shop.product.dto.PublishPreviewVO.DiffRow> rows = new java.util.ArrayList<>();
        diffRow(rows, "title", "标题", live.getTitle(), cmd.title());
        diffRow(rows, "subtitle", "副标题", live.getSubtitle(), cmd.subtitle());
        diffRow(rows, "cover", "封面", live.getCover(), cmd.cover());
        diffRow(rows, "spec", "规格", renderGroups(live.getSpecGroups()), renderGroups(bakedGroups));
        diffRow(rows, "params", "参数", renderParams(live.getParams()),
                cmd.params() == null ? renderParams(live.getParams()) : renderParams(writeJson(cmd.params())));
        // SKU：按位次比价格与库存 —— 档位文案差异已含在「规格」一行里
        List<PrdSku> liveSkus = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, live.getGoodsNo())
                        .orderByAsc(PrdSku::getId)));
        List<Sku> cmdSkus = cmd.skus() == null ? List.of() : cmd.skus();
        int n = Math.max(liveSkus.size(), cmdSkus.size());
        for (int i = 0; i < n; i++) {
            String before = i < liveSkus.size()
                    ? liveSkus.get(i).getSpec() + " ¥" + liveSkus.get(i).getPrice() + " 库存" + liveSkus.get(i).getStock()
                    : null;
            String after = i < cmdSkus.size()
                    ? String.join(" · ", cmdSkus.get(i).optionValues() == null ? List.of() : cmdSkus.get(i).optionValues())
                      + " ¥" + cmdSkus.get(i).price() + " 库存" + cmdSkus.get(i).stock()
                    : null;
            diffRow(rows, "sku" + i, "第 " + (i + 1) + " 档", before, after);
        }
        return new ai.neargo.shop.product.dto.PublishPreviewVO(rows, blocked, stale, live.getVersion());
    }

    private static void diffRow(List<ai.neargo.shop.product.dto.PublishPreviewVO.DiffRow> rows,
                                String field, String label, String before, String after) {
        if (java.util.Objects.equals(before, after)) {
            return;
        }
        rows.add(new ai.neargo.shop.product.dto.PublishPreviewVO.DiffRow(field, label, before, after));
    }

    /** spec_groups JSON → 「组名: 档1/档2」的一行文本，够对比用 —— 预览是给人扫一眼的，不是契约 */
    private static String renderGroups(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            StringBuilder sb = new StringBuilder();
            for (var g : arr) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(g.path("name").asText()).append(": ");
                var opts = g.path("options");
                for (int i = 0; i < opts.size(); i++) {
                    if (i > 0) {
                        sb.append("/");
                    }
                    sb.append(opts.get(i).asText());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private static String renderParams(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            StringBuilder sb = new StringBuilder();
            for (var pnode : arr) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(pnode.path("name").asText()).append(": ").append(pnode.path("label").asText());
            }
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 换版：把草稿原样走一遍 save() 全链路（PUBLISHING 标志让它跳过草稿分支与
     * V247 下架段，收尾改成 编译+保持在售），成功后**物理删**草稿行 —— 同一事务，
     * 中途任何失败（80017 / 乐观锁）整体回滚，线上停在完整旧版。
     */
    private GoodsVO swapFromDraft(PrdGoods live, PrdGoodsDraft draft) {
        SaveCommand cmd;
        try {
            cmd = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(draft.getPayload(), SaveCommand.class);
        } catch (Exception e) {
            log.warn("[换版] 草稿 payload 解析失败：goods={}", draft.getGoodsNo(), e);
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PUBLISHING.set(Boolean.TRUE);
        try {
            GoodsVO vo = save(live.getEntityNo(), cmd);
            DataScopeContext.executeWithoutScope(() -> draftMapper.purge(draft.getGoodsNo()));
            return vo;
        } finally {
            PUBLISHING.remove();
        }
    }

    private void applyOptional(PrdGoods g, SaveCommand cmd) {
        if (cmd.limitPerUser() != null) {
            // 负数限购会让「每人限购」变成谁都买不了，而界面上看着是配着的
            g.setLimitPerUser(Math.max(cmd.limitPerUser(), 0));
        }
        if (cmd.fresh() != null && "FRESH".equals(g.getType())) {
            var f = cmd.fresh();
            if (f.cutoffAt() != null) {
                g.setCutoffAt(f.cutoffAt());
            }
            if (f.arrivalDesc() != null) {
                g.setArrivalDesc(f.arrivalDesc());
            }
            if (f.weighed() != null) {
                g.setWeighed(f.weighed());
            }
            if (f.origin() != null) {
                g.setOrigin(f.origin());
            }
        }
        if (cmd.service() != null && "SERVICE".equals(g.getType())) {
            var s = cmd.service();
            if (s.durationMin() != null) {
                g.setDurationMin(s.durationMin());
            }
            if (s.storeName() != null) {
                g.setStoreName(s.storeName());
            }
        }
        if (cmd.groupBuy() != null) {
            var gb = cmd.groupBuy();
            /*
             * 两个值必须一起给 —— `groupBuyConf` 缺一个就返回 null，也就是「不能开团」。
             * 只给一个的话，商家在界面上填了团价却开不出团，而没有任何提示。
             * 两个都为空是**显式关闭拼团**，与「不传这一段」（不改）分开。
             */
            if (gb.minCount() == null && gb.priceMinor() == null) {
                g.setGroupMinCount(null);
                g.setGroupPriceMinor(null);
            } else if (gb.minCount() == null || gb.priceMinor() == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            } else {
                // 一个人不叫团（词典 §8）
                if (gb.minCount() < 2 || gb.priceMinor() < 0) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
                g.setGroupMinCount(gb.minCount());
                g.setGroupPriceMinor(gb.priceMinor());
            }
        }
    }

    /**
     * SKU 全量替换，但<b>保留原编号</b>：历史订单、购物车、库存锁都指向 skuNo，
     * 换编号等于让它们指向一个不存在的东西。
     */
    /**
     * 把「这个 SKU 建好了」告诉外面。**今天唯一的消费方是进销存**（建物料与外部引用）。
     *
     * <p><b>为什么不直接调进销存</b>：`shop-core/product` 对进销存 ACL 的引用数必须是 0 ——
     * 那是「进销存可独立交付」的前提，也是架构守卫盯着的一条。走事件，两个模块仍然互不认识。
     *
     * <p><b>按 skuNo 去重</b>：SKU 行是 (skuNo × market) 的，而**库存不分市场**
     *（货就那么多，卖到哪个市场都是同一批）。不去重的话一个 SKU 会发 N 条，
     * N 是运营开了几个市场 —— 消费方幂等，不会出错，但那是白跑。
     *
     * <p>发的是 Outbox（只写库、由投递器异步投），所以**它不会拖慢建品**，
     * 也不会因为进销存那边出问题而让建品失败。
     */
    private void publishSkuUpserted(String merchantNo, PrdGoods g, List<PrdSku> saved) {
        if (saved.isEmpty()) {
            return;
        }
        java.util.Set<String> sent = new java.util.HashSet<>();
        for (PrdSku row : saved) {
            /*
             * **必须用落库那一行，不能用命令里的 Sku。** 新建时命令里
             * `skuNo` 是空的 —— 它在 saveSkus 里才生成。用命令的话这个循环
             * 一条都发不出去，而且不报错：SKU 建成了，账上没有它。
             * 2026-08-28 第一版就是这么写的，被 newSkuLandsOnTheBooks 抓下来。
             *
             * **按 skuNo 去重**：SKU 行是 (skuNo × market) 的，而库存不分市场。
             */
            if (row.getSkuNo() == null || !sent.add(row.getSkuNo())) {
                continue;
            }
            events.publish(new ai.neargo.shop.spi.product.ProductEvents.SkuUpserted(
                    row.getSkuNo(), merchantNo, g.getGoodsNo(), g.getTitle(),
                    row.getSpec(), row.getBarcode(), row.getMerchantSkuCode(), row.getSaleUnit()));
        }
    }

    /** @return 本次真正落库的 SKU 行。**新建时 skuNo 是这里生成的**，命令里没有 */
    private List<PrdSku> saveSkus(String merchantNo, String goodsNo, List<Sku> skus,
                          List<SpecGroup> groups) {
        List<PrdSku> saved = new java.util.ArrayList<>();
        /*
         * **SKU 数量上限**。端上限制 3 个规格维度，但那是界面的事 ——
         * 接口层此前一条都不拦，3 维 × 各 8 个选项 = 512 行 × 3 市场可以直接灌进来，
         * 而每一行都会进商品详情、进 C 端选规格面板。这是防呆，不是业务限制。
         */
        if (skus.size() > MAX_SKUS) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<PrdSku> existing = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo)));
        Map<String, PrdSku> byNo = new LinkedHashMap<>();
        for (PrdSku s : existing) {
            byNo.put(s.getSkuNo() + "@" + s.getMarket(), s);
        }

        /*
         * **把选项文案反查成规格值编号**，落进 SKU 快照（V195）。
         *
         * 每个规格组带着它的维度（端上原样回传的 templateNo = dimNo），
         * 于是「第 i 个维度上的这个文案」能定位到唯一一个值。查不到就留 null ——
         * 商家手打的规格本来就没有值编号，造一个假的比留空更糟。
         *
         * 这一层是「跨店可比」真正的落点：此前 spec_groups 里存的只有文案，
         * 三家店的「500g」「五百克」「0.5kg」永远聚不到一起（线上 378 件商品，
         * 带 optionCode 的 0 件）。
         */
        Map<Integer, Map<String, String>> valueNoByDim = new LinkedHashMap<>();
        if (groups != null) {
            for (int i = 0; i < groups.size(); i++) {
                SpecGroup g = groups.get(i);
                if (g == null || g.templateNo() == null || g.templateNo().isBlank()) {
                    continue;
                }
                valueNoByDim.put(i,
                        specLibrary.resolveValueNos(merchantNo, g.templateNo(), g.options()));
            }
        }

        // Set 而不是 List：下面每删一行都要 contains 一次，
        // 而规格矩阵 3×4×3 = 36 个 SKU × 3 市场 = 108 行，List 是 O(n²)
        Set<String> kept = new java.util.HashSet<>();
        for (Sku sku : skus) {
            String skuNo = sku.skuNo() == null || sku.skuNo().isBlank()
                    ? BizKey.next(BizKey.SKU) : sku.skuNo();
            // 按市场逐行写：(entity_no, sku_no, market) 是价格的唯一权威，
            // 一个 SKU 在三个市场就是三行，而不是一行里塞一个 JSON 价格表
            Map<String, Long> prices = new LinkedHashMap<>();
            prices.put(HOME_MARKET, sku.price());
            if (sku.priceByMarket() != null) {
                /*
                 * **market 的取值要校验，它是从调用方原样落进列里的。**
                 *
                 * 2026-08-20 线上真的写进去过 4 行 `market='CNY'` —— 那是
                 * **货币码**，不是市场码（`sys_market` 里 CN 那一行的 currency
                 * 恰好就是 CNY）。唯一键是 (entity_no, sku_no, market)，
                 * 于是同一个 SKU 变成两行、两份价、两份库存，而<b>没有任何报错</b>：
                 * 界面上看不出来，只有拿平台侧 210 去对进销存 209 时才露出来。
                 *
                 * `PlatformConfigServiceImpl.markets()` 那段注释早就点了名：
                 * 「market 这个列早就在五张表上用着，却没有任何东西保证那些值
                 * 真的存在」。表（`sys_market`）在 S11 建好了，这里补上写入侧那一半。
                 *
                 * **一次取全量再比，不逐个 find**：一次保存最多 108 行
                 *（36 个 SKU × 3 市场），逐个查等于 108 次往返。
                 */
                Set<String> known = marketsOf();
                for (String m : sku.priceByMarket().keySet()) {
                    if (!known.contains(m)) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                }
                prices.putAll(sku.priceByMarket());
            }
            for (var e : prices.entrySet()) {
                String key = skuNo + "@" + e.getKey();
                PrdSku row = byNo.get(key);
                boolean fresh = row == null;
                if (fresh) {
                    row = new PrdSku();
                    row.setSkuNo(skuNo);
                    row.setGoodsNo(goodsNo);
                    row.setEntityNo(merchantNo);
                    row.setMarket(e.getKey());
                    row.setLockedStock(0);
                }
                row.setOptionValues(writeJson(sku.optionValues()));
                row.setOptionValueNos(writeJson(valueNos(sku.optionValues(), valueNoByDim)));
                row.setSpec(String.join(" · ", sku.optionValues() == null ? List.of() : sku.optionValues()));
                row.setPrice(e.getValue());
                row.setStock(sku.stock());
                /*
                 * 划线价与标称重量：**不传 = 不改**，与商品级那几段同一条规矩。
                 * 两列此前都是「有列、有契约、没有写入路径」——
                 * 折扣标永远不出现，生鲜也永远按不了实称。
                 *
                 * 划线价不分市场校验（各市场自己的划线价各填各的），但**必须高于售价**：
                 * 低于售价的划线价会渲染成一个「涨价了」的折扣标。
                 */
                if (sku.originPrice() != null) {
                    if (sku.originPrice() > 0 && sku.originPrice() < e.getValue()) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    row.setOriginPrice(sku.originPrice() <= 0 ? null : sku.originPrice());
                }
                if (sku.nominalGram() != null) {
                    row.setNominalGram(sku.nominalGram() <= 0 ? null : sku.nominalGram());
                }
                /*
                 * 成本价：不传 = 不改，<= 0 = 清空。**不校验与售价的大小关系** ——
                 * 引流款本来就可能亏本卖，拦住它是替商家做生意。
                 * 它只写基准市场那一行也说得通（进价不随售卖市场变），但按行写更省事，
                 * 且读回时取的是 skuNo 维度的任意一行（见 withMarketPrices）。
                 */
                if (sku.costPrice() != null) {
                    row.setCostPrice(sku.costPrice() <= 0 ? null : sku.costPrice());
                }
                /*
                 * **外部身份三件套**（V252）：条码 / 商家自有货号 / 计量单位。
                 * 都是「不传 = 不改，传空串 = 清空」—— 与 detail、detailImages 同一口径。
                 *
                 * <p>不校验条码格式：EAN-13 与 UPC 长度不同，还有店内自编码（20/21 开头）
                 * 与称重码这些变体；在这里拦一道，商家扫出来一个合法但不在我们白名单里的
                 * 条码就录不进去，而他手上那包货确实印着它。
                 * 校验该发生在**导入**那一步（能一次说清哪几行有问题），不是逐条录入时。
                 */
                row.setBarcode(blankToNull(sku.barcode(), row.getBarcode()));
                row.setMerchantSkuCode(blankToNull(sku.merchantSkuCode(), row.getMerchantSkuCode()));
                row.setSaleUnit(blankToNull(sku.saleUnit(), row.getSaleUnit()));
                PrdSku toSave = row;
                DataScopeContext.executeWithoutScope(() ->
                        fresh ? skuMapper.insert(toSave) : skuMapper.updateById(toSave));
                saved.add(toSave);
                kept.add(key);
            }
        }

        /*
         * 被删掉的规格行：逻辑删除而不是物理删 —— 历史订单要能查回「当时买的是哪个规格」。
         *
         * ★ <b>只删「这个 skuNo 整个不见了」的行，不删「这次没提交的市场」。</b>
         *
         * 上一版是按 {@code skuNo@market} 逐行比对，于是端上只回填得了当前市场那一格
         * （{@code GoodsVO.SkuVO} 当时不下发 priceByMarket），提交上来的价格表只有
         * {CN: x} —— <b>商家改一次标题，AE/US 两行就被逻辑删了</b>，而且不报错：
         * 那两个市场的买家从此看不到这件商品，商家在 B 端也看不出任何异常。
         *
         * 与 titleI18n 是逐字同款的形状（编辑页按维度逐格填、保存是整份覆盖），
         * 那边补了下发，这边没补。现在两头都做：下发补齐（见 {@code GoodsVO.SkuVO.priceByMarket}），
         * 同时这里把「没提交的市场」按不改处理 —— 两道防线，因为下发补齐防不住
         * 老版本客户端。
         */
        Set<String> keptSkuNos = kept.stream().map(k -> k.substring(0, k.indexOf('@')))
                .collect(java.util.stream.Collectors.toSet());
        for (var e : byNo.entrySet()) {
            String skuNo = e.getKey().substring(0, e.getKey().indexOf('@'));
            if (keptSkuNos.contains(skuNo)) {
                continue;
            }
            DataScopeContext.executeWithoutScope(() -> skuMapper.deleteById(e.getValue().getId()));
        }
        ensureStoreStockRows(merchantNo, keptSkuNos);
        // groups 已写在 goods 上，这里只用于生成 spec 文案，不再单独落库
        return saved;
    }

    /** 一件商品最多几个规格组合。3×4×3=36 是端上摸得到的上界，留三倍余量 */
    private static final int MAX_SKUS = 100;

    /**
     * 与 optionValues 一一对应的值编号，归不了一的位置是 null。
     *
     * <p><b>位置必须对齐</b>：第 i 个取值属于第 i 个规格组，也就是第 i 个维度。
     * 错位的话「黑色」会被归成一个重量值，而这种错不会有任何一处报出来。
     */
    private static List<String> valueNos(List<String> optionValues,
                                         Map<Integer, Map<String, String>> byDim) {
        if (optionValues == null || optionValues.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(optionValues.size());
        for (int i = 0; i < optionValues.size(); i++) {
            Map<String, String> m = byDim.get(i);
            out.add(m == null ? null : m.get(optionValues.get(i)));
        }
        return out;
    }

    /**
     * 新加的规格要跟上<b>分店库存</b>这条口径。
     *
     * <p>{@code StockPortImpl.hasStoreStock(skuNo)} 是<b>按 SKU</b> 判「有没有启用分店库存」：
     * 给一个已经按店管库存的多规格商品新加一个规格，新 SKU 一条门店行都没有，
     * 于是它<b>回落到主体总量</b>——同一张商品页上，老规格按店卖、新规格按主体卖，
     * 两套口径并存，而界面上完全看不出来。
     *
     * <p>做法是给每家已经管过这件商品的门店补一行 {@code stock=0}：
     * <b>少卖可恢复，超卖不可</b>（与 {@code hasStoreStock} 的注释同一条判据）。
     * 商家在编辑页看到新规格库存是 0，是正确的提示 —— 他确实还没给任何一家店备货。
     */
    private void ensureStoreStockRows(String merchantNo, Set<String> skuNos) {
        if (skuNos.isEmpty()) {
            return;
        }
        List<PrdStoreStock> rows = DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                        .in(PrdStoreStock::getSkuNo, skuNos)));
        if (rows.isEmpty()) {
            // 这件商品还没有任何门店库存行 = 没启用分店库存，什么都不用做
            return;
        }
        Set<String> stores = rows.stream().map(PrdStoreStock::getStoreNo)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> have = rows.stream().map(r -> r.getSkuNo() + "@" + r.getStoreNo())
                .collect(java.util.stream.Collectors.toSet());
        for (String skuNo : skuNos) {
            for (String storeNo : stores) {
                if (have.contains(skuNo + "@" + storeNo)) {
                    continue;
                }
                PrdStoreStock row = new PrdStoreStock();
                row.setStoreNo(storeNo);
                row.setSkuNo(skuNo);
                row.setEntityNo(merchantNo);
                row.setStock(0);
                row.setLockedStock(0);
                DataScopeContext.executeWithoutScope(() -> storeStockMapper.insert(row));
            }
        }
    }

    // ---------------------------------------------------------------- 上下架 / 库存

    /**
     * 上架编译点（TDD-商品规格与发布 §3.2）：把快照对着**当前**规格库重解析、重烘焙。
     *
     * <p>为什么在上架而不是保存：草稿放两周，其间平台改文案、停档位 ——
     * 保存时烘的那份已经过期，而此前 toggle 只翻状态位，没有任何一处会发现。
     *
     * <p>做三件事：①文案刷新（组名取本店叫法、档位文案取当前平台/类目口径，
     * **按 optionCodes 定位** —— 文案是会变的，code 才是身份）；②身份复核
     * （resolveValueNos 对当前库重解析，解析不出＝档被停用/合并，**集中一次报全**，
     * 不让商家改一个撞一个）；③SKU 快照同步（文案与 option_value_nos 一起刷）。
     *
     * <p>不碰的：没有 templateNo 的组（手打的历史商品，没有身份可复核 ——
     * V229 那批的余量，动它们只会把「没身份」变成「错身份」）。
     * 幂等：算出来与快照相同就不写库，免得每次上架都动 updated_at。
     * 下架与驳回**不**烘焙 —— 快照要留给历史订单解释自己。
     */
    /**
     * 商品上架要不要人审（goods.audit 开关，PRD-商品规格与发布 §3.2）。
     * **默认开** —— 开关行不存在时行为与从前逐字相同；关掉的只是人审，
     * 编译点校验（bakeForPublish）与强制下架照常有效。
     */
    private boolean auditRequired() {
        return switchPort.bool("goods.audit", true);
    }

    /** 编译的纯计算结果 —— bakeForPublish（落库）与 publishPreview（dry-run）共用 */
    record BakedSpecs(String specGroupsJson, boolean changed,
                      List<Map<String, String>> relabelByGroup,
                      List<Map<String, String>> valueNoByGroup,
                      List<String> unresolved) {
    }

    /**
     * 对着**当前**规格库重解析+重烘焙一份 spec_groups（不碰任何数据库行）。
     * 语义细节见 bakeForPublish 的注释 —— 这里只是把「算」从「写」里抽出来，
     * 让发布前的差异预览能用同一套规则 dry-run：预览与真发布算出不同结果，
     * 比没有预览更糟。
     */
    private BakedSpecs bakeSpecs(String entityNo, String categoryNo, String specGroupsJson) {
        List<ai.neargo.shop.product.dto.SpecTemplateVO> current =
                specLibrary.templatesForCategory(entityNo, categoryNo);
        Map<String, ai.neargo.shop.product.dto.SpecTemplateVO> byDim = current.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ai.neargo.shop.product.dto.SpecTemplateVO::templateNo, v -> v, (a, b) -> a));
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode groups;
        try {
            groups = (com.fasterxml.jackson.databind.node.ArrayNode) om.readTree(specGroupsJson);
        } catch (Exception e) {
            return null;   // 解析不动的老快照：调用方按「跳过烘焙」处理
        }
        List<String> unresolved = new java.util.ArrayList<>();
        List<Map<String, String>> relabelByGroup = new java.util.ArrayList<>();
        List<Map<String, String>> valueNoByGroup = new java.util.ArrayList<>();
        boolean groupsChanged = false;
        for (var node : groups) {
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode group)
                    || group.path("templateNo").asText("").isBlank()) {
                relabelByGroup.add(Map.of());
                valueNoByGroup.add(Map.of());
                continue;
            }
            String dimNo = group.path("templateNo").asText();
            var vo = byDim.get(dimNo);
            String groupName = group.path("name").asText("");
            Map<String, String> codeToLabel = new LinkedHashMap<>();
            if (vo != null) {
                if (!vo.name().equals(groupName)) {
                    group.put("name", vo.name());
                    groupsChanged = true;
                }
                for (var o : vo.options()) {
                    codeToLabel.put(o.code() == null ? "" : o.code(), o.label());
                }
            } else if (specLibrary.dimUsable(dimNo)) {
                /*
                 * 快照引用的维度不在合并结果里 —— 不等于停用：建品页允许直接挑
                 * 平台通用维度（不落类目绑定也不落商家覆盖）。回落到该维度的全量值池
                 * 刷文案；组名保持原样（这条路上拿不到「本店叫法」的合并口径，
                 * 而保持原样最多是名字旧，改错名字是更糟的那种错）。
                 */
                for (var o : specLibrary.valuesOfDim(entityNo, dimNo)) {
                    codeToLabel.put(o.code() == null ? "" : o.code(), o.label());
                }
            } else {
                unresolved.add(groupName + "（该规格已停用）");
                relabelByGroup.add(Map.of());
                valueNoByGroup.add(Map.of());
                continue;
            }
            String shownName = vo != null ? vo.name() : groupName;
            var options = (com.fasterxml.jackson.databind.node.ArrayNode) group.get("options");
            var codes = group.get("optionCodes") instanceof com.fasterxml.jackson.databind.node.ArrayNode ca
                    ? ca : null;
            Map<String, String> relabel = new LinkedHashMap<>();
            List<String> newLabels = new java.util.ArrayList<>();
            for (int i = 0; options != null && i < options.size(); i++) {
                String oldLabel = options.get(i).asText();
                String code = codes != null && i < codes.size() ? codes.get(i).asText() : null;
                // 有 code 按 code 找（文案会变，code 是身份）；没 code 的老数据按文案原样试
                String newLabel = code != null && !code.isBlank()
                        ? codeToLabel.get(code)
                        : (codeToLabel.containsValue(oldLabel) ? oldLabel : null);
                if (newLabel == null) {
                    unresolved.add(shownName + "·" + oldLabel);
                    newLabels.add(oldLabel);
                    continue;
                }
                if (!newLabel.equals(oldLabel)) {
                    options.set(i, om.getNodeFactory().textNode(newLabel));
                    relabel.put(oldLabel, newLabel);
                    groupsChanged = true;
                }
                newLabels.add(newLabel);
            }
            Map<String, String> resolved = specLibrary.resolveValueNos(entityNo, dimNo, newLabels);
            for (String label : newLabels) {
                if (resolved.get(label) == null && !unresolved.contains(shownName + "·" + label)) {
                    unresolved.add(shownName + "·" + label);
                }
            }
            relabelByGroup.add(relabel);
            valueNoByGroup.add(resolved);
        }
        String outJson = specGroupsJson;
        if (groupsChanged) {
            try {
                outJson = om.writeValueAsString(groups);
            } catch (Exception e) {
                throw new IllegalStateException(e);   // 刚解析成功的树写不回去＝编程错误，不吞
            }
        }
        return new BakedSpecs(outJson, groupsChanged, relabelByGroup, valueNoByGroup, unresolved);
    }

    private void bakeForPublish(PrdGoods g) {
        if (g.getSpecGroups() == null || g.getSpecGroups().isBlank()) {
            return;
        }
        BakedSpecs baked = bakeSpecs(g.getEntityNo(), g.getCategoryNo(), g.getSpecGroups());
        if (baked == null) {
            log.warn("[上架烘焙] spec_groups 解析失败，跳过烘焙照原样上架：goods={}", g.getGoodsNo());
            return;
        }
        if (!baked.unresolved().isEmpty()) {
            throw BizException.of(ErrorCode.GOODS_SPEC_UNRESOLVED,
                    String.join("、", baked.unresolved().stream().distinct().toList()));
        }
        if (baked.changed()) {
            g.setSpecGroups(baked.specGroupsJson());
        }
        List<Map<String, String>> relabelByGroup = baked.relabelByGroup();
        List<Map<String, String>> valueNoByGroup = baked.valueNoByGroup();
        // SKU 快照跟上：文案按组内映射换，身份按新文案重解析（位置=第 i 组）
        List<PrdSku> skus = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, g.getGoodsNo())));
        for (PrdSku sku : skus) {
            List<String> values = readJsonList(sku.getOptionValues());
            if (values.isEmpty()) {
                continue;
            }
            boolean skuChanged = false;
            List<String> newValues = new java.util.ArrayList<>(values);
            List<String> newNos = new java.util.ArrayList<>();
            for (int i = 0; i < newValues.size(); i++) {
                Map<String, String> relabel = i < relabelByGroup.size() ? relabelByGroup.get(i) : Map.of();
                String v = newValues.get(i);
                if (relabel.containsKey(v)) {
                    newValues.set(i, relabel.get(v));
                    skuChanged = true;
                }
                Map<String, String> nos = i < valueNoByGroup.size() ? valueNoByGroup.get(i) : Map.of();
                newNos.add(nos.get(newValues.get(i)));
            }
            String nosJson = writeJson(newNos);
            if (!nosJson.equals(sku.getOptionValueNos())) {
                sku.setOptionValueNos(nosJson);
                skuChanged = true;
            }
            if (skuChanged) {
                sku.setOptionValues(writeJson(newValues));
                sku.setSpec(String.join(" · ", newValues));
                PrdSku row = sku;
                DataScopeContext.executeWithoutScope(() -> skuMapper.updateById(row));
            }
        }
    }

    /** option_values 那种字符串数组；坏 JSON 当空 —— 烘焙对老数据要宽 */
    private static List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            List<String> out = new java.util.ArrayList<>();
            arr.forEach(n -> out.add(n.isNull() ? null : n.asText()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    @Transactional
    public GoodsVO toggle(String merchantNo, String goodsNo, boolean onSale) {
        PrdGoods g = mine(merchantNo, goodsNo);
        // 未过审不能上架：上架是商家自己能按的按钮，能把 AUDITING 推到 C 端的话审核就形同虚设
        if (onSale && !APPROVED.equals(g.getAuditStatus())) {
            // 专用码：此前用的是 ORDER_STATE_ILLEGAL，商家看到「订单状态不允许该操作」
            // 而他手上一张订单都没有
            throw BizException.of(ErrorCode.GOODS_NOT_APPROVED);
        }
        if (onSale) {
            requireCategoryAuthorized(merchantNo, g.getCategoryNo());
        }
        String storeNo = BizContext.current().currentStoreNo();
        /*
         * 闸二：**上架的商品，它的类目必须在这家店的货架上**（TDD-品类约束全链路 §4.3）。
         *
         * 走到这里资质已经过了闸一，所以缺的只可能是「这家店还没摆这个货架」——
         * 那是一次登记，不是一次拒绝，补上即可。硬拒的话，商家会看到一句
         * 「本店不能卖这一类」，而他明明有资质，也确实想卖。
         *
         * 反向的口子（货架被撤而商品还在）由 StoreCategoryService.replace 那侧堵：
         * 底下还有商品的类目删不掉。两侧合起来，「上架商品 ⊆ 本店货架」才是闭的。
         */
        if (onSale && storeNo != null && !storeNo.isBlank()) {
            storeCategoryPort.ensure(merchantNo, storeNo, g.getCategoryNo());
        }
        /*
         * 多门店商家的上下架落在**门店行**上，不动主体的 on_sale。
         *
         * 不分的话，A 店店长点一下「下架」，B 店的货跟着一起没了 —— 而他做的
         * 只是「今天我这儿不卖了」。这与库存那处是同一条理由：钱与货的作用域
         * 必须与操作人能管的范围对齐。
         *
         * 单店（或没有门店上下文）仍改主体级，行为与改造前逐字相同。
         */
        if (onSale) {
            bakeForPublish(g);
        }
        if (perStore(merchantNo, storeNo, goodsNo)) {
            setStoreOnSale(g, storeNo, onSale);
            // 主体级 on_sale 是「这件货整体还卖不卖」的总闸：任一门店在售就得是开的，
            // 否则 storeOnSale 的 && 会把店级的 true 一起吞掉
            boolean anyOn = onSale || storeGoodsRows(goodsNo).stream()
                    .anyMatch(r -> Boolean.TRUE.equals(r.getOnSale()));
            g.setOnSale(anyOn);
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
            syncPool(g, anyOn);
            return toVO(g);
        }

        g.setOnSale(onSale);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        syncPool(g, onSale);
        return toVO(g);
    }

    @Override
    public GoodsVO detailForOps(String goodsNo) {
        /*
         * 不走 toVO()：statusOf/storeSkus 读 BizContext 的「当前门店」，
         * 而运营端请求没有 BizContext —— 这里用主体级口径（opsStatusOf），
         * 与商品池列表同一套状态词。
         */
        return toVOWithoutStoreContext(requireByNoInScope(goodsNo));
    }

    @Override
    @Transactional
    public GoodsVO forceOff(String goodsNo, String reason) {
        if (reason == null || reason.isBlank()) {
            // 与审核驳回同一条规矩：没有原因的处置在申诉时站不住
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdGoods g = requireByNo(goodsNo);
        /*
         * 强制下架 = 撤销过审（TDD D1）。不新增状态值：REJECTED 之后商家改商品
         * 重新提审走的是**既有**链路，B 端也已经会渲染「被驳回 + 原因」。
         * 与首次驳回靠原因前缀区分。
         */
        g.setAuditStatus(REJECTED);
        g.setAuditReason("平台强制下架：" + reason.trim());
        g.setOnSale(false);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        // 店级行全下且**不打 platform_suspended**：商品要重新过审才能回来，
        // 恢复走审核链路，不走门店解除处置那条标记
        for (var row : storeGoodsRows(goodsNo)) {
            if (Boolean.TRUE.equals(row.getOnSale())) {
                row.setOnSale(false);
                DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(row));
            }
        }
        syncPool(g, false);
        return toVOWithoutStoreContext(g);
    }

    @Override
    @Transactional
    public GoodsVO platformSuspendGoods(String goodsNo, String reason) {
        if (reason == null || reason.isBlank()) {
            // 与审核驳回同一条规矩：没有原因的处置在申诉时站不住
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdGoods g = requireByNo(goodsNo);
        // 只有在售的才谈得上「压下架」。已经下架/被驳回的再压一次，
        // 唯一的效果是把上一条更重要的处置原因覆盖掉
        if (!Boolean.TRUE.equals(g.getOnSale()) || REJECTED.equals(g.getAuditStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        /*
         * **不动 auditStatus** —— 这正是与 forceOff 的分界：过审结论还在，
         * 商家处理完问题自己点一下就能重新上架，不必走一遍重新提审。
         */
        g.setAuditReason("平台下架：" + reason.trim());
        g.setOnSale(false);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        for (var row : storeGoodsRows(goodsNo)) {
            if (Boolean.TRUE.equals(row.getOnSale())) {
                row.setOnSale(false);
                DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(row));
            }
        }
        // 撤池：只改 on_sale 不撤池的话，被压下的商品在 C 端还搜得到 ——
        // 处置没有落到买家看得见的地方，就等于没处置
        syncPool(g, false);
        return toVOWithoutStoreContext(g);
    }

    @Override
    @Transactional
    public void platformOfflineStore(String entityNo, String storeNo) {
        List<PrdGoods> all = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getEntityNo, entityNo)));
        for (PrdGoods g : all) {
            List<ai.neargo.shop.product.entity.PrdStoreGoods> rows = storeGoodsRows(g.getGoodsNo());
            boolean managed = !rows.isEmpty();
            boolean onAtStore = managed
                    ? rows.stream().anyMatch(r -> storeNo.equals(r.getStoreNo())
                            && Boolean.TRUE.equals(r.getOnSale()))
                    : Boolean.TRUE.equals(g.getOnSale());
            if (!onAtStore) {
                // 只压当前在售的：商家自己下架的不打标记，解除时才不会替他重新上架
                continue;
            }
            setStoreOnSale(g, storeNo, false);
            markPlatformSuspended(g.getGoodsNo(), storeNo, true);
            boolean anyOn = storeGoodsRows(g.getGoodsNo()).stream()
                    .anyMatch(r -> Boolean.TRUE.equals(r.getOnSale()));
            g.setOnSale(anyOn);
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
            syncPool(g, anyOn);
        }
    }

    @Override
    @Transactional
    public void platformRestoreStore(String entityNo, String storeNo) {
        List<ai.neargo.shop.product.entity.PrdStoreGoods> suspended = DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectList(Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo, storeNo)
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getEntityNo, entityNo)
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getPlatformSuspended, true)));
        for (var row : suspended) {
            row.setOnSale(true);
            row.setPlatformSuspended(false);
            DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(row));
            PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                    goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                            .eq(PrdGoods::getGoodsNo, row.getGoodsNo()).last("limit 1")));
            if (g == null) {
                continue;
            }
            /*
             * 处置期间商品可能被驳回/强制下架（audit_status 变了）——那种行不能跟着回架：
             * 恢复门店不等于恢复商品，商品要走它自己的重新提审链路。
             */
            if (!APPROVED.equals(g.getAuditStatus())) {
                continue;
            }
            if (!Boolean.TRUE.equals(g.getOnSale())) {
                g.setOnSale(true);
                DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
            }
            syncPool(g, true);
        }
    }

    /** ops 视角的整装 VO：与 {@link #toVO} 的差别只在不读 BizContext（无「当前门店」）。 */
    private GoodsVO toVOWithoutStoreContext(PrdGoods g) {
        GoodsVO base = goodsService.detail(g.getGoodsNo());
        return new GoodsVO(base.goodsNo(), base.title(), base.subtitle(), base.cover(),
                base.images(), base.detail(), base.detailImages(),
                base.type(), base.categoryNo(), base.merchant(),
                base.rating(), base.ratingCount(), base.price(), base.originPrice(),
                base.fulfillments(), base.specGroups(), base.skus(), base.sales(),
                base.cutoffAt(), base.arrivalDesc(), base.weighed(), base.origin(),
                base.durationMin(), base.storeName(), base.limitPerUser(), base.onSale(),
                opsStatusOf(g),
                readMap(g.getTitleI18n()), readMap(g.getSubtitleI18n()),
                g.getStdNo(),
                g.getAuditReason(),
                GoodsServiceImpl.groupBuyConf(g),
                // 商家侧回显：不回显的话「打开编辑页再保存一次就把参数清空了」
                readParams(g.getParams()),
                /*
                 * ops 视角不带 hasDraft：它是商家的编辑态提示，审核队列不消费它；
                 * 而且这里查的话会豁免一张已注册表（G1 守卫拦的正是这个）——
                 * 配了「只看某商家」的运营不该有任何一条绕过域的查询。
                 * 审核员要看的「待审草稿内容」另有入口（步骤 4 的欠口，见工单）。
                 */
                null,
                // 无门店上下文（ops 视角 / 单店）：storeOnSale 留空 = 未按店管理
                null);
    }

    /**
     * 按 goodsNo 取商品，**不接数据域** —— 写路径（强制下架、平台压下架）用。
     *
     * <p>与读路径分成两个方法而不是加一个布尔参数（批② T2 的同一条结论）：
     * 参数化的话，下一个人在处置路径上传错一次，就把一次平台处置变成了
     * <b>静默失败</b> —— 按钮点了、返回 200、商品还在架上。
     */
    private PrdGoods requireByNo(String goodsNo) {
        PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }

    /**
     * 同上，但**接数据域**（批③）—— 运营端的只读详情用。
     *
     * <p>不在自己数据域内的商品返回 {@code NOT_FOUND} 而不是 403：
     * 「这件商品不归你管」与「这件商品不存在」在运营端应当是同一个回答 ——
     * 403 会告诉他这个货号真实存在，那本身就是一次信息泄露。
     */
    private PrdGoods requireByNoInScope(String goodsNo) {
        PrdGoods g = goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1"));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }

    private void markPlatformSuspended(String goodsNo, String storeNo, boolean flag) {
        var row = DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectOne(Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo, storeNo)
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, goodsNo)
                        .last("limit 1")));
        if (row != null) {
            row.setPlatformSuspended(flag);
            DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(row));
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 这次上下架该落在门店行上，还是主体的 {@code on_sale} 上。
     *
     * <p>判据就是「这个主体有几家店」——<b>多门店时每一次上下架都落成门店行，
     * 包括在默认门店做的那次</b>。
     *
     * <p>这里原先写的是「操作发生在非默认门店」，被测试当场抓住：
     * 老板在默认店 A 上架（走主体级，不写行），再去 B 店上架（写了行），
     * 此时 A 店因为「有行了但没有 A 的行」而变成未上架 —— 他什么都没对 A 做过。
     *
     * <p>单店商家恒为 false，行为与改造前逐字相同。
     */
    private boolean perStore(String merchantNo, String storeNo, String goodsNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return false;
        }
        return merchantPort.storeNos(merchantNo).size() > 1 || !storeGoodsRows(goodsNo).isEmpty();
    }

    /**
     * 写一条门店上架行（有则更新）。**只在多门店时被调用**。
     *
     * <p><b>第一次转成店级管理时，先把其他门店的现状固化下来</b>。
     *
     * <p>不这么做的后果是实测撞到的：两家店都在卖，商家在 A 店点「下架」——
     * 只写了 A 的行，B 因为「有行了但没有自己的行」而一起变成未上架。
     * 他做的只是「A 店今天不卖」，B 店的货却跟着没了，而且没有任何提示。
     *
     * <p>测试当时没抓到，是因为用例先把两家店都显式上架过一遍 ——
     * 正好绕开了转换那一刻。这类「迁移瞬间」的缺陷，写用例时最容易被跳过。
     */
    private void setStoreOnSale(PrdGoods g, String storeNo, boolean onSale) {
        if (storeGoodsRows(g.getGoodsNo()).isEmpty()) {
            boolean current = Boolean.TRUE.equals(g.getOnSale());
            for (String other : merchantPort.storeNos(g.getEntityNo())) {
                if (other.equals(storeNo)) {
                    continue;
                }
                ai.neargo.shop.product.entity.PrdStoreGoods seed =
                        new ai.neargo.shop.product.entity.PrdStoreGoods();
                seed.setStoreNo(other);
                seed.setGoodsNo(g.getGoodsNo());
                seed.setEntityNo(g.getEntityNo());
                seed.setOnSale(current);
                DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.insert(seed));
            }
        }
        writeStoreOnSale(g, storeNo, onSale);
    }

    private void writeStoreOnSale(PrdGoods g, String storeNo, boolean onSale) {
        ai.neargo.shop.product.entity.PrdStoreGoods row =
                DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.selectOne(
                        Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                                .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo, storeNo)
                                .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, g.getGoodsNo())
                                .last("limit 1")));
        if (row == null) {
            row = new ai.neargo.shop.product.entity.PrdStoreGoods();
            row.setStoreNo(storeNo);
            row.setGoodsNo(g.getGoodsNo());
            row.setEntityNo(g.getEntityNo());
            row.setOnSale(onSale);
            ai.neargo.shop.product.entity.PrdStoreGoods toInsert = row;
            DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.insert(toInsert));
            return;
        }
        row.setOnSale(onSale);
        ai.neargo.shop.product.entity.PrdStoreGoods toUpdate = row;
        DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(toUpdate));
    }

    /**
     * 类目经营准入：这家店有没有获批经营这个类目。
     *
     * <p><b>校验点选在「上架」而不是「保存商品」</b>：商家把草稿归到一个自己还没资质的
     * 类目下不该被拦 —— 他可能正准备去申请。真正不能发生的是这件商品**对消费者可见**。
     *
     * <p>类目没挂 {@code requiredCode} 即无门槛，直接放行；挂了但商家没有，
     * 报一个专用错误码，让端上能把「缺哪张资质、去哪申请」说出来 ——
     * 通用的「请求参数有误」会让商家反复改商品信息，而问题根本不在商品上。
     */
    private void requireCategoryAuthorized(String merchantNo, String categoryNo) {
        boolean categorized = categoryNo != null && !categoryNo.isBlank();
        String required = categorized ? categoryService.requiredCodeOf(categoryNo) : null;
        boolean needsQualification = required != null && !required.isBlank();

        /*
         * 弱主体准入（保证金 / 限品类）。
         *
         * <b>放在所有 early return 之前</b>，因为保证金与类目无关：小微卖什么都要有钱兜底。
         * 上一版放在「没归类就 return」之后，于是**不填类目的商品完全绕过这道闸**——
         * 而 categoryNo 是选填的，绕过它只需要少填一个字段。
         * 「无门槛类目」（requiredCode 为空）与「无类目」（categoryNo 为空）是两件事，
         * 上一版的注释只覆盖了前者，测试也只传了前者，所以两边都没发现。
         */
        admissionPort.requireListingAllowed(merchantNo, categoryNo, needsQualification);

        if (!categorized) {
            // 没归类的商品不卡在资质这一关：归类是否必填是另一个决定，不该由准入校验顺手做掉
            return;
        }
        if (!needsQualification) {
            return;
        }
        if (!merchantPort.authorizedCategoryCodes(merchantNo).contains(required)) {
            /*
             * **暂时只记不拦**（2026-08-23，shop.category.gate.enforce=false）。
             *
             * <p>决定的背景：线上 379 件商品里 267 件落在带门槛的类目下，而在受理入口
             * （B 端传证 + 运营按证授码）铺开之前，这道闸对所有人都是关着的 ——
             * 拦住的不是无证经营，是平台自己还没建好的那条路。
             *
             * <p>打开它的前提有两个，缺一个都不该开：商家侧能传证（已有「我的资质」），
             * 运营侧有人在按证授码（已有一键勾选）。等这两件事跑起来、
             * 存量商家的码补齐了，把 enforce 打成 true 就是一行配置。
             *
             * <p>记 WARN 而不是静默放行：它是「本该被拦下的一次上架」的唯一痕迹，
             * 开闸之前要拿这个数判断影响面。
             */
            if (!gateEnforced()) {
                log.warn("[类目闸] 放行未授权上架：merchant={} category={} 需要码={}（enforce=false）",
                        merchantNo, categoryNo, required);
                return;
            }
            throw BizException.of(ErrorCode.CATEGORY_NOT_AUTHORIZED);
        }
        /*
         * 资质过期也不能上架。
         *
         * **这一道与 category_codes 那道判的不是同一件事**：后者判「当初批没批过」，
         * 是审核时写死的一串编码，证过期了它不会变；这一道判「现在还有效吗」。
         * 只有前者的话，商家的食品经营许可证到期后什么都不用做，
         * 商品继续在架、继续能上新，而平台收不到任何信号。
         *
         * 与定时扫描是两道防线，针对不同时机：任务覆盖「已经在架的」，
         * 这里覆盖「正要上架的」—— 任务有间隔，上架随时发生。
         */
        if (merchantPort.hasExpiredQualification(merchantNo)) {
            // 同上：闸关着的时候，过期也只记不拦 —— 两条一起开、一起关，
            // 否则会出现「没证的能上、证过期的不能上」这种解释不通的中间态
            if (!gateEnforced()) {
                log.warn("[类目闸] 放行资质过期的上架：merchant={}（enforce=false）", merchantNo);
                return;
            }
            throw BizException.of(ErrorCode.QUALIFICATION_EXPIRED);
        }
    }

    /**
     * 同步社区池。<b>上架的真正含义是「进哪些社区的池」</b> ——
     * C 端按社区查商品读的就是 {@code prd_community_pool}，不是 {@code on_sale}。
     *
     * <p>不做这一步的后果与入驻那个缺口一模一样：商家点了上架、列表里显示"在售"、
     * 而买家<b>在任何地方都搜不到这件货</b>，且没有任何报错。
     * 上一次是商家没进社区，这一次是商品没进池 —— 同一个形状的故障。
     *
     * <p>范围来自 {@link MerchantQueryPort#reachableCommunities}（ADR-009 三档已展开），
     * 不是 product 域自己去读商家的社区表：那样两处口径迟早分岔。
     */
    @Override
    @Transactional
    public int resyncCommunityPools(String entityNo) {
        if (entityNo == null || entityNo.isBlank()) {
            return 0;
        }
        List<PrdGoods> all = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getEntityNo, entityNo)));
        for (PrdGoods g : all) {
            // 用主体级总闸，与上下架那条链路同一个判据。下架的走 syncPool(false) —— 
            // 它会把残留的池行撤掉，这正是「范围改小了」要的效果
            syncPool(g, Boolean.TRUE.equals(g.getOnSale()));
        }
        return all.size();
    }

    @Override
    public int resyncAllCommunityPools() {
        List<String> entityNos = DataScopeContext.executeWithoutScope(() ->
                        goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                                .select(PrdGoods::getEntityNo)))
                .stream().map(PrdGoods::getEntityNo)
                .filter(no -> no != null && !no.isBlank())
                .distinct().toList();
        int n = 0;
        for (String entityNo : entityNos) {
            n += resyncCommunityPools(entityNo);
        }
        log.info("[pool] 全量重建社区池：{} 个主体 / {} 件商品", entityNos.size(), n);
        return n;
    }

    /**
     * 算不出距离时写进 {@code sort_weight} 的数。
     *
     * <p><b>不能写 0</b>：0 会让缺坐标的门店排到最前面，恰好与「就近展示」相反。
     * 取一个比地球上任何两点距离都大的数，缺坐标的店自然排到最后。
     */
    private static final int UNKNOWN_DISTANCE_M = 99_999_999;

    /**
     * 重建这件货的社区池。<b>按门店算</b>（可见性按门店算 · 第 3 步）。
     *
     * <p>口径：
     * <pre>
     * 货 G 在社区 C 可见  ⟺  ∃ 门店 S：S 在架卖 G  ∧  S 可达 C
     * </pre>
     *
     * <p>改造之前这里算的是「主体可达 × 商品在架」，两个问题：
     * <ul>
     *   <li>A 店的货会出现在只有 B 店服务的社区里（可达取的是主体并集）</li>
     *   <li>{@code prd_store_goods}（门店选品）在整条可见性链路上一个读者都没有</li>
     * </ul>
     *
     * <p><b>「这家店卖不卖」沿用三态语义</b>（与 {@code prd_store_stock} 逐字一致）：
     * 一条店级行都没有 → 主体下所有 ACTIVE 门店都算在架；有了任意一条 → 转店级管理，
     * 没有行的店视为未上架。改成「没有行就不卖」的话，存量商家（全部没有店级行）
     * 会在切口径当天从 C 端集体消失。
     */
    /**
     * 把上架状态送给进销存 —— 那边靠它在挑货弹层里标出「已下架」。
     *
     * <p><b>挂在 syncPool 里而不是各个入口</b>：上下架有十个调用点
     *（手动、平台强制下架、审核通过、店级开关、批量……），逐个发必漏一个，
     * 而漏掉的那个会让物料上的标记停在上一个状态 —— <b>那比没有标记更坏</b>。
     * syncPool 是十处的唯一汇聚点，语义也正好是「这件货整体还卖不卖变了」。
     *
     * <p>没有 SKU 就不发：进销存那边认的是 skuNo，一条都没有的话这个事件
     * 没有任何落点，发出去只是让 outbox 多一行永远没人消费的记录。
     */
    private void publishOnSaleChanged(PrdGoods g, boolean onSale) {
        List<String> skuNos = DataScopeContext.executeWithoutScope(() ->
                        skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                                .eq(PrdSku::getGoodsNo, g.getGoodsNo())))
                .stream()
                .map(PrdSku::getSkuNo)
                .filter(java.util.Objects::nonNull)
                // SKU 行是 (skuNo × market) 的，而库存不分市场 —— 与 publishSkuUpserted 同一条去重
                .distinct()
                .toList();
        if (skuNos.isEmpty()) {
            return;
        }
        events.publish(new ai.neargo.shop.spi.product.ProductEvents.GoodsOnSaleChanged(
                g.getGoodsNo(), g.getEntityNo(), onSale, skuNos));
    }

    private void syncPool(PrdGoods g, boolean onSale) {
        publishOnSaleChanged(g, onSale);
        List<PrdCommunityPool> existing = DataScopeContext.executeWithoutScope(() ->
                poolMapper.selectList(Wrappers.<PrdCommunityPool>lambdaQuery()
                        .eq(PrdCommunityPool::getGoodsNo, g.getGoodsNo())));
        if (!onSale) {
            // 下架 = 从所有池里撤出。留在池里的话 C 端还能搜到，点进去才发现买不了
            for (PrdCommunityPool row : existing) {
                DataScopeContext.executeWithoutScope(() -> poolMapper.deleteById(row.getId()));
            }
            return;
        }

        // 想要的 (社区, 门店) 组合
        Map<String, String> want = new LinkedHashMap<>();   // key = 社区|门店
        List<String> sellingStores = storesSelling(g);
        for (String storeNo : sellingStores) {
            for (String communityNo : merchantPort.reachableCommunities(g.getEntityNo(), storeNo)) {
                want.put(communityNo + "|" + storeNo, communityNo);
            }
        }

        /*
         * **空集要喊出来。**上架 + 一个 (社区,门店) 都没有 = 商品从所有池里撤出，
         * 而商家侧仍显示「在售」—— 买家在任何地方都搜不到，且不报任何错。
         *
         * 空集本身是合法语义（PICKUP 没框范围 = 没有落点），所以不在这里拦，
         * 但它 99% 是配置缺失而不是本意。日志是运营能看见这件事的唯一通道。
         */
        if (want.isEmpty()) {
            log.warn("[pool] 商家 {} 上架 {} 但没有任何「门店 × 可达社区」组合 —— "
                            + "商品对所有买家不可见。多半是没配经营范围（PICKUP 必须框范围），"
                            + "也可能是所有门店都没把它摆上货架（在架门店 {} 家）",
                    g.getEntityNo(), g.getGoodsNo(), sellingStores.size());
        }

        // 差集增删，不是「先全删再全插」：唯一键含 (community_no, goods_no, store_no)
        // 而删除是逻辑删 —— 删完再插同一组会撞键
        Set<String> have = new java.util.HashSet<>();
        for (PrdCommunityPool row : existing) {
            String key = row.getCommunityNo() + "|" + nz(row.getStoreNo());
            have.add(key);
            if (!want.containsKey(key)) {
                DataScopeContext.executeWithoutScope(() -> poolMapper.deleteById(row.getId()));
            }
        }

        Map<String, Integer> distances = distancesFor(want.values(), sellingStores);
        for (Map.Entry<String, String> e : want.entrySet()) {
            if (have.contains(e.getKey())) {
                continue;
            }
            String communityNo = e.getValue();
            String storeNo = e.getKey().substring(e.getKey().indexOf('|') + 1);
            /*
             * 先试着复活被逻辑删的行。**下架是逻辑删，而唯一键不含 deleted** ——
             * 直接 insert 会撞唯一键，表现为上架接口 500，而商家看到的是「系统开小差」。
             */
            if (DataScopeContext.executeWithoutScope(() ->
                    poolMapper.revive(communityNo, g.getGoodsNo(), storeNo)) > 0) {
                continue;
            }
            PrdCommunityPool row = new PrdCommunityPool();
            row.setCommunityNo(communityNo);
            row.setGoodsNo(g.getGoodsNo());
            row.setEntityNo(g.getEntityNo());
            row.setStoreNo(storeNo);
            // 就近展示：一件货被两家店摆着、都服务这个社区时，C 端按这个数升序取第一条
            row.setSortWeight(distances.getOrDefault(communityNo + "|" + storeNo, UNKNOWN_DISTANCE_M));
            DataScopeContext.executeWithoutScope(() -> poolMapper.insert(row));
        }
    }

    /**
     * 主体下**在架卖这件货**的门店。三态语义见 {@link #syncPool}。
     *
     * <p>只算 ACTIVE 门店：停用的店不该把货带进任何社区。
     */
    private List<String> storesSelling(PrdGoods g) {
        List<String> activeStores = merchantPort.storeNos(g.getEntityNo());
        if (activeStores.isEmpty()) {
            return List.of();
        }
        List<ai.neargo.shop.product.entity.PrdStoreGoods> rows = storeGoodsRows(g.getGoodsNo());
        if (rows.isEmpty()) {
            // 一条店级行都没有 → 走主体总闸，所有门店都算在架（存量商家全在这一支）
            return activeStores;
        }
        Set<String> on = rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.getOnSale()))
                .map(ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo)
                .collect(java.util.stream.Collectors.toSet());
        return activeStores.stream().filter(on::contains).toList();
    }

    /**
     * 每个 (社区, 门店) 的直线距离（米）。<b>批量取坐标</b> ——
     * 一次上架要建几十行，逐个查就是几十次往返。
     *
     * <p>任一端没标过点就不进结果，调用方回落到 {@link #UNKNOWN_DISTANCE_M}。
     */
    private Map<String, Integer> distancesFor(java.util.Collection<String> communityNos,
                                              List<String> storeNos) {
        if (communityNos.isEmpty() || storeNos.isEmpty()) {
            return Map.of();
        }
        Map<String, int[]> cc = communityQueryPort.coordsOfCommunities(new java.util.HashSet<>(communityNos));
        Map<String, int[]> sc = merchantPort.coordsOfStores(storeNos);
        Map<String, Integer> out = new java.util.HashMap<>();
        for (Map.Entry<String, int[]> c : cc.entrySet()) {
            for (Map.Entry<String, int[]> st : sc.entrySet()) {
                out.put(c.getKey() + "|" + st.getKey(),
                        distanceMeters(c.getValue(), st.getValue()));
            }
        }
        return out;
    }

    /**
     * 两点直线距离（米），球面近似。
     *
     * <p>**只用来排序，不展示给人**，所以不必上 Haversine 的精度 ——
     * 但也不能用平面欧氏：经度一度的实际长度随纬度收缩，
     * 不乘 cos(lat) 的话南北向会被系统性地算短。
     */
    private static int distanceMeters(int[] a, int[] b) {
        double latA = a[0] / 1e6;
        double latB = b[0] / 1e6;
        double dLat = (latA - latB) * 111_320.0;
        double dLng = (a[1] - b[1]) / 1e6 * 111_320.0
                * Math.cos(Math.toRadians((latA + latB) / 2));
        double d = Math.sqrt(dLat * dLat + dLng * dLng);
        return d >= UNKNOWN_DISTANCE_M ? UNKNOWN_DISTANCE_M - 1 : (int) Math.round(d);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    @Transactional
    public GoodsVO saveStock(String merchantNo, String goodsNo, String skuNo, int stock) {
        PrdGoods g = mine(merchantNo, goodsNo);
        if (stock < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **这个 SKU 已经按店管理时，写到当前门店去** —— 否则写与读不是同一个数。
         *
         * 主体总量与分店库存是两笔账：SKU 一旦有过分店库存行，
         * 商品详情读的就是分店那份（见 {@code applyStoreStock}）。这条端点写的是主体总量，
         * 于是商家点「保存」→ **返回成功 → 页面数字纹丝不动 → 没有任何提示**。
         * 实测：store-stock 设 33 → 读回 33；stock 设 99 → code 0，读回还是 33。
         *
         * b-app 已经绕开了（多店走 store-stock、单店走 stock），但**两边判据不同**：
         * 端上按「商家是否多店」，后端按「这个 SKU 是否已按店管理」——
         * 单店商家若有按店库存的 SKU（比如关掉过一家店），就会落进这个静默无效。
         *
         * 改成「写落到读的地方」而不是报错：报错的话，那位商家在界面上
         * **没有任何可用路径**去改这件商品的库存（单店模式不显示分店库存入口）。
         */
        if (isStoreManaged(skuNo)) {
            String storeNo = ai.neargo.shop.auth.BizContext.current().currentStoreNo();
            if (storeNo != null && !storeNo.isBlank()) {
                return saveStoreStock(merchantNo, storeNo, goodsNo, skuNo, stock);
            }
        }
        List<PrdSku> rows = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo).eq(PrdSku::getSkuNo, skuNo)));
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * **走 StockPort，不自己 update。**
         *
         * 商家有两个改库存的入口：这一个与库存页的「改数」。原本这里直接
         * `updateById`、那边走进销存的盘点单 —— 两个都在 B 端、都归商家、
         * 都叫「改库存」，却写进两本互不知道的账。搬运之后同一件货就有了
         * 两个数、两个改法，而改任一个另一个都不知道。
         *
         * 收进 Port 之后，真相源在哪它就落到哪。**入口可以有两个，账只能有一本。**
         *
         * 顺带修掉一个并发缺陷：原来是「先查再 updateById」，
         * 中间那笔销售会被覆盖掉；Port 那边是带条件的 UPDATE。
         */
        for (PrdSku row : rows) {
            // 库存不分市场：货就那么多，卖到哪个市场都是同一批。
            // 价格分市场、库存不分 —— 这两件事的口径不同，正是分开存的理由
            stockPort.setOnHand(row.getSkuNo(), null, stock, "OTHER");
        }
        // 补货**不触发重审**：这是每天都在做的事，走完整保存等于每次补货都要重新过审
        return toVO(g);
    }

    @Override
    @Transactional
    public GoodsVO saveStoreStock(String merchantNo, String storeNo, String goodsNo,
                                  String skuNo, int stock) {
        PrdGoods g = mine(merchantNo, goodsNo);
        if (stock < 0 || storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectCount(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo).eq(PrdSku::getSkuNo, skuNo))) > 0;
        if (!exists) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        PrdStoreStock row = DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectOne(Wrappers.<PrdStoreStock>lambdaQuery()
                        .eq(PrdStoreStock::getStoreNo, storeNo)
                        .eq(PrdStoreStock::getSkuNo, skuNo)));
        if (row == null) {
            // 这家店还没有这一行 —— 先建出来（Port 的 setOnHand 只改不建）
            PrdStoreStock toInsert = new PrdStoreStock();
            toInsert.setStoreNo(storeNo);
            toInsert.setSkuNo(skuNo);
            toInsert.setEntityNo(merchantNo);
            toInsert.setLockedStock(0);
            toInsert.setStock(0);
            DataScopeContext.executeWithoutScope(() -> storeStockMapper.insert(toInsert));
        }
        // 与主体级同一条：走 Port，两本账才不会分叉（见 saveStock 的说明）
        stockPort.setOnHand(skuNo, storeNo, stock, "OTHER");
        return toVO(g);
    }

    @Override
    @Transactional
    public GoodsVO submitForAudit(String merchantNo, String goodsNo) {
        PrdGoods g = mine(merchantNo, goodsNo);
        /*
         * 只有草稿会动。已在审 / 已过审 / 已驳回调它**什么都不做**，不报错 ——
         * 端上重复点击是常态，一个「状态不允许」只会让商家以为提交失败又点一次。
         */
        if (DRAFT.equals(g.getAuditStatus())) {
            /*
             * 禁售词前置校验（商品①）。**拦在进队列之前**。
             *
             * 此前只有事后驳回：带违禁词的标题会进审核队列、占一个审核员的时间、
             * 再被驳回，而商家隔几天才知道要改哪个字。
             * 2026-09-03 线上 194 件卡在审核里，而这条链的入口没有任何前置检查。
             *
             * 报错**点名那个词**：驳回理由是人手写的一句话，商家读完常常还是
             * 不知道改哪儿；而「标题里的『XX』不能用」他当场就能改。
             */
            bannedWords.firstHit(g.getTitle()).ifPresent(hit -> {
                throw BizException.of(ErrorCode.BAD_REQUEST,
                        "标题里的「" + hit.word() + "」不能用"
                                + (hit.reason() == null || hit.reason().isBlank()
                                        ? "" : "：" + hit.reason()));
            });
            if (!auditRequired()) {
                // 免审：提交即编译上架。编译失败（80017）直接抛给商家 ——
                // 他就在屏幕前，逐条点名比留一个「审核中」的假状态有用
                bakeForPublish(g);
                g.setAuditStatus(APPROVED);
                g.setOnSale(true);
                g.setPendingOnSale(false);
                log.info("[免审] goods.audit=off，提交即过审上架：goods={} merchant={}",
                        g.getGoodsNo(), merchantNo);
                DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
                syncPool(g, true);
                return toVO(g);
            }
            g.setAuditStatus(AUDITING);
            /*
             * **提交审核就是「我要卖它」**，把意向记下（V247）。
             * 新品此前没有任何地方表达过这件事：on_sale 一直是 false，
             * 过审后仍是 false，于是他提交完以为在卖了，其实一件也卖不出去。
             */
            g.setPendingOnSale(true);
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        }
        return toVO(g);
    }

    @Override
    @Transactional
    public GoodsVO savePresaleCutoff(String merchantNo, String goodsNo, Long cutoffAt,
                                     String arrivalDesc) {
        PrdGoods g = mine(merchantNo, goodsNo);
        /*
         * **只有生鲜有截单**。别的品类改它是无声无息的一次写入 ——
         * 字段进了库，而没有任何一条链路会读它，商家以为自己设了个什么。
         */
        if (!"FRESH".equals(g.getType())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (cutoffAt != null) {
            g.setCutoffAt(cutoffAt);
        }
        if (arrivalDesc != null) {
            g.setArrivalDesc(arrivalDesc.isBlank() ? null : arrivalDesc);
        }
        /*
         * ★ **不动 auditStatus、不下架** —— 这正是它与 save() 的分界。
         *
         * 生鲜商家每天晚上定明天的截单；走 save() 的话每天都要重审一次，
         * 而重审期间商品是下架的：改一次截单等于停一天生意。
         * 改的是「今天几点前下单」，不是商品本身 —— 审核结论不该因此失效。
         */
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        return toVO(g);
    }

    @Override
    @Transactional
    public GoodsVO saveStorePrice(String merchantNo, String storeNo, String goodsNo,
                                  String skuNo, Long price, Long originPrice) {
        PrdGoods g = mine(merchantNo, goodsNo);
        if (storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectCount(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo).eq(PrdSku::getSkuNo, skuNo))) > 0;
        if (!exists) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var existing = DataScopeContext.executeWithoutScope(() ->
                storePriceMapper.selectOne(Wrappers.<ai.neargo.shop.product.entity.PrdStorePrice>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStorePrice::getStoreNo, storeNo)
                        .eq(ai.neargo.shop.product.entity.PrdStorePrice::getSkuNo, skuNo)
                        .eq(ai.neargo.shop.product.entity.PrdStorePrice::getMarket, HOME_MARKET)));
        /*
         * **传空 = 取消本店单独定价**，回到主体价。
         *
         * 这条要有：没有它，商家一旦给某店定过价就再也回不去 ——
         * 「改成和总部一样」只能靠他自己抄一遍数字，而抄错没有任何一处会拦。
         */
        if (price == null) {
            if (existing != null) {
                DataScopeContext.executeWithoutScope(() -> storePriceMapper.deleteById(existing.getId()));
            }
            return toVO(g);
        }
        if (price < 0 || (originPrice != null && originPrice < 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (existing == null) {
            var row = new ai.neargo.shop.product.entity.PrdStorePrice();
            row.setStoreNo(storeNo);
            row.setSkuNo(skuNo);
            row.setEntityNo(merchantNo);
            row.setMarket(HOME_MARKET);
            row.setPrice(price);
            row.setOriginPrice(originPrice);
            DataScopeContext.executeWithoutScope(() -> storePriceMapper.insert(row));
        } else {
            existing.setPrice(price);
            existing.setOriginPrice(originPrice);
            DataScopeContext.executeWithoutScope(() -> storePriceMapper.updateById(existing));
        }
        return toVO(g);
    }

    /** 当前门店给这些 SKU 单独定过的价。没定过的不出现 —— 调用方据此显示「同主体价」 */
    private java.util.Map<String, Long> storePriceOf(String storeNo, java.util.List<String> skuNos) {
        if (storeNo == null || storeNo.isBlank() || skuNos == null || skuNos.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (var r : DataScopeContext.executeWithoutScope(() ->
                storePriceMapper.selectList(Wrappers.<ai.neargo.shop.product.entity.PrdStorePrice>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStorePrice::getStoreNo, storeNo)
                        .eq(ai.neargo.shop.product.entity.PrdStorePrice::getMarket, HOME_MARKET)
                        .in(ai.neargo.shop.product.entity.PrdStorePrice::getSkuNo, skuNos)))) {
            if (r.getPrice() != null) {
                out.put(r.getSkuNo(), r.getPrice());
            }
        }
        return out;
    }

    /**
     * 这个 SKU 是否已经切成「按店管理」。判据与 {@code applyStoreStock} 一致：
     * <b>只要存在任何一行分店库存</b>，读取口径就已经换成分店那份了。
     */
    private boolean isStoreManaged(String skuNo) {
        return skuNo != null && !skuNo.isBlank()
                && DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectCount(Wrappers.<PrdStoreStock>lambdaQuery()
                        .eq(PrdStoreStock::getSkuNo, skuNo))) > 0;
    }

    @Override
    public java.util.Map<String, Integer> outOfStockCountByStore(String merchantNo,
                                                                 java.util.Collection<String> storeNos) {
        // 空集合 = 一家都不看（fail-closed），与订单侧同一套口径。
        // 当成「不过滤」的话，一个没被授权到任何门店的店员会看到全主体的缺货
        if (merchantNo == null || merchantNo.isBlank() || (storeNos != null && storeNos.isEmpty())) {
            return java.util.Map.of();
        }
        List<PrdStoreStock> rows = DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                        .eq(PrdStoreStock::getEntityNo, merchantNo)
                        .in(storeNos != null, PrdStoreStock::getStoreNo,
                                storeNos == null ? List.of() : storeNos)));
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (PrdStoreStock r : rows) {
            if (r.getStoreNo() == null || r.getStoreNo().isBlank()) {
                continue;
            }
            /*
             * 可用量 = stock − locked。**扣掉锁定量而不是只看 stock**：
             * 20 件全被未付款的单锁着，货架上就是空的 —— 只看 stock 的话
             * 商家在「缺货 0」的页面上眼看着卖不出去。
             */
            int available = nzi(r.getStock()) - nzi(r.getLockedStock());
            if (available <= 0) {
                out.merge(r.getStoreNo(), 1, Integer::sum);
            }
        }
        return out;
    }

    private static int nzi(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 「不传 = 不改，传空串 = 清空」这一口径的公共写法。
     *
     * <p>三个字段各写一遍 if 的话，迟早有一个漏掉「空串 = 清空」那一半 ——
     * 而那一半的症状是「商家把货号删掉、保存、它又回来了」，不报错。
     */
    private static String blankToNull(String incoming, String current) {
        if (incoming == null) {
            return current;             // 不传 = 不改
        }
        String v = incoming.trim();
        return v.isEmpty() ? null : v;  // 空串 = 清空
    }

    @Override
    @Transactional
    public GoodsVO audit(String goodsNo, boolean approved, String reason) {
        if (!approved && (reason == null || reason.isBlank())) {
            // 不写理由的驳回等于让商家猜着改
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        g.setAuditStatus(approved ? APPROVED : REJECTED);
        // 原因落在商品行上（V96）：它是商家能看到的那半边。过审清空 ——
        // 旧原因留着会被当成「还有问题没改完」
        g.setAuditReason(approved ? null : reason.trim());
        if (!approved) {
            // 驳回同时强制下架**并撤出社区池**：只改 on_sale 不撤池的话，
            // 被驳回的商品在 C 端还搜得到 —— 审核结论没有落到买家看得见的地方
            g.setOnSale(false);
        } else {
            /*
             * **过审时把上架意向兑现**（V247）。
             *
             * 意向只有两个来源：他点过「提交审核」（新品），或者这件货本来就在卖
             * （改了一下送去重审）。两者都是他自己表达过的，过审是平台同意 ——
             * 再要一次点击不增加任何信息。
             *
             * <b>不是无条件置真。</b>试过那么写，10 条测试红：
             *   - M9aOpsFlowTest.goodsAuditQueueOnlyPending 断言过审后 OFF_SALE ——
             *     「过审 ≠ 上架」是既有设计，不是疏漏；
             *   - StoreGoodsFlowTest.storeWithoutRowIsNotOnSale ——
             *     主体级 on_sale 一开，没有店级行的门店会跟着一起在架，
             *     正好与商家刚做的事相反。
             * 只恢复他真的表达过的那一份，这两条就都不受影响。
             */
            PrdGoodsDraft submitted = DataScopeContext.executeWithoutScope(() ->
                    draftMapper.selectOne(Wrappers.<PrdGoodsDraft>lambdaQuery()
                            .eq(PrdGoodsDraft::getGoodsNo, g.getGoodsNo())
                            .eq(PrdGoodsDraft::getStatus, PrdGoodsDraft.SUBMITTED).last("limit 1")));
            if (submitted != null) {
                /*
                 * 双版本的过审 = 换版（swapFromDraft 内部会编译+保持在售+删草稿）。
                 * 换版失败（草稿引用了这期间停用的档）不打回审核结论 —— 保持旧版在售，
                 * 草稿退回 EDITING，商家重新发布时得到 80017 的逐条点名。
                 */
                try {
                    return swapFromDraft(g, submitted);
                } catch (BizException e) {
                    log.warn("[换版] 过审换版失败，旧版继续卖、草稿退回编辑态：goods={} msg={}",
                            g.getGoodsNo(), e.getMessage());
                    submitted.setStatus(PrdGoodsDraft.EDITING);
                    DataScopeContext.executeWithoutScope(() -> draftMapper.updateById(submitted));
                    g.setAuditStatus(APPROVED);
                    DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
                    return toVO(g);
                }
            }
            if (Boolean.TRUE.equals(g.getPendingOnSale())) {
                /*
                 * 编译点的第二入口。这里烘焙失败**不打回审核**（审核员改不了商家的规格，
                 * 拒他等于把商家的问题算在审核头上）—— 改成不恢复上架 + 记 warn：
                 * 商家看到「过审但没上架」，手动上架时会撞到 80017 的逐条点名。
                 */
                try {
                    bakeForPublish(g);
                    g.setOnSale(true);
                } catch (BizException e) {
                    log.warn("[上架烘焙] 过审商品的规格解析失败，保持下架待商家处理：goods={} msg={}",
                            g.getGoodsNo(), e.getMessage());
                }
            }
        }
        // 用完清零：留着的话下一次审核会把一个过期的意向再兑现一遍
        g.setPendingOnSale(false);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        syncPool(g, Boolean.TRUE.equals(g.getOnSale()));
        return toVO(g);
    }

    // ---------------------------------------------------------------- 规格模板

    @Override
    public List<SpecTemplateVO> specTemplates(String merchantNo, String categoryType, String categoryNo) {
        var w = Wrappers.<PrdSpecTemplate>lambdaQuery()
                /*
                 * 归档的不下发（V102）。**归档了商家还能选，等于没归档** ——
                 * 而运营会以为自己把那套错的规格下线了，直到发现新品还在用它。
                 * 存量行的 status 由迁移的 DEFAULT 'ACTIVE' 兜住。
                 */
                .eq(PrdSpecTemplate::getStatus, PrdSpecTemplate.ACTIVE)
                // 平台模板 + 我自己的。别家商家自存的模板与我无关
                .and(q -> q.eq(PrdSpecTemplate::getScope, PrdSpecTemplate.PLATFORM)
                        .or(o -> o.eq(PrdSpecTemplate::getScope, PrdSpecTemplate.MERCHANT)
                                .eq(PrdSpecTemplate::getEntityNo, merchantNo)));
        if (categoryType != null && !categoryType.isBlank()) {
            // 品类过滤只作用于平台模板：商家自存的模板不限品类（他自己知道用在哪）
            w.and(q -> q.eq(PrdSpecTemplate::getCategoryType, categoryType)
                    .or(o -> o.isNull(PrdSpecTemplate::getCategoryType)));
        }
        /*
         * **别家类目的专属模板要挡掉。**
         *
         * 类目级模板的 category_type 也填着（不填会变成谁都查不到的孤儿行），
         * 所以只按品类过滤的话，选「休闲零食」会连「手机数码 → 颜色/存储」
         * 一起推过来 —— 它们同属 NORMAL。
         */
        String picked = categoryNo == null || categoryNo.isBlank() ? null : categoryNo;
        w.and(q -> {
            q.isNull(PrdSpecTemplate::getCategoryNo);
            if (picked != null) {
                q.or(o -> o.eq(PrdSpecTemplate::getCategoryNo, picked));
            }
        });

        List<SpecTemplateVO> legacy = DataScopeContext.executeWithoutScope(() -> templateMapper.selectList(w))
                .stream().map(this::toVO).toList();
        /*
         * **类目级规格来自规格库（V195），品类兜底与商家自存仍来自老表。**
         *
         * 规格库那边一个类目能给出 3–5 个维度、值有编号也有归一量；老表那 22 条里
         * 类目级只覆盖 13 个类目，且值只是 JSON 里的字符串。所以类目一确定就以新库为准，
         * 老库只剩两件事：没配规格的类目还能拿到品类兜底，商家自存的常用还在。
         *
         * 契约形状一个字没变（SpecTemplate[]），b-app 因此不用改。
         */
        List<SpecTemplateVO> fromLibrary = picked == null ? List.of()
                : specLibrary.templatesForCategory(merchantNo, picked);
        /*
         * **类目选定之后，没配规格就是没有规格 —— 不再回落品类兜底。**
         *
         * 那条回落有两个后果：运营端看到的「缺口」在商家这边被兜底盖住了，于是没人急着补；
         * 而兜底给出的是「包装：袋装/瓶装/罐装」这种推给谁都不对题的东西，商家照样全删了手打。
         * 删掉之后三件事同时成立：缺口计数是真的、商家看到的推荐一定对题、老模板表能退役。
         *
         * 商家仍有出路：手输，或者自建规格（POST /biz/spec-dims、/biz/spec-values）。
         */
        if (picked != null) {
            List<SpecTemplateVO> merged0 = new java.util.ArrayList<>(fromLibrary);
            // 商家自存的常用仍旧给他 —— 那是他自己的东西，与运营配没配无关
            legacy.stream().filter(t -> PrdSpecTemplate.MERCHANT.equals(t.scope())).forEach(merged0::add);
            return merged0;
        }
        if (fromLibrary.isEmpty()) {
            /*
             * **还没选类目：平台模板照给。**
             *
             * 我上一版在这里只留了商家自存的，理由是「选完类目才知道该推什么」——
             * 那句话对的是上面 picked != null 那条（选了类目就不该再回落品类兜底），
             * 搬到这里就错了：运营端 /ops/spec-templates 仍旧往老表写，
             * 而商家侧只认新库的话，**运营建的模板商家永远查不到** ——
             * 「模板是死的」那条断裂原样回来了，且运营那边看不出任何异常。
             */
            return legacy;
        }
        // 新库给了这一类目的维度，老表里同名的那几条（兜底或旧类目级）就别再推一遍
        Set<String> libNames = fromLibrary.stream().map(SpecTemplateVO::name)
                .collect(java.util.stream.Collectors.toSet());
        List<SpecTemplateVO> merged = new java.util.ArrayList<>(fromLibrary);
        for (SpecTemplateVO t : legacy) {
            boolean sameName = libNames.contains(t.name());
            boolean mine = PrdSpecTemplate.MERCHANT.equals(t.scope());
            // 商家自存的即便同名也留着 —— 那是他自己的东西，平台没有资格顶掉
            if (mine || !sameName) {
                merged.add(t);
            }
        }
        return merged;
    }

    /**
     * 类目级顶掉同名的品类兜底，并把类目级排在前面。
     *
     * <p>不去重的话，选「休闲零食」会同时看到类目级的「重量」与兜底的「规格」，
     * 而商家其实只该看到前者 —— 两个都推给他，他得先判断该用哪个，
     * 而这正是模板本该替他省掉的那一步。
     *
     * <p>只按 <b>name</b> 去重而不按 templateNo：顶掉的判据是「说的是同一个维度」，
     * 编号本来就不同。商家自存的模板（scope=MERCHANT）不参与顶替，
     * 那是他自己的东西，平台没有资格覆盖。
     */
    private static List<SpecTemplateVO> preferCategoryLevel(List<SpecTemplateVO> all, String picked) {
        if (picked == null) {
            return all;
        }
        Set<String> catLevelNames = all.stream()
                .filter(t -> picked.equals(t.categoryNo()))
                .map(SpecTemplateVO::name)
                .collect(java.util.stream.Collectors.toSet());
        List<SpecTemplateVO> out = new java.util.ArrayList<>(
                all.stream().filter(t -> picked.equals(t.categoryNo())).toList());
        for (SpecTemplateVO t : all) {
            boolean isCatLevel = picked.equals(t.categoryNo());
            boolean shadowed = PrdSpecTemplate.PLATFORM.equals(t.scope()) && catLevelNames.contains(t.name());
            if (!isCatLevel && !shadowed) {
                out.add(t);
            }
        }
        return out;
    }

    @Override
    @Transactional
    public SpecTemplateVO saveSpecTemplate(String merchantNo, String name, List<SpecOption> options) {
        if (name == null || name.isBlank() || options == null || options.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdSpecTemplate t = new PrdSpecTemplate();
        t.setTemplateNo(BizKey.next(BizKey.SPEC_TEMPLATE));
        // 一律存成 MERCHANT：商家改不了平台模板 —— 平台模板是跨店可比的基础
        t.setScope(PrdSpecTemplate.MERCHANT);
        t.setStatus(PrdSpecTemplate.ACTIVE);
        t.setEntityNo(merchantNo);
        t.setName(name);
        t.setOptions(writeJson(options));
        DataScopeContext.executeWithoutScope(() -> templateMapper.insert(t));
        return toVO(t);
    }

    // ---------------------------------------------------------------- helpers

    private PrdGoods newGoods(String merchantNo) {
        PrdGoods g = new PrdGoods();
        g.setGoodsNo(BizKey.next(BizKey.GOODS));
        g.setEntityNo(merchantNo);
        g.setRating(50);
        g.setRatingCount(0);
        g.setSales(0);
        g.setLimitPerUser(0);
        /*
         * 新建默认**实物类全支持**，由商家去收窄。
         *
         * 此前默认是 ["STORE_PICKUP"] 且商家改不了 —— 那个值从来不表示
         * 「只支持到店自提」，只是个占位，而这些商品一直在被下成快递单。
         * F-1 给下单加了「必须支持」的校验之后，照原样留着会让新商品一建出来
         * 就只能自提。默认放宽、由商家收窄，是唯一不会凭空拦单的方向。
         *
         * ⚠️ **是 PHYSICAL 不是 ALL**：服务类履约（到店核销 / 上门预约）
         * 要由商家显式选择 —— 一件大米不该一建出来就声称支持到店核销。
         */
        g.setFulfillments(writeJson(new ArrayList<>(Fulfillments.PHYSICAL)));
        return g;
    }

    /**
     * 取自己的商品。<b>不是自己的按 404 处理而不是 403</b> ——
     * 403 等于告诉对方「这个编号确实存在，只是不归你」，那是一条可以拿来枚举别家商品的信道。
     */
    private PrdGoods mine(String merchantNo, String goodsNo) {
        PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, goodsNo)
                        .eq(PrdGoods::getEntityNo, merchantNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }

    private void applyStatus(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PrdGoods> w,
                             String status) {
        if (status == null || status.isBlank()) {
            return;
        }
        switch (status) {
            case "ON_SALE" -> w.eq(PrdGoods::getOnSale, true);
            case "OFF_SALE" -> w.eq(PrdGoods::getOnSale, false).eq(PrdGoods::getAuditStatus, APPROVED);
            // PENDING 是对外口径；AUDITING 是库里的词，老客户端还在传 —— 两个都收
            case "PENDING", "AUDITING" -> w.eq(PrdGoods::getAuditStatus, AUDITING);
            case "REJECTED" -> w.eq(PrdGoods::getAuditStatus, REJECTED);
            case DRAFT -> w.eq(PrdGoods::getAuditStatus, DRAFT);
            /*
             * 缺货：**条件已经在 list() 里按 goodsNo 圈过了**，这里只负责别把它
             * 落进 default。缺货与在售不互斥（一件在售商品照样能全规格断货），
             * 所以这里不再叠加 on_sale 条件。
             */
            case OUT_OF_STOCK -> { }
            // 未知取值当作不过滤：前端多传一个筛选项不该让列表变空，那看着像"一件商品都没有"
            default -> { }
        }
    }

    /**
     * 商家侧状态：审核结果优先于上下架 —— 没过审时"已下架"这个说法会让人以为点一下就能卖。
     *
     * <p>不再是 static：在售与否现在要看**当前门店**，那需要读库。
     */
    private String statusOf(PrdGoods g) {
        /*
         * 草稿排在最前：它既不是「已下架」（点一下就能卖）也不是「待审」（在等别人）——
         * 说错了商家的下一步就错了。
         */
        if (DRAFT.equals(g.getAuditStatus())) {
            return DRAFT;
        }
        if (AUDITING.equals(g.getAuditStatus())) {
            /*
             * **下发 PENDING，不是库里那个 AUDITING**（2026-08-12 收敛）。
             *
             * 词典 §11 的通用状态词表规定「已提交待处理」= PENDING，ops-web 的
             * SkuStatus 一直照着写，只有这里发的是列名的原值 —— 于是同一件事
             * 在三端有两个词，而枚举登记表里那句「已归一」当时只归在端上。
             *
             * 库里的 audit_status 不动：它是审核结果那一轴（AUDITING/APPROVED/REJECTED），
             * 改列值要迁移，而收益只是名字好看 —— 对外口径统一就够了。
             */
            return "PENDING";
        }
        if (REJECTED.equals(g.getAuditStatus())) {
            return "REJECTED";
        }
        return storeOnSale(g) ? "ON_SALE" : "OFF_SALE";
    }

    /**
     * 这件商品在**当前门店**上不上架。
     *
     * <p>语义与门店库存（V13）逐字同款：
     * <ul>
     *   <li>一条店级行都没有 → 走 {@code prd_goods.on_sale}，单店行为完全不变
     *   <li>有了任意一条 → 该商品整体转为店级管理，<b>没有行的店视为未上架</b>
     * </ul>
     *
     * <p>最后半句不能改成「没有行就回退主体级」：那样商家给 A 店单独上架之后，
     * B 店会跟着一起上架 —— 而他刚做的恰恰是「只在 A 店卖」。
     */
    private boolean storeOnSale(PrdGoods g) {
        boolean entityOn = Boolean.TRUE.equals(g.getOnSale());
        String storeNo = ai.neargo.shop.auth.BizContext.current().currentStoreNo();
        List<ai.neargo.shop.product.entity.PrdStoreGoods> rows = storeGoodsRows(g.getGoodsNo());
        if (rows.isEmpty()) {
            return entityOn;
        }
        /*
         * storeNo 在 B 端不会为空 —— 不传 X-Store-No 时 BizContext 取默认店，
         * 「主体视角」这个东西在 B 端并不存在。
         * 这里原先有一个 storeNo == null 的分支（「任一门店在售就算在售」），
         * 是我凭空造的语义，测试当场证伪：不带头访问看到的就是默认店的答案。
         * 留成死代码的话，下一个人会照着它推理出一个不存在的视角。
         */
        if (storeNo == null || storeNo.isBlank()) {
            return entityOn;
        }
        return entityOn && rows.stream()
                .filter(r -> storeNo.equals(r.getStoreNo()))
                .anyMatch(r -> Boolean.TRUE.equals(r.getOnSale()));
    }

    private List<ai.neargo.shop.product.entity.PrdStoreGoods> storeGoodsRows(String goodsNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectList(Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, goodsNo)));
    }

    private GoodsVO toVO(PrdGoods g) {
        // 复用买家侧的组装：同一件商品在两个端展示出两套价格/库存口径是最难查的一类 bug
        // 单件详情不带门店上下文：列表才是「这家店卖什么」那一屏
        return merchantView(g, goodsService.detail(g.getGoodsNo()), null);
    }

    /**
     * 买家侧的组装结果 → 商家视角。<b>与 {@link #toVO} 分开，是为了让列表能批量取 base</b>：
     * 逐行 {@code detail()} 是这个域最贵的一处 N+1（见 {@link #toVOs}）。
     */
    private GoodsVO merchantView(PrdGoods g, GoodsVO base, Boolean storeOnSale) {
        return new GoodsVO(base.goodsNo(), base.title(), base.subtitle(), base.cover(),
                base.images(), base.detail(), base.detailImages(),
                base.type(), base.categoryNo(), base.merchant(),
                base.rating(), base.ratingCount(), base.price(), base.originPrice(),
                base.fulfillments(), base.specGroups(),
                withMarketPrices(g.getGoodsNo(), storeSkus(base.skus())), base.sales(),
                base.cutoffAt(), base.arrivalDesc(), base.weighed(), base.origin(),
                base.durationMin(), base.storeName(), base.limitPerUser(), base.onSale(),
                statusOf(g),
                /*
                 * **译文原文只在这一侧下发**。编辑页按语言逐格填、保存是整份覆盖 ——
                 * 拿不到原文它就只能回填当前那一格，于是用中文改一次，
                 * 英文与阿语的标题被清空，而且不报错（C 端回落中文，看起来一切正常）。
                 */
                readMap(g.getTitleI18n()), readMap(g.getSubtitleI18n()),
                g.getStdNo(),
                g.getAuditReason(),
                // 「可开团的商品」那一栏就是按它筛的
                GoodsServiceImpl.groupBuyConf(g),
                // 商家侧回显：不回显的话「打开编辑页再保存一次就把参数清空了」
                readParams(g.getParams()),
                hasDraft(g.getGoodsNo()),
                storeOnSale);
    }

    /**
     * 把 SKU 的库存换成**当前门店**的。
     *
     * <p>商家给某家店设了 1 件之后，商品页却显示主体总量 91 —— 他会以为还有货。
     * 库存分店（V13）只解决了「扣谁的数」，没解决「商家看到的是谁的数」，
     * 而后者才是他每天盯着的那个数字。
     *
     * <p>没启用分店库存的 SKU 原样返回，单店商家因此看不出任何变化。
     */
    /**
     * 给商家侧的 SKU 补上**各市场价**。
     *
     * <p>{@code GoodsServiceImpl} 只查基准市场那一行（买家只看自己那个市场），
     * 而编辑页按市场逐格填、保存是整份覆盖 —— 拿不到整张表就只能回填当前那一格，
     * 于是<b>改一次标题就把其余市场的价删了</b>。这条与 {@code titleI18n} 同一个形状。
     */
    private List<GoodsVO.SkuVO> withMarketPrices(String goodsNo, List<GoodsVO.SkuVO> skus) {
        if (skus == null || skus.isEmpty()) {
            return skus;
        }
        List<PrdSku> rows = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo)));
        Map<String, Map<String, Long>> bySku = rows.stream()
                .filter(r -> r.getPrice() != null)
                .collect(java.util.stream.Collectors.groupingBy(PrdSku::getSkuNo,
                        java.util.stream.Collectors.toMap(PrdSku::getMarket, PrdSku::getPrice, (a, b) -> a)));
        /*
         * 成本价**只在这条路径补**（商家侧）。买家侧的 toSkuVO 恒发 null ——
         * 进货价是商家的经营秘密，从买家端的响应里能读到就等于公开了。
         *
         * 按 skuNo 取基准市场那一行：成本是同一件货的进价，不随售卖市场变。
         */
        Map<String, Long> costBySku = rows.stream()
                .filter(r -> r.getCostPrice() != null)
                .collect(java.util.stream.Collectors.toMap(PrdSku::getSkuNo, PrdSku::getCostPrice,
                        (a, b) -> a));
        /*
         * 条码与货号同理：**只在商家侧补**（V252）。买家侧恒发 null ——
         * 它们是商家与供应商/ERP 之间的键，而条码还能反查到进货渠道。
         *
         * 与成本价同一条：按 skuNo 取基准市场那一行，它们不随售卖市场变。
         */
        Map<String, String> barcodeBySku = rows.stream()
                .filter(r -> r.getBarcode() != null)
                .collect(java.util.stream.Collectors.toMap(PrdSku::getSkuNo, PrdSku::getBarcode,
                        (a, b) -> a));
        Map<String, String> codeBySku = rows.stream()
                .filter(r -> r.getMerchantSkuCode() != null)
                .collect(java.util.stream.Collectors.toMap(PrdSku::getSkuNo,
                        PrdSku::getMerchantSkuCode, (a, b) -> a));
        return skus.stream()
                .map(s -> new GoodsVO.SkuVO(s.skuNo(), s.optionValues(), s.spec(), s.price(),
                        s.originPrice(), s.stock(), s.nominalGram(),
                        bySku.getOrDefault(s.skuNo(), Map.of()), s.storePrice(),
                        costBySku.get(s.skuNo()),
                        // 条码与货号在这里补上；计量单位买家侧已经带了，原样透传
                        barcodeBySku.get(s.skuNo()), codeBySku.get(s.skuNo()), s.saleUnit()))
                .toList();
    }

    private List<GoodsVO.SkuVO> storeSkus(List<GoodsVO.SkuVO> skus) {
        String storeNo = ai.neargo.shop.auth.BizContext.current().currentStoreNo();
        if (storeNo == null || storeNo.isBlank() || skus == null || skus.isEmpty()) {
            return skus;
        }
        List<String> skuNos = skus.stream().map(GoodsVO.SkuVO::skuNo).toList();
        /*
         * 本店价先贴上 —— **与库存分开算**：库存那段可能整体 early return
         * （这些 SKU 都没启用分店库存），而门店定价与分店库存是两件独立的事，
         * 顺手搭在库存那条分支上的话，只定价不分库存的商家永远看不到自己定的价。
         */
        Map<String, Long> prices = storePriceOf(storeNo, skuNos);
        if (!prices.isEmpty()) {
            skus = skus.stream().map(s -> new GoodsVO.SkuVO(s.skuNo(), s.optionValues(), s.spec(),
                    s.price(), s.originPrice(), s.stock(), s.nominalGram(), s.priceByMarket(),
                    prices.get(s.skuNo()), s.costPrice(),
                    s.barcode(), s.merchantSkuCode(), s.saleUnit())).toList();
        }
        Map<String, PrdStoreStock> byStore = DataScopeContext.executeWithoutScope(() ->
                        storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                                .in(PrdStoreStock::getSkuNo, skuNos)))
                .stream().collect(java.util.stream.Collectors.toMap(
                        r -> r.getSkuNo() + "@" + r.getStoreNo(), r -> r, (a, b) -> a));
        // 这些 SKU 里有没有任何一个启用了分店库存 —— 判据与 StockPortImpl 保持一致
        java.util.Set<String> perStore = byStore.values().stream()
                .map(PrdStoreStock::getSkuNo).collect(java.util.stream.Collectors.toSet());
        if (perStore.isEmpty()) {
            return skus;
        }
        return skus.stream().map(sku -> {
            if (!perStore.contains(sku.skuNo())) {
                return sku;
            }
            PrdStoreStock row = byStore.get(sku.skuNo() + "@" + storeNo);
            /*
             * 这家店没有行 = 0，不是回退总量 —— 与下单侧同一口径。
             * 两处口径不一致的话，会出现「页面显示有货、下单说库存不足」。
             */
            int stock = row == null || row.getStock() == null ? 0 : row.getStock();
            int locked = row == null || row.getLockedStock() == null ? 0 : row.getLockedStock();
            int available = Math.max(stock - locked, 0);
            return new GoodsVO.SkuVO(sku.skuNo(), sku.optionValues(), sku.spec(),
                    sku.price(), sku.originPrice(), available, sku.nominalGram(),
                    sku.priceByMarket(), sku.storePrice(), sku.costPrice(),
                    sku.barcode(), sku.merchantSkuCode(), sku.saleUnit());
        }).toList();
    }

    private SpecTemplateVO toVO(PrdSpecTemplate t) {
        List<SpecTemplateVO.Option> options;
        try {
            options = json.readValue(t.getOptions() == null ? "[]" : t.getOptions(),
                    new TypeReference<List<SpecTemplateVO.Option>>() {
                    });
        } catch (Exception e) {
            options = List.of();
        }
        // 老 prd_spec_template 里的模板（商家自存 + 品类兜底）没有主维度这个概念 ——
        // 主维度是类目绑定上的判据，这条路根本不经过绑定表
        return new SpecTemplateVO(t.getTemplateNo(), t.getScope(), t.getCategoryType(),
                t.getCategoryNo(), t.getName(), options, t.getEntityNo(), false);
    }

    private String writeSpecGroups(List<SpecGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return "[]";
        }
        /*
         * **optionCodes 与 templateNo 必须一起存下去。**
         *
         * 此前这里只写 name/options，两者在保存那一刻被静默丢弃 —— 于是
         * B-4.5 说的「一期只写入不消费」连写入都没有，而平台规格模板（P-3.4/E27）
         * 存在的唯一理由就是那个 code：没有它，三家店的「500g」「五百克」「0.5kg」
         * 永远聚合不到一起，模板与手输没有任何区别。
         *
         * 丢弃发生在写库这一步，所以从接口到页面全程看不出来 —— 建品成功、
         * 规格显示正常，只是那一列 code 从来没存在过。
         */
        return writeJson(groups.stream()
                .map(g -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("name", g.name());
                    m.put("options", g.options() == null ? List.of() : g.options());
                    if (g.optionCodes() != null && !g.optionCodes().isEmpty()) {
                        m.put("optionCodes", g.optionCodes());
                    }
                    if (g.templateNo() != null && !g.templateNo().isBlank()) {
                        m.put("templateNo", g.templateNo());
                    }
                    return m;
                })
                .toList());
    }

    private String writeMap(Map<String, String> m) {
        return m == null || m.isEmpty() ? null : writeJson(m);
    }

    private String writeJson(Object v) {
        try {
            return json.writeValueAsString(v == null ? List.of() : v);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 已登记的市场码。
     *
     * <p><b>每次保存查一次，不缓存</b> —— 运营端可以随时增删市场，缓存住的话
     * 新开一个市场之后商家那边会一直报「不认识这个市场」，而没有任何地方
     * 提示要重启。一次保存只查这一次（结果在循环外复用），代价是一条主键扫描。
     */
    private Set<String> marketsOf() {
        return marketPort.all().stream()
                .map(ai.neargo.shop.spi.pay.MarketPort.MarketBrief::market)
                .collect(java.util.stream.Collectors.toSet());
    }

}
