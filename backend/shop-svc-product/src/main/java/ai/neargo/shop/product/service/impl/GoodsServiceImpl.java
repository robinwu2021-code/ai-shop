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

    public GoodsServiceImpl(GoodsMapper goodsMapper, SkuMapper skuMapper, CommunityPoolMapper poolMapper,
                            MerchantQueryPort merchantPort, ObjectMapper json) {
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.poolMapper = poolMapper;
        this.merchantPort = merchantPort;
        this.json = json;
    }

    @Override
    public PageData<GoodsVO> list(GoodsQuery q) {
        LambdaQueryWrapper<PrdGoods> w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getOnSale, true)
                .eq(PrdGoods::getAuditStatus, "APPROVED");

        if (q.merchantNo() != null && !q.merchantNo().isBlank()) {
            w.eq(PrdGoods::getMerchantNo, q.merchantNo());
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
            // S1 用 like 前缀索引；商品量上来后换 ES（GoodsService 接口不变）
            w.and(x -> x.like(PrdGoods::getTitle, q.keyword()).or().like(PrdGoods::getSubtitle, q.keyword()));
        }
        w.orderByDesc(PrdGoods::getSales);

        // ★ 公共目录必须显式豁免数据域，见类注释「关于 executeWithoutScope」
        Page<PrdGoods> page = DataScopeContext.executeWithoutScope(
                () -> goodsMapper.selectPage(Page.of(q.page(), q.size()), w));
        if (page.getRecords().isEmpty()) {
            return PageData.empty(q.page(), q.size());
        }

        Map<String, List<PrdSku>> skus = loadSkus(page.getRecords().stream().map(PrdGoods::getGoodsNo).toList());
        List<GoodsVO> records = page.getRecords().stream()
                .map(g -> toVO(g, skus.getOrDefault(g.getGoodsNo(), List.of())))
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
        return toVO(g, loadSkus(List.of(goodsNo)).getOrDefault(goodsNo, List.of()));
    }

    @Override
    public ai.neargo.shop.product.dto.SkuPriceVO skuPrice(String goodsNo, String skuNo) {
        PrdSku sku = skuMapper.selectOne(Wrappers.<PrdSku>lambdaQuery()
                .eq(PrdSku::getGoodsNo, goodsNo)
                .eq(PrdSku::getSkuNo, skuNo)
                .eq(PrdSku::getMarket, MARKET_CN)
                .last("limit 1"));
        if (sku == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var vo = toSkuVO(sku);
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
        return skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .in(PrdSku::getGoodsNo, goodsNos)
                        .eq(PrdSku::getMarket, MARKET_CN)).stream()
                .collect(Collectors.groupingBy(PrdSku::getGoodsNo));
    }

    private GoodsVO toVO(PrdGoods g, List<PrdSku> skus) {
        // 展示价取最低 SKU 价（端上「¥x 起」）。无 SKU 的商品不该上架，这里兜底为 0 而不是抛错，
        // 否则一条脏数据会让整个列表页 500
        long minPrice = skus.stream().mapToLong(s -> s.getPrice() == null ? 0L : s.getPrice()).min().orElse(0L);
        Long minOrigin = skus.stream().map(PrdSku::getOriginPrice).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);

        var brief = merchantPort.find(g.getMerchantNo())
                .map(m -> new GoodsVO.MerchantBriefVO(m.merchantNo(), m.merchantName(), "", 0d, false, 0))
                .orElseGet(() -> new GoodsVO.MerchantBriefVO(g.getMerchantNo(), "", "", 0d, false, 0));

        return new GoodsVO(
                g.getGoodsNo(), g.getTitle(), g.getSubtitle(), g.getCover(),
                readList(g.getImages()), g.getType(), g.getCategoryNo(), brief,
                g.getRating() == null ? 0d : g.getRating() / 10d,
                nz(g.getRatingCount()), minPrice, minOrigin,
                readList(g.getFulfillments()), readSpecGroups(g.getSpecGroups()),
                skus.stream().map(this::toSkuVO).toList(),
                nz(g.getSales()), g.getCutoffAt(), g.getArrivalDesc(), g.getWeighed(), g.getOrigin(),
                g.getDurationMin(), g.getStoreName(), nz(g.getLimitPerUser()),
                Boolean.TRUE.equals(g.getOnSale()));
    }

    private GoodsVO.SkuVO toSkuVO(PrdSku s) {
        // 可售 = 总库存 - 已锁定：把锁定量直接从展示库存里扣掉，端上就不会出现
        // 「显示有货但下单提示库存不足」
        int available = nz(s.getStock()) - nz(s.getLockedStock());
        return new GoodsVO.SkuVO(s.getSkuNo(), readList(s.getOptionValues()), s.getSpec(),
                s.getPrice() == null ? 0L : s.getPrice(), s.getOriginPrice(),
                Math.max(available, 0), s.getNominalGram());
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
