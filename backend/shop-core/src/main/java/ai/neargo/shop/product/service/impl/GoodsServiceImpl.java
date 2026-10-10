package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.product.service.GoodsService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品读实现。
 *
 * <p><b>价格一律来自 {@link PrdSku}</b>（TDD-backend §6.3）：列表展示价 = 最低 SKU 价，
 * 详情给全部 SKU。商品表上没有 price 列可读，从结构上杜绝「两处价格不一致」。
 *
 * <p><b>关于 {@code executeWithoutScope}</b> —— 本类每次查 {@code prd_goods} 都显式豁免数据域，
 * 这不是图省事，是必须的：{@code prd_goods} 在 {@code DataScopeRegistration} 里按 MERCHANT 维度注册了
 * （B 端商家只能改自己的货），而 {@code DataScopeHandler} 对已注册的表是 <b>fail-closed</b> ——
 * C 端会话的维度是 SELF，在商品表的锚点里找不到对应列，于是拼出 {@code 1=0}。
 * 症状是「游客能逛、一登录就一件商品都看不见」，且不报错、日志干净。
 * 正确做法是在公共目录查询上显式豁免，<b>而不是</b>给商品表编一个假的 SELF 锚点
 * （那会让「按属主过滤」这件事在商品域变成一句谎话）。由 {@code DataScopeFlowTest} 守卫。
 */
@Service
public class GoodsServiceImpl implements GoodsService {

    /** 一期恒 CN；多市场时由请求上下文带入。 */
    private static final String MARKET_CN = "CN";

    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;
    private final MerchantQueryPort merchantPort;
    private final ObjectMapper json;
    /** 限时特价覆盖展示价。product → marketing 走 Port（ArchUnit 守着不许直连） */
    private final ai.neargo.shop.spi.marketing.CampaignPort campaignPort;
    /** 首页推荐位的运营配置。没配时 promoted() 仍走销量兜底 */
    private final ai.neargo.shop.spi.marketing.ContentSlotPort contentSlotPort;
    /** 门店级上架关系：门户只列本店在售的 */
    private final ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper storeGoodsMapper;
    /** 店级库存的唯一一份判据（覆盖层规则）。买家侧详情按它换库存 */
    private final ai.neargo.shop.product.service.StoreStockReader storeStockReader;
    /** 买家在哪儿能看到什么 —— 查询时现算（方案-商品可见性改查询时关联） */
    private final GoodsVisibility visibility;

    public GoodsServiceImpl(GoodsMapper goodsMapper, SkuMapper skuMapper,
                            MerchantQueryPort merchantPort, ObjectMapper json,
                            ai.neargo.shop.spi.marketing.CampaignPort campaignPort,
                            ai.neargo.shop.spi.marketing.ContentSlotPort contentSlotPort,
                            ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper storeGoodsMapper,
                            ai.neargo.shop.product.service.StoreStockReader storeStockReader,
                            GoodsVisibility visibility) {
        this.visibility = visibility;
        this.storeGoodsMapper = storeGoodsMapper;
        this.storeStockReader = storeStockReader;
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.merchantPort = merchantPort;
        this.json = json;
        this.campaignPort = campaignPort;
        this.contentSlotPort = contentSlotPort;
    }

    /**
     * 这次能看到的商品 —— <b>「位置不明」不等于「看全平台」。</b>
     *
     * <p>两级：精确定位给 {@code communityNo}，模糊定位只给得出区县码，
     * 那就按「区里任一开放小区有门店服务」筛。查询时现算，见 {@link GoodsVisibility}。
     * 两级都没有才是真正的「不筛」，而端上不该走到那儿 ——
     * 连模糊定位都拒的人看到的是空态要位置，不是一屏他买不到的货。
     *
     * @return {@code null} = 不筛；<b>空列表 = 筛出来一件都没有</b>，
     *         调用方必须回空而不是当成「不筛」—— 这两件事混在一起，
     *         正是「没铺货的区看到全平台商品」的由来
     */
    private List<String> visibleGoodsNos(String communityNo, String regionCode) {
        return visibleGoodsNos(communityNo, regionCode, null, null);
    }

    /** 带消费者坐标：才能命中商家画的多边形范围，也才能在「只有定位、没落到小区」时按省市区匹配（ADR-034） */
    private List<String> visibleGoodsNos(String communityNo, String regionCode, Integer latE6, Integer lngE6) {
        return visibility.goodsNos(communityNo, regionCode);
    }

    @Override
    public List<GoodsVO> promoted(String communityNo, String regionCode, Integer size) {
        return promoted(communityNo, regionCode, size, null, null);
    }

    @Override
    public List<GoodsVO> promoted(String communityNo, String regionCode, Integer size,
                                  Integer latE6, Integer lngE6) {
        int limit = size == null || size <= 0 ? 6 : size;
        /*
         * **运营配的内容位优先**。配了就按运营给的顺序展示 —— 首页上写的是「推荐」，
         * 在此之前它展示的却是销量事实，运营想推一件新货只能等它先卖起来。
         *
         * 没配仍然走下面的销量兜底：删掉兜底的话，没人配过的社区首页那一屏直接空了。
         */
        List<String> curated = contentSlotPort.homeFloorGoodsNos(communityNo);
        if (!curated.isEmpty()) {
            List<GoodsVO> vos = byGoodsNos(curated.size() > limit ? curated.subList(0, limit) : curated);
            if (!vos.isEmpty()) {
                return vos;
            }
            // 配了位子、但那些货全都下架/下架待审了 —— 与「没配」同样处理，
            // 不能让首页因为一条过期配置而空着
        }
        LambdaQueryWrapper<PrdGoods> w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getOnSale, true)
                .eq(PrdGoods::getAuditStatus, "APPROVED");
        onShelf(null).accept(w);

