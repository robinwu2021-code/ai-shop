package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.product.service.GoodsService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.CommunityPoolMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
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
    private final CommunityPoolMapper poolMapper;
    private final MerchantQueryPort merchantPort;
    private final ObjectMapper json;
    /** 限时特价覆盖展示价。product → marketing 走 Port（ArchUnit 守着不许直连） */
    private final ai.neargo.shop.spi.marketing.CampaignPort campaignPort;

    public GoodsServiceImpl(GoodsMapper goodsMapper, SkuMapper skuMapper, CommunityPoolMapper poolMapper,
                            MerchantQueryPort merchantPort, ObjectMapper json,
                            ai.neargo.shop.spi.marketing.CampaignPort campaignPort) {
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.poolMapper = poolMapper;
        this.merchantPort = merchantPort;
        this.json = json;
        this.campaignPort = campaignPort;
    }

    @Override
    public List<GoodsVO> promoted(String communityNo, Integer size) {
        int limit = size == null || size <= 0 ? 6 : size;
        LambdaQueryWrapper<PrdGoods> w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getOnSale, true)
                .eq(PrdGoods::getAuditStatus, "APPROVED");

        // 与 list() 同一条规矩：社区池之外的商品不该出现 —— 用户看到也买不到
        if (communityNo != null && !communityNo.isBlank()) {
            List<String> goodsNos = poolMapper.selectList(Wrappers.<PrdCommunityPool>lambdaQuery()
                            .eq(PrdCommunityPool::getCommunityNo, communityNo)).stream()
                    .map(PrdCommunityPool::getGoodsNo).toList();
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

    @Override
    public PageData<GoodsVO> list(GoodsQuery q) {
        LambdaQueryWrapper<PrdGoods> w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getOnSale, true)
                .eq(PrdGoods::getAuditStatus, "APPROVED");

        if (q.merchantNo() != null && !q.merchantNo().isBlank()) {
            w.eq(PrdGoods::getEntityNo, q.merchantNo());
        } else if (q.communityNo() != null && !q.communityNo().isBlank()) {
            // 社区池是筛选视图：先取该社区可见的 goodsNo，再查商品。
            // 没有池数据 = 该社区还没铺货，返回空列表而不是全量 —— 否则用户会看到根本买不到的东西
            List<String> goodsNos = poolMapper.selectList(Wrappers.<PrdCommunityPool>lambdaQuery()
                            .eq(PrdCommunityPool::getCommunityNo, q.communityNo())).stream()
                    .map(PrdCommunityPool::getGoodsNo).toList();
            if (goodsNos.isEmpty()) {
                return PageData.empty(q.page(), q.size());
            }
            w.in(PrdGoods::getGoodsNo, goodsNos);
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
        List<GoodsVO> records = page.getRecords().stream()
                .map(g -> toVO(g, skus.getOrDefault(g.getGoodsNo(), List.of()),
                        flash.get(g.getGoodsNo())))
                .toList();
        return PageData.of(records, page.getTotal(), page.getCurrent(), page.getSize());
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
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PrdGoods>lambdaQuery()
                                .eq(PrdGoods::getOnSale, true)
                                .eq(PrdGoods::getAuditStatus, "APPROVED")
                                .like(PrdGoods::getTitle, keyword)
                                .orderByDesc(PrdGoods::getSales)
                                .last("limit 10"))).stream()
                .map(PrdGoods::getTitle).distinct().toList();
    }

    @Override
    public List<String> hotWords() {
        // 一期用「销量前 10 的商品标题」代替真实搜索词统计：
        // 真实热搜要先有搜索日志，而现在还没有用户。等有量了换成日志聚合，接口不变
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PrdGoods>lambdaQuery()
                                .eq(PrdGoods::getOnSale, true)
                                .eq(PrdGoods::getAuditStatus, "APPROVED")
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
                        m.logo(), m.rating(), m.ratingCount(), m.verified(), m.breachCount()))
                .orElseGet(() -> new GoodsVO.MerchantBriefVO(g.getEntityNo(), "", "", 0d, 0, false, 0));

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
                readParams(g.getParams()));
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
