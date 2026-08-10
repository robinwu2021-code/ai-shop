package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.impl.GoodsServiceImpl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** {@link GoodsQueryPort} 实现。查询同样要豁免数据域，理由见 {@link GoodsServiceImpl} 类注释。 */
@Component
public class GoodsQueryPortImpl implements GoodsQueryPort {

    private static final String MARKET_CN = "CN";

    private final SkuMapper skuMapper;
    private final GoodsMapper goodsMapper;
    private final ObjectMapper json;

    public GoodsQueryPortImpl(SkuMapper skuMapper, GoodsMapper goodsMapper, ObjectMapper json) {
        this.skuMapper = skuMapper;
        this.goodsMapper = goodsMapper;
        this.json = json;
    }

    @Override
    public java.util.Optional<SkuSnapshot> snapshotOfGoods(String goodsNo) {
        if (goodsNo == null || goodsNo.isBlank()) {
            return java.util.Optional.empty();
        }
        // 取首个 SKU：开团、分享这类「以商品为单位」的动作只需要一份代表性的价与库存
        PrdSku sku = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectOne(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo)
                        .eq(PrdSku::getMarket, MARKET_CN)
                        .orderByAsc(PrdSku::getId)
                        .last("limit 1")));
        if (sku == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(snapshot(List.of(sku.getSkuNo())).get(sku.getSkuNo()));
    }

    @Override
    public Map<String, SkuSnapshot> snapshot(List<String> skuNos) {
        if (skuNos == null || skuNos.isEmpty()) {
            return Map.of();
        }
        List<PrdSku> skus = skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                .in(PrdSku::getSkuNo, skuNos)
                .eq(PrdSku::getMarket, MARKET_CN));
        if (skus.isEmpty()) {
            return Map.of();
        }

        List<String> goodsNos = skus.stream().map(PrdSku::getGoodsNo).distinct().toList();
        Map<String, PrdGoods> goodsMap = DataScopeContext.executeWithoutScope(() ->
                        goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery().in(PrdGoods::getGoodsNo, goodsNos)))
                .stream().collect(Collectors.toMap(PrdGoods::getGoodsNo, Function.identity(), (a, b) -> a));

        Map<String, SkuSnapshot> result = new HashMap<>();
        for (PrdSku sku : skus) {
            PrdGoods g = goodsMap.get(sku.getGoodsNo());
            if (g == null) {
                continue;
            }
            int available = nz(sku.getStock()) - nz(sku.getLockedStock());
            result.put(sku.getSkuNo(), new SkuSnapshot(
                    sku.getSkuNo(), sku.getGoodsNo(), sku.getEntityNo(),
                    g.getTitle(), g.getCover(), sku.getSpec(), g.getType(),
                    sku.getPrice() == null ? 0L : sku.getPrice(),
                    Math.max(available, 0),
                    Boolean.TRUE.equals(g.getOnSale()) && "APPROVED".equals(g.getAuditStatus()),
                    readList(g.getFulfillments()),
                    g.getGroupPriceMinor(), g.getGroupMinCount()));
        }
        return result;
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

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
