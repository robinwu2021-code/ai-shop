package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdGoodsRevision;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.spi.product.RetailProductSourcePort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Read original published history only. Legacy goods without a saved publication are deliberately
 * unsupported.
 */
@Component
public class RetailProductSourcePortImpl implements RetailProductSourcePort {
    private final ProductMappers.GoodsMapper goods;
    private final ProductMappers.GoodsRevisionMapper revisions;
    private final Clock clock;

    @Autowired
    public RetailProductSourcePortImpl(
            ProductMappers.GoodsMapper goods, ProductMappers.GoodsRevisionMapper revisions) {
        this(goods, revisions, Clock.systemUTC());
    }

    public RetailProductSourcePortImpl(
            ProductMappers.GoodsMapper goods,
            ProductMappers.GoodsRevisionMapper revisions,
            Clock clock) {
        this.goods = goods;
        this.revisions = revisions;
        this.clock = clock;
    }

    @Override
    @Transactional(
            transactionManager = "transactionManager",
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public Facts read(Query q) {
        if (q == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        key(q.entityNo());
        key(q.storeNo());
        key(q.goodsNo());
        if (q.revisionNo() != null && q.revisionNo() <= 0
                || q.historical() && q.revisionNo() == null)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var head =
                            goods.selectOne(
                                    Wrappers.<PrdGoods>lambdaQuery()
                                            .eq(PrdGoods::getEntityNo, q.entityNo())
                                            .eq(PrdGoods::getStoreNo, q.storeNo())
                                            .eq(PrdGoods::getGoodsNo, q.goodsNo()));
                    if (head == null
                            || !q.entityNo().equals(head.getEntityNo())
                            || !q.storeNo().equals(head.getStoreNo()))
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    if (!q.historical()
                            && (!Boolean.TRUE.equals(head.getOnSale())
                                    || !"APPROVED".equals(head.getAuditStatus())))
                        throw BizException.of(ErrorCode.CONFLICT);
                    var lookup =
                            Wrappers.<PrdGoodsRevision>lambdaQuery()
                                    .eq(PrdGoodsRevision::getEntityNo, q.entityNo())
                                    .eq(PrdGoodsRevision::getGoodsNo, q.goodsNo());
                    if (q.historical())
                        lookup.eq(PrdGoodsRevision::getRevisionNo, q.revisionNo())
                                .in(
                                        PrdGoodsRevision::getStatus,
                                        PrdGoodsRevision.ONLINE,
                                        PrdGoodsRevision.SUPERSEDED);
                    else lookup.eq(PrdGoodsRevision::getStatus, PrdGoodsRevision.ONLINE);
                    var rows = revisions.selectList(lookup.last("LIMIT 2"));
                    if (rows.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
                    var row = rows.getFirst();
                    if (row.getPublishedAt() == null
                            || row.getRevisionNo() == null
                            || row.getPayload() == null
                            || row.getPayload().isBlank()
                            || q.revisionNo() != null
                                    && !q.revisionNo().equals(row.getRevisionNo()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    MerchantGoodsService.SaveCommand saved;
                    try {
                        saved =
                                new ObjectMapper()
                                        .readValue(
                                                row.getPayload(),
                                                MerchantGoodsService.SaveCommand.class);
                    } catch (Exception malformed) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    // Original editor snapshots can be partial: missing SKU identities never prove
                    // historical membership.
                    var skuNos =
                            saved.skus() == null
                                    ? List.<String>of()
                                    : saved.skus().stream()
                                            .map(MerchantGoodsService.Sku::skuNo)
                                            .filter(Objects::nonNull)
                                            .toList();
                    if (new HashSet<>(skuNos).size() != skuNos.size())
                        throw BizException.of(ErrorCode.CONFLICT);
                    skuNos.forEach(RetailProductSourcePortImpl::key);
                    var now = clock.instant();
                    var hash =
                            PricingSnapshotHash.of(
                                    List.of(
                                            q.entityNo(),
                                            q.storeNo(),
                                            q.goodsNo(),
                                            row.getRevisionNo(),
                                            row.getPayload()));
                    return new Facts(
                            q,
                            row.getRevisionNo(),
                            hash,
                            skuNos,
                            now,
                            now.plusSeconds(30),
                            PricingSnapshotHash.of(List.of(q, hash, now)));
                });
    }

    private static void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}"))
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