        // 与 list() 同一条规矩：这里没有门店服务的商品不该出现 —— 用户看到也买不到
        List<String> goodsNos = visibleGoodsNos(communityNo, regionCode, latE6, lngE6);
        if (goodsNos != null) {
            if (goodsNos.isEmpty()) {
                return List.of();
            }
            w.in(PrdGoods::getGoodsNo, goodsNos);
        }
        /*
         * 一期无运营后台，按销量兜底。**刻意与主商品流不同序** ——
         * 主流按距离/上架时间，这里按销量；同序的话推荐位和下面的列表会是同一批货，
         * 这个位子就白占了。接上运营配置时只换这一段。
         */
        w.orderByDesc(PrdGoods::getSales).last("limit " + limit);

        // ★ 公共目录必须显式豁免数据域，与 list() 同一条规矩（见类注释）
        List<PrdGoods> rows = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(w));
        if (rows.isEmpty()) {
            return List.of();
        }
        // SKU 要一次批量取：逐条查是列表页 N+1 的经典来源
        Map<String, List<PrdSku>> skus = loadSkus(rows.stream().map(PrdGoods::getGoodsNo).toList());
        var flash = campaignPort.flashPrices(rows.stream().map(PrdGoods::getGoodsNo).toList());
        return rows.stream()
                .map(g -> toVO(g, skus.getOrDefault(g.getGoodsNo(), List.of()), flash.get(g.getGoodsNo())))
                .toList();
    }

    /**
     * 按**给定顺序**取这些货的展示形状；不在售/未过审的<b>直接跳过</b>。
     *
     * <p>跳过而不是报错：运营配位子时那件货还在售，下架是商家自己的动作，
     * 谁也不会回头去改内容位。留着它的结果是首页上一个点不开的坑。
     */
    private List<GoodsVO> byGoodsNos(List<String> goodsNos) {
        var shelf = onShelf(null);
        List<PrdGoods> rows = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getOnSale, true)
                        .eq(PrdGoods::getAuditStatus, "APPROVED")
                        .in(PrdGoods::getGoodsNo, goodsNos)
                        .func(shelf)));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, PrdGoods> byNo = rows.stream()
                .collect(java.util.stream.Collectors.toMap(PrdGoods::getGoodsNo, g -> g, (a, b) -> a));
        Map<String, List<PrdSku>> skus = loadSkus(rows.stream().map(PrdGoods::getGoodsNo).toList());
        var flash = campaignPort.flashPrices(rows.stream().map(PrdGoods::getGoodsNo).toList());
        // ★ 顺序按运营配的来，不是按查出来的顺序 —— 数据库不保证 IN 的返回序
        return goodsNos.stream().map(byNo::get).filter(java.util.Objects::nonNull)
                .map(g -> toVO(g, skus.getOrDefault(g.getGoodsNo(), List.of()), flash.get(g.getGoodsNo())))
                .toList();
    }

    /**
     * 仅活动商品的判定（TDD-商品仅活动可售 §4.4）。setter 注入：缺了按「没有活动在跑」处理 ——
     * 仅活动的货一律不上货架，与下单那道闸同一取向。
     */
    private ai.neargo.shop.spi.marketing.SaleGatePort saleGatePort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSaleGatePort(ai.neargo.shop.spi.marketing.SaleGatePort saleGatePort) {
        this.saleGatePort = saleGatePort;
    }

    /**
     * 极速退的覆盖范围（§3.4）。setter 注入：缺了就少一条服务承诺 ——
     * 承诺缺席比承诺错了好，而切片测试里本来就没有 trade 域。
     */
    private ai.neargo.shop.spi.trade.AfterSaleRulePort afterSaleRulePort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAfterSaleRulePort(ai.neargo.shop.spi.trade.AfterSaleRulePort afterSaleRulePort) {
        this.afterSaleRulePort = afterSaleRulePort;
    }

    /**
     * 门店查名（AC1）。setter 注入、缺了就不挂店名 —— 那时端上退回主体名，
     * 与改造前逐字相同。切片测试里没有它时，列表的其余部分一字不差。
     */
    private ai.neargo.shop.spi.user.StoreDirectoryPort storeDirectory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStoreDirectory(ai.neargo.shop.spi.user.StoreDirectoryPort storeDirectory) {
        this.storeDirectory = storeDirectory;
    }

    /**
     * 评分概览（§3.3）。评价与商品同在 product 域，所以直接用 Service，不必走 port。
     * setter 注入：切片测试里没有它时，详情的其余部分一字不差。
     */
    private ai.neargo.shop.product.review.ReviewService reviewService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReviewService(ai.neargo.shop.product.review.ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 货架可见：<b>正常售卖</b>，或<b>仅活动且此刻有点名它的活动在跑</b>。
     *
     * <p>不是「仅活动永远不上货架」—— 集单没有 C 端列表页，仅活动的集单货若不上货架，
     * 顾客没有任何地方能看到它。
     *
     * <p><b>先算出集合再进 SQL</b>，不是取完一页再剔：剔会让一页少于请求数，
     * 而分页游标以为还有下一页的位置已经被占了。仅活动的在售货通常是个位数，这一趟很轻。
     *
     * @param merchantNo 限一家店时传，全平台（首页推荐、搜索）传 null
     */
    private java.util.function.Consumer<LambdaQueryWrapper<PrdGoods>> onShelf(String merchantNo) {
        List<String> only = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PrdGoods>lambdaQuery().select(PrdGoods::getGoodsNo)
                                .eq(PrdGoods::getOnSale, true)
                                .eq(PrdGoods::getSaleMode, PrdGoods.SALE_ACTIVITY_ONLY)
                                .eq(merchantNo != null && !merchantNo.isBlank(), PrdGoods::getEntityNo, merchantNo)))
                .stream().map(PrdGoods::getGoodsNo).toList();
        java.util.Set<String> live = only.isEmpty() || saleGatePort == null ? java.util.Set.of()
                : saleGatePort.live(only, System.currentTimeMillis()).any();
        return x -> {
            if (live.isEmpty()) {
                x.eq(PrdGoods::getSaleMode, PrdGoods.SALE_NORMAL);
            } else {
                x.and(y -> y.eq(PrdGoods::getSaleMode, PrdGoods.SALE_NORMAL)
                        .or().in(PrdGoods::getGoodsNo, live));
            }
        };
    }

    /**
     * 这个主体的商品里，<b>在这家店不卖</b>的那些。
     *
     * <p>口径逐字照 {@code PrdStoreGoods} 的类注释：某商品一条店级行都没有 → 不在这里排除
     * （由 {@code prd_goods.on_sale} 决定，单店时代的行为）；有了任意一行 → 只有本店那行
     * {@code on_sale=1} 才算在卖。取「排除集」而不是「在售集」：没有店级行的老商品不必逐件登记。
     */
    private List<String> notOnSaleAt(String entityNo, String storeNo) {
        List<ai.neargo.shop.product.entity.PrdStoreGoods> rows = DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectList(Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                        .eq(entityNo != null && !entityNo.isBlank(),
                                ai.neargo.shop.product.entity.PrdStoreGoods::getEntityNo, entityNo)));
        java.util.Set<String> managed = new java.util.HashSet<>();
        java.util.Set<String> sellingHere = new java.util.HashSet<>();
        for (var r : rows) {
            managed.add(r.getGoodsNo());
            if (storeNo.equals(r.getStoreNo()) && Boolean.TRUE.equals(r.getOnSale())) {
                sellingHere.add(r.getGoodsNo());
            }
        }
        managed.removeAll(sellingHere);
        return List.copyOf(managed);
    }

    @Override
    public PageData<GoodsVO> list(GoodsQuery q) {
        LambdaQueryWrapper<PrdGoods> w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getOnSale, true)
                .eq(PrdGoods::getAuditStatus, "APPROVED");
        onShelf(q.merchantNo()).accept(w);

        if (q.merchantNo() != null && !q.merchantNo().isBlank()) {
            w.eq(PrdGoods::getEntityNo, q.merchantNo());
        } else {
            // 先取这里看得到的 goodsNo，再查商品。
            // 一件都没有 = 那儿还没有门店服务，返回空列表而不是全量 —— 否则用户会看到根本买不到的东西
            List<String> goodsNos = visibleGoodsNos(q.communityNo(), q.regionCode(), q.latE6(), q.lngE6());
            if (goodsNos != null) {
                if (goodsNos.isEmpty()) {
                    return PageData.empty(q.page(), q.size());
                }
                w.in(PrdGoods::getGoodsNo, goodsNos);
            }
        }

        if (q.storeNo() != null && !q.storeNo().isBlank()) {
            // 商品只属于一家门店（V384）：门户只列本店的货；没有归属的（测试种子）沿用投影
            w.and(x -> x.eq(PrdGoods::getStoreNo, q.storeNo()).or().isNull(PrdGoods::getStoreNo));
            List<String> notHere = notOnSaleAt(q.merchantNo(), q.storeNo());
            if (!notHere.isEmpty()) {
                w.notIn(PrdGoods::getGoodsNo, notHere);
            }
        }
        if (q.type() != null && !q.type().isBlank()) {
            w.eq(PrdGoods::getType, q.type());
        }
        if (q.categoryNo() != null && !q.categoryNo().isBlank()) {
            w.eq(PrdGoods::getCategoryNo, q.categoryNo());
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            // 两边通配 %kw%，**任何索引都用不上**（前缀索引只服务 likeRight）——
            // 每次搜索都是全表扫。380 行免费；商品量上来后换 ES，GoodsService 接口不变。
            // 别把这条改成「有索引兜底」：它没有，写成有会让 ES 的紧迫性被低估
            w.and(x -> x.like(PrdGoods::getTitle, q.keyword()).or().like(PrdGoods::getSubtitle, q.keyword()));
        }
        w.orderByDesc(PrdGoods::getSales);

        // ★ 公共目录必须显式豁免数据域，见类注释「关于 executeWithoutScope」
        Page<PrdGoods> page = DataScopeContext.executeWithoutScope(
                () -> goodsMapper.selectPage(Page.of(q.page(), q.size()), w));
        if (page.getRecords().isEmpty()) {
            return PageData.empty(q.page(), q.size());
        }

        List<String> nos = page.getRecords().stream().map(PrdGoods::getGoodsNo).toList();
        Map<String, List<PrdSku>> skus = loadSkus(nos);
        // 批量拿一次闪购价，与 promoted()/detailAll() 同一个形状。
        // 此前在 map 里逐行调（List.of(单个)），20 行/页 = 20 次跨域调用
        var flash = campaignPort.flashPrices(nos);
        /*
         * 每行挂上**提供这件货的门店**（AC1/AC2）。按主体号查目录时不挂 ——
         * 那条路没有社区上下文，挂不出「哪家店」，端上退回主体名。
         */
        Map<String, String> byVisibility = q.merchantNo() != null && !q.merchantNo().isBlank()
                ? Map.of() : visibility.providingStores(q.communityNo(), q.regionCode(), q.latE6(), q.lngE6(), nos);
        /*
         * ★ **没有位置时的兜底：按「在架卖它的门店」反查，唯一才填**（2026-09-30）。
         *
         * 上面那条按社区算 —— 不给 communityNo 也不给 regionCode 时
         * 它必然返回空，端上只能回落主体名。线上实测：首页（传了社区）显示
         * 「虹选粮油·深圳测试店」，而搜索页显示「虹选科技有限公司」，同一件货两个说法。
         *
         * **搜索页不传社区是有意的**（那是「主动找特定商家」，不该被送达范围筛掉），
         * 所以不能靠端上补参数解决：要分开的是两件被绑在一起的事 ——
         * 「按哪个社区筛」与「这一行来自哪家店」。
         *
         * **唯一才填**：两家店都在架卖它时，没有社区就说不清买家会落到哪家，
         * 硬挑一家会让落款与真正履约的店对不上（下单落店另有自己的判据）。
         * 说不清就不说，端上回落主体名 —— 那是诚实的默认值。
         */
        final Map<String, String> storeOfGoods = new HashMap<>(
                byVisibility.isEmpty() && (q.merchantNo() == null || q.merchantNo().isBlank())
                        ? soleSellingStoreOf(nos)
                        : byVisibility);
        // 商品只属于一家门店（V384）：有归属就是它，不必再推断「唯一在架的那家」
        page.getRecords().forEach(g -> {
            if (g.getStoreNo() != null && !g.getStoreNo().isBlank()) {
                storeOfGoods.put(g.getGoodsNo(), g.getStoreNo());
            }
        });
        Map<String, String> storeNames = storeNamesOf(storeOfGoods.values());
        List<GoodsVO> records = page.getRecords().stream()
                .map(g -> withStoreScope(
                        toVO(g, skus.getOrDefault(g.getGoodsNo(), List.of()), flash.get(g.getGoodsNo())),
                        storeOfGoods.get(g.getGoodsNo()), storeNames))
                .toList();
        return PageData.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 商品的归属门店（V384）；没有归属返回 null */
    private String ownerStoreOf(String goodsNo) {
        PrdGoods g = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(
                Wrappers.<PrdGoods>lambdaQuery().select(PrdGoods::getStoreNo)
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1")));
        return g == null || g.getStoreNo() == null || g.getStoreNo().isBlank() ? null : g.getStoreNo();
    }

    /** 门店号 → 门店名。一次取回，避免每行查一次 */
    private Map<String, String> storeNamesOf(java.util.Collection<String> storeNos) {
        if (storeNos.isEmpty() || storeDirectory == null) {
            return Map.of();
        }
        return storeDirectory.cards(List.copyOf(new java.util.HashSet<>(storeNos))).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().storeName()));
    }

    /**
     * 给这一行挂上门店：店名（落款要显示它，不是主体名）+ 该店的库存（售罄判据要按店算）。
     *
     * <p>没有门店时原样返回 —— 单店商家与按主体查目录都走这一支，行为逐字不变（AC8）。
     */
    /**
     * 每件货**唯一**在架卖它的那家门店；不唯一或一条店级行都没有的不进结果。
     *
     * <p>三态语义（与 {@code MerchantGoodsServiceImpl.storeOnSale} 同一套）：
     * 一条店级行都没有 = 还没按店管理，主体下每家店都算在卖 —— 那也是「说不清哪家」，
     * 所以同样不填。只认「有行、且恰好一家在架」这一种。
     */
    private Map<String, String> soleSellingStoreOf(List<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return Map.of();
        }
        List<ai.neargo.shop.product.entity.PrdStoreGoods> rows =
                DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.selectList(
                        Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                                .in(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, goodsNos)));
        Map<String, java.util.Set<String>> selling = new HashMap<>();
        for (var r : rows) {
            if (Boolean.TRUE.equals(r.getOnSale()) && r.getStoreNo() != null && !r.getStoreNo().isBlank()) {
                selling.computeIfAbsent(r.getGoodsNo(), k -> new java.util.LinkedHashSet<>()).add(r.getStoreNo());
            }
        }
        Map<String, String> out = new HashMap<>();
        for (var e : selling.entrySet()) {
            if (e.getValue().size() == 1) {
                out.put(e.getKey(), e.getValue().iterator().next());
            }
        }
        return out;
    }

    private GoodsVO withStoreScope(GoodsVO v, String storeNo, Map<String, String> storeNames) {
        if (storeNo == null || storeNo.isBlank()) {
            return v;
        }
        GoodsVO out = v.withStore(new GoodsVO.StoreBriefVO(storeNo, storeNames.get(storeNo)));
        if (out.skus() == null || out.skus().isEmpty()) {
            return out;
        }
        Map<String, Integer> avail = storeStockReader.available(
                out.skus().stream().map(GoodsVO.SkuVO::skuNo).toList(), storeNo);
        return out.withStoreSkus(out.skus().stream()
                .map(sk -> new GoodsVO.SkuVO(sk.skuNo(), sk.optionValues(), sk.spec(), sk.price(),
                        sk.originPrice(), avail.getOrDefault(sk.skuNo(), 0), sk.nominalGram(),
                        sk.priceByMarket(), sk.storePrice(), sk.costPrice(), sk.barcode(),
                        sk.merchantSkuCode(), sk.saleUnit()))
                .toList());
    }

    @Override
    public GoodsVO detail(String goodsNo) {
        PrdGoods g = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(
                Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return toVO(g, loadSkus(List.of(goodsNo)).getOrDefault(goodsNo, List.of()),
                campaignPort.flashPrices(List.of(goodsNo)).get(goodsNo));
    }

    @Override
    public GoodsVO detailForBuyer(String goodsNo) {
        return detailForBuyer(goodsNo, null);
    }

    @Override
    public GoodsVO detailForBuyer(String goodsNo, String storeNo) {
        GoodsVO v = detail(goodsNo);
        /*
         * ★ 商品只属于一家门店（V384，ADR-031）：有归属就按它 —— 链接上带的门店号只是「从哪进来的」，
         * 不决定这件货由谁卖。库存、店名都按归属店取。没有归属（测试种子）才用调用方给的。
         */
        String owner = ownerStoreOf(goodsNo);
        if (owner != null) {
            storeNo = owner;
        }
        /*
         * **库存只在调用方真正传了 storeNo 时才换**（AC8：不带门店时给主体总量，口径不变 ——
         * 单店商家、从首页推荐进来的都走这一支）。换库存与下面「解析门店名」是两件事，别绑在一起：
         * 绑一起的话不带 storeNo 也会把库存换成解析门店的，StoreStockFlowTest.buyerSeesStoreStock 立刻红。
         * 放最前：directBuyable / 促销要读 skus，读到主体总量会按「有货」往下走。
         */
        if (storeNo != null && !storeNo.isBlank() && v.skus() != null && !v.skus().isEmpty()) {
            java.util.Map<String, Integer> avail = storeStockReader.available(
                    v.skus().stream().map(GoodsVO.SkuVO::skuNo).toList(), storeNo);
            v = v.withStoreSkus(v.skus().stream()
                    .map(s -> new GoodsVO.SkuVO(s.skuNo(), s.optionValues(), s.spec(), s.price(),
                            s.originPrice(), avail.getOrDefault(s.skuNo(), 0), s.nominalGram(),
                            s.priceByMarket(), s.storePrice(), s.costPrice(), s.barcode(),
                            s.merchantSkuCode(), s.saleUnit()))
                    .toList());
        }
        v = withSaleScope(v, v.merchant() == null ? null : v.merchant().merchantNo());
        v = v.withSaleGate(directBuyable(v), null);
        v = v.withPromotions(promotionsOf(v.goodsNo()),
                v.merchant() == null ? List.of() : campaignPort.activityTags(v.merchant().merchantNo()).stream()
                        .map(t -> new GoodsVO.ActivityTagVO(t.activityNo(), t.name(), t.amountMinor(),
                                t.thresholdMinor(), t.thresholdQty(), t.newCustomerOnly()))
                        .toList());
        v = v.withServices(servicesOf(v));
        // 评分概览随详情一起下发（§3.3）：首屏那一行「4.6 分 · 12 条」不值得多打一次请求
        if (reviewService != null) {
            v = v.withReviewSummary(reviewService.summary(v.goodsNo(), null));
        }
        /*
         * 这件货由**哪家门店**卖给这位买家（2026-10-04 用户：「每个商品都关联门店，不论从哪进来」）。
         * 口径与 list、与下单落店同序，解析不出才回落主体名（诚实默认，不编一个门店）：
         *   ① 调用方带了 storeNo（从门户进）→ 用它
         *   ② 否则商品只有一家店在售 → 那家
         *   ③ 否则取主体默认营业店
         * 此前详情从不填 store，店铺卡恒显主体名「虹选科技有限公司」。列表路径一直对，详情漏了这一步。
         *
         * **放最后填**：上面 withSaleScope 重建 GoodsVO 时会把 store 置回 null，先填就被清掉了
         *（这也是为什么 list 路径没这个坑 —— 它的 withStoreScope 是整条链的最后一步）。
         * 只填门店名，不碰库存（库存口径见上面 AC8）。
         */
        String resolvedStore = storeNo;
        if (resolvedStore == null || resolvedStore.isBlank()) {
            resolvedStore = soleSellingStoreOf(List.of(v.goodsNo())).get(v.goodsNo());
        }
        if ((resolvedStore == null || resolvedStore.isBlank()) && v.merchant() != null) {
            resolvedStore = merchantPort.defaultStoreNo(v.merchant().merchantNo()).orElse(null);
        }
        if (resolvedStore != null && !resolvedStore.isBlank()) {
            // 取名用 merchantPort.storeNames（直接读 mch_store.name），不用 storeNamesOf（走 cards、按可达过滤）。
            // **必须 executeWithoutScope**：买家登录后请求线程带了数据域，而 storeNames 故意不解域，
            // 门店名查询会被域过滤成空 —— 匿名请求看不出问题，一带 C 端 token 就 storeName=null，
            // 前端回落主体名。买家查商品详情要显示任意门店名，不该受数据域限制。
            final String rs = resolvedStore;
            String name = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(
                    () -> merchantPort.storeNames(List.of(rs)).get(rs));
            v = v.withStore(new GoodsVO.StoreBriefVO(rs, name));
        }
        return v;
    }

    /**
     * 服务承诺（§3.4）：只放**可核验**的短语，一条都不许是空话。
     *
     * <ul>
     *   <li><b>极速退款</b> —— 按这件货的价问售后规则（运营可调的金额上限与总开关）。
     *       价格高于上限还挂这四个字就是假承诺，所以逐件判，不是整站挂一句。</li>
     *   <li><b>门店自提免运</b> —— 支持到店自提才给。自提本来就没有运费，
     *       这句是把「不用付运费」说出来，而不是一项额外优待。</li>
     * </ul>
     *
     * <p>拿不到售后规则时（port 没装配，例如切片测试）只是少一条承诺，不影响整页 ——
     * 承诺缺席比承诺错了好。
     */
    private List<String> servicesOf(GoodsVO v) {
        var out = new java.util.ArrayList<String>();
        long price = v.price();
        if (afterSaleRulePort != null && price > 0 && afterSaleRulePort.instantRefundCovers(price)) {
            out.add(GoodsVO.SERVICE_INSTANT_REFUND);
        }
        if (v.fulfillments() != null && v.fulfillments().contains("STORE_PICKUP")) {
            out.add(GoodsVO.SERVICE_PICKUP_FREE);
        }
        return out;
    }

    /**
     * 买 N 送 M（契约 {@code Goods.promotions}）。与下单算赠品同一个来源（{@code giftRules}）——
     * 两处各查一次的话，商品页说「买 2 送 1」、下单却没送，顾客只会觉得被骗。
     */
    private List<GoodsVO.PromotionVO> promotionsOf(String goodsNo) {
        var rule = campaignPort.giftRules(List.of(goodsNo)).get(goodsNo);
        return rule == null || rule.buyN() <= 0 || rule.giftM() <= 0 ? List.of()
                : List.of(new GoodsVO.PromotionVO(GoodsVO.PromotionVO.BUY_N_GET_M, rule.buyN(), rule.giftM()));
    }

    @Override
    public Boolean deliverableTo(String goodsNo, String communityNo) {
        return deliverableTo(goodsNo, communityNo, null, null);
    }

    @Override
    public Boolean deliverableTo(String goodsNo, String communityNo, Integer latE6, Integer lngE6) {
        return visibility.deliverable(goodsNo, communityNo, latE6, lngE6);
    }

    /**
     * 此刻能不能走普通下单（加购 / 立即购买 / 单买）—— 买家详情页的底栏只看它。
     * 与下单那道闸（OrderServiceImpl.split）同一个判定口，缺了按「不能」处理。
     */
    private boolean directBuyable(GoodsVO v) {
        if (!PrdGoods.SALE_ACTIVITY_ONLY.equals(v.saleMode())) {
            return true;
        }
        return saleGatePort != null
                && saleGatePort.live(List.of(v.goodsNo()), System.currentTimeMillis()).direct().contains(v.goodsNo());
    }

    /** 空按正常售卖 —— 与迁移的默认值同一口径；VO 上不留 null，端上就不必再判一次 */
    private static String saleModeOf(PrdGoods g) {
        return g.getSaleMode() == null ? PrdGoods.SALE_NORMAL : g.getSaleMode();
    }

    @Override
    public Map<String, GoodsVO> detailAll(List<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return Map.of();
        }
        List<PrdGoods> rows = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                Wrappers.<PrdGoods>lambdaQuery().in(PrdGoods::getGoodsNo, goodsNos)));
        if (rows.isEmpty()) {
            return Map.of();
        }
        /*
         * 三次批量查代替 N×4 次逐行查：SKU、商家、限时特价各一次。
         * merchantPort.find / campaignPort.flashPrices 都有批量版，
         * 逐行调用只是没人把这条路径按 listForOps 的样子重写过。
         */
        List<String> nos = rows.stream().map(PrdGoods::getGoodsNo).toList();
        Map<String, List<PrdSku>> skus = loadSkus(nos);
        var flash = campaignPort.flashPrices(nos);
        return rows.stream().collect(Collectors.toMap(PrdGoods::getGoodsNo,
                g -> toVO(g, skus.getOrDefault(g.getGoodsNo(), List.of()), flash.get(g.getGoodsNo())),
                (a, b) -> a));
    }

    @Override
    public ai.neargo.shop.product.dto.SkuPriceVO skuPrice(String goodsNo, String skuNo) {
        // ★ 与本类其它公共目录查询同一条规矩：买家侧读 SKU 必须显式豁免数据域。
        // prd_sku 登记 MERCHANT 锚点之后，不豁免就是 1=0 —— 症状是「规格切换后价格取不到」
        PrdSku sku = DataScopeContext.executeWithoutScope(() -> skuMapper.selectOne(
                Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo)
                        .eq(PrdSku::getSkuNo, skuNo)
                        .eq(PrdSku::getMarket, MARKET_CN)
                        .last("limit 1")));
        if (sku == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 单 SKU 询价也要吃特价 —— 否则规格切换时价格会跳回原价
        var vo = toSkuVO(sku, campaignPort.flashPrices(List.of(goodsNo)).get(goodsNo));
        return new ai.neargo.shop.product.dto.SkuPriceVO(
                vo.skuNo(), vo.spec(), vo.price(), vo.originPrice(), vo.stock());
    }

    @Override
    public List<String> suggest(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        // S1 用 like；商品量上来后换 ES。返回标题而不是商品对象 —— 联想词是拿来填搜索框的
        var shelf = onShelf(null);
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PrdGoods>lambdaQuery()
                                .eq(PrdGoods::getOnSale, true)
                                .eq(PrdGoods::getAuditStatus, "APPROVED")
                                .func(shelf)
                                .like(PrdGoods::getTitle, keyword)
                                .orderByDesc(PrdGoods::getSales)
                                .last("limit 10"))).stream()
                .map(PrdGoods::getTitle).distinct().toList();
    }

    @Override
    public List<String> hotWords() {
        // 一期用「销量前 10 的商品标题」代替真实搜索词统计：
        // 真实热搜要先有搜索日志，而现在还没有用户。等有量了换成日志聚合，接口不变
        var shelf = onShelf(null);
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PrdGoods>lambdaQuery()
                                .eq(PrdGoods::getOnSale, true)
                                .eq(PrdGoods::getAuditStatus, "APPROVED")
                                .func(shelf)
                                .orderByDesc(PrdGoods::getSales)
                                .last("limit 10"))).stream()
                .map(PrdGoods::getTitle).toList();
    }

    private Map<String, List<PrdSku>> loadSkus(List<String> goodsNos) {
        // ★ 同上：列表页的 SKU 批量读也是公共目录查询
        return DataScopeContext.executeWithoutScope(() -> skuMapper.selectList(
                        Wrappers.<PrdSku>lambdaQuery()
                                .in(PrdSku::getGoodsNo, goodsNos)
                                .eq(PrdSku::getMarket, MARKET_CN))).stream()
                .collect(Collectors.groupingBy(PrdSku::getGoodsNo));
    }

    /**
     * @param flashPrice 限时特价；null 表示这件商品此刻没有特价活动
     */
    private GoodsVO toVO(PrdGoods g, List<PrdSku> skus, Long flashPriceRaw) {
        /*
         * 多规格商品不套用商品级特价 —— 与 GoodsQueryPortImpl 同一条规则。
         * 两处都要判：一处管展示、一处管钱，只改一处会让「页面特价、下单原价」。
         */
        Long flashPrice = skus.size() > 1 ? null : flashPriceRaw;
        // 展示价取最低 SKU 价（端上「¥x 起」）。无 SKU 的商品不该上架，这里兜底为 0 而不是抛错，
        // 否则一条脏数据会让整个列表页 500
        long listPrice = skus.stream().mapToLong(s -> s.getPrice() == null ? 0L : s.getPrice()).min().orElse(0L);
        /*
         * 特价生效时，**原价挪到划线价**上 —— 只改售价不给划线价，用户看到的是
         * 一个孤零零的低价，既感知不到优惠，也无法判断值不值。
         * 没有特价时保留 SKU 自己的划线价（商家手填的那个）。
         */
        long minPrice = flashPrice != null ? flashPrice : listPrice;
        /*
         * ⚠️ 这里**不能写成三元表达式**：一支是 long、另一支是可空的 Long，
         * 三元会把整体拆箱成 long，于是没有划线价的商品（orElse(null)）直接 NPE ——
         * 而它的症状是保存商品返回 500，跟价格看着毫无关系。
         */
        Long minOrigin;
        if (flashPrice != null) {
            minOrigin = listPrice;
        } else {
            minOrigin = skus.stream().map(PrdSku::getOriginPrice).filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
        }

        /*
         * **四个字段都要给全**：logo / rating / verified / breachCount。
         *
         * 这里原先把后三个写死成 `0d, false, 0`（logo 写死成空串），于是商品页那条商家信息条
         * <b>永远是「无头像 · 0.0 分 · 无认证标」</b>：店家评分 4.8、126 人评过、认证也过了，
         * 屏幕上一律看不到。而它恰恰是「要不要在这家店下单」的那一眼 ——
         * 一家 0.0 分的店和一家没人评过的店，在买家眼里是同一回事。
         *
         * Port 上这四个字段一直都在（{@link MerchantQueryPort.MerchantBrief}），
         * 取到了没往下传而已 —— 不报错，页面也照常渲染，只是渲染的是零值。
         */
        var brief = merchantPort.find(g.getEntityNo())
                .map(m -> new GoodsVO.MerchantBriefVO(m.merchantNo(), m.merchantName(),
                        m.logo(), m.rating(), m.ratingCount(), m.verified(), m.breachCount(),
                        m.selfOperated()))
                .orElseGet(() -> new GoodsVO.MerchantBriefVO(g.getEntityNo(), "", "", 0d, 0, false, 0, false));

        return new GoodsVO(
                g.getGoodsNo(), g.getTitle(), g.getSubtitle(), g.getCover(),
                readList(g.getImages()), g.getDetail(), readList(g.getDetailImages()),
                g.getType(), g.getCategoryNo(), brief,
                g.getRating() == null ? 0d : g.getRating() / 10d,
                nz(g.getRatingCount()), minPrice, minOrigin,
                readList(g.getFulfillments()), readSpecGroups(g.getSpecGroups()),
                skus.stream().map(s -> toSkuVO(s, flashPrice)).toList(),
                nz(g.getSales()), g.getCutoffAt(), g.getArrivalDesc(), g.getWeighed(), g.getOrigin(),
                g.getDurationMin(), g.getStoreName(), nz(g.getLimitPerUser()),
                Boolean.TRUE.equals(g.getOnSale()),
                // 买家侧不下发状态：某件商品是"审核中"还是"被驳回"是店主和平台之间的事
                null,
                // 译文原文同理：这里的 title 已经按当前语言拍平，整份译文只有编辑页才用得上
                null, null,
                // 溯源只对商家与运营有意义：买家不关心这件货是不是从标准品建的
                null,
                // 驳回原因也是店主和平台之间的事
                null,
                groupBuyConf(g),
                readParams(g.getParams()),
                null,
                // C 端不分门店视角：门店上下架在可见性那一步就算进去了，不在这条链上
                null,
                // 销售范围只有详情页要 —— 列表在这儿填就是 N+1，见 withSaleScope
                null,
                saleModeOf(g),
                // directBuyable 只有详情页要（见 withSaleGate），activityLive 是 B 端列表的
                null, null, null, null,
                readList(g.getRestrictedRegions()));
    }

    /**
     * 给一条已经装好的 VO 补上销售范围。
     *
     * <p><b>不做进 {@code toVO}</b>：那一个被列表与 {@code detailAll} 共用，
     * 一屏几十行、每行一次范围查询就是 N+1。而列表本来也不标范围
     * （买家在列表上要的是「有什么」，点进来才问「送不送到我这儿」）。
     *
     * <p>Java record 没有 with，只能整份重建 —— 字段一多就容易漏填一个，
     * 所以这里逐个透传、不做任何加工，新增字段时这一段要跟着补。
     */
    private GoodsVO withSaleScope(GoodsVO v, String entityNo) {
        var scope = merchantPort.saleScope(entityNo);
        if (scope == null || scope.isEmpty()) {
            return v;
        }
        return new GoodsVO(v.goodsNo(), v.title(), v.subtitle(), v.cover(), v.images(),
                v.detail(), v.detailImages(), v.type(), v.categoryNo(), v.merchant(),
                v.rating(), v.ratingCount(), v.price(), v.originPrice(), v.fulfillments(),
                v.specGroups(), v.skus(), v.sales(), v.cutoffAt(), v.arrivalDesc(),
                v.weighed(), v.origin(), v.durationMin(), v.storeName(), v.limitPerUser(),
                v.onSale(), v.status(), v.titleI18n(), v.subtitleI18n(), v.stdNo(),
                v.auditReason(), v.groupBuy(), v.params(), v.hasDraft(), v.storeOnSale(),
                new GoodsVO.SaleScopeVO(scope.unlimited(), scope.areaNames(), scope.areaCount(),
                        scope.excludedNames() == null ? List.of() : scope.excludedNames()),
                v.saleMode(), v.directBuyable(), v.activityLive(), null, null, v.restrictedRegions());
    }

    /**
     * 商品参数 JSON → VO。**读不动就当没有**，与 readList 同一条规矩 ——
     * 一条脏数据不该让整个商品详情 500。
     */
    private List<GoodsVO.GoodsParamVO> readParams(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return this.json.readValue(raw, new TypeReference<List<GoodsVO.GoodsParamVO>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 配齐了团价与起团人数才算「能开团」—— 缺一个都开不出来 */
    static GoodsVO.GroupBuyConfVO groupBuyConf(PrdGoods g) {
        return g.getGroupPriceMinor() == null || g.getGroupMinCount() == null
                ? null
                : new GoodsVO.GroupBuyConfVO(g.getGroupMinCount(), g.getGroupPriceMinor());
    }

    private GoodsVO.SkuVO toSkuVO(PrdSku s, Long flashPrice) {
        // 可售 = 总库存 - 已锁定：把锁定量直接从展示库存里扣掉，端上就不会出现
        // 「显示有货但下单提示库存不足」
        int available = nz(s.getStock()) - nz(s.getLockedStock());
        long own = s.getPrice() == null ? 0L : s.getPrice();
        // 同 toVO：划线价那一支不能用三元 —— long 与可空 Long 混在一起会被拆箱成 long
        Long origin;
        if (flashPrice != null) {
            origin = own;
        } else {
            origin = s.getOriginPrice();
        }
        return new GoodsVO.SkuVO(s.getSkuNo(), readList(s.getOptionValues()), s.getSpec(),
                flashPrice != null ? flashPrice : own, origin,
                Math.max(available, 0), s.getNominalGram(),
                // 买家侧不发多市场价：他只看自己那个市场的价，整张表对他没有用处。
                // 商家侧由 MerchantGoodsServiceImpl 单独查一次补上（那边才需要整份回填）
                null,
                /*
                 * 门店价同样**不发给买家**：这条路径没有门店上下文（商品挂主体，
                 * 店是下单时由自提点定的），给一个「某家店的价」只会与他实际付的钱不符。
                 * 买家侧的门店价在结算那一步生效，见 OrderServiceImpl.split()。
                 */
                null,
                // 成本价同理：进货价是商家的经营秘密，买家端恒空
                null,
                /*
                 * **条码与货号不发给买家**：它们是商家与供应商/ERP 之间的键，
                 * 对买家没有用处，而条码还能反查到进货渠道。
                 */
                null, null,
                /*
                 * **计量单位买家也要**：「5」到底是 5 件还是 5 斤，
                 * 不说清楚他没法判断贵不贵 —— 这正是它与前两列的差别。
                 */
                s.getSaleUnit());
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

    private List<GoodsVO.SpecGroupVO> readSpecGroups(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<GoodsVO.SpecGroupVO>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
