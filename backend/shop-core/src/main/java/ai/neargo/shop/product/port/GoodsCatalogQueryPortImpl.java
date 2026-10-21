package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Pure product metadata: explicit owner predicates also protect service-token and consumer callers. */
@Component
public class GoodsCatalogQueryPortImpl implements GoodsCatalogQueryPort {
    private final GoodsMapper goods;
    private final SkuMapper skus;

    public GoodsCatalogQueryPortImpl(GoodsMapper goods, SkuMapper skus) {
        this.goods = goods;
        this.skus = skus;
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public GoodsFact getGoods(String entityNo, String storeNo, String goodsNo) {
        key(entityNo); key(storeNo); key(goodsNo);
        return DataScopeContext.executeWithoutScope(() -> {
            var row = goods.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                    .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo,
                            PrdGoods::getTitle, PrdGoods::getCover, PrdGoods::getOnSale,
                            PrdGoods::getAuditStatus, PrdGoods::getVersion)
                    .eq(PrdGoods::getEntityNo, entityNo).eq(PrdGoods::getStoreNo, storeNo)
                    .eq(PrdGoods::getGoodsNo, goodsNo));
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            var variants = skus.selectList(Wrappers.<PrdSku>lambdaQuery()
                    .select(PrdSku::getSkuNo, PrdSku::getSpec, PrdSku::getVersion)
                    .eq(PrdSku::getEntityNo, entityNo).eq(PrdSku::getGoodsNo, goodsNo)
                    .eq(PrdSku::getMarket, "CN").orderByAsc(PrdSku::getSkuNo));
            return new GoodsFact(row.getGoodsNo(), row.getEntityNo(), row.getStoreNo(), row.getTitle(),
                    row.getCover(), Boolean.TRUE.equals(row.getOnSale()), row.getAuditStatus(), row.getVersion(),
                    variants.stream().map(s -> new SkuFact(s.getSkuNo(), s.getSpec(), s.getVersion())).toList());
        });
    }

    private static void key(String value) {
        if (value == null || value.isBlank() || value.length() > 32) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
