package ai.neargo.shop.trade.entitlement.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.service.EntitlementSourceDecisionService;
import ai.neargo.shop.trade.entitlement.CardSaleSnapshotPlan;
import ai.neargo.shop.trade.entitlement.CardSaleSnapshotService;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot;
import ai.neargo.shop.trade.mapper.TradeMappers.CardSaleSnapshotMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class CardSaleSnapshotServiceImpl implements CardSaleSnapshotService {
    private static final String TENANT = "MAIN";
    private final CardSaleSnapshotMapper snapshots;
    private final OrderMapper orders;
    private final EntitlementSourceDecisionService decisions;

    public CardSaleSnapshotServiceImpl(CardSaleSnapshotMapper snapshots, OrderMapper orders,
                                      EntitlementSourceDecisionService decisions) {
        this.snapshots = snapshots;
        this.orders = orders;
        this.decisions = decisions;
    }

    @Override
    @Transactional
    public List<CardSaleSnapshotPlan.Snapshot> capture(CardSaleSnapshotPlan.OrderFacts order,
                                                        List<CardSaleSnapshotPlan.SaleLine> lines) {
        var plan = CardSaleSnapshotPlan.expand(order, lines);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (var item : plan) {
            try {
                var row = toEntity(item, now);
                snapshots.insertIfAbsent(row);
                var existing = snapshots.findBySource(TENANT, item.source().grantKey());
                if (existing == null || !item.snapshotHash().equals(existing.getSnapshotHash())) {
                    throw new IllegalStateException("Card sale source already exists with different immutable facts");
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Could not persist card sale entitlement snapshot", e);
            }
        }
        return plan;
    }

    @Override
    @Transactional
    public int recordPaidFromOrder(String orderNo) {
        OrdOrder order = orders.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, orderNo).last("limit 1"));
        if (order == null || !OrdOrder.PAID.equals(order.getStatus()) || order.getPaidAt() == null
                || order.getPaidAt() <= 0) return 0;
        String paidFactNo = order.getPayTradeNo() == null || order.getPayTradeNo().isBlank()
                ? order.getOrderNo() : order.getPayTradeNo();
        LocalDateTime paidAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(order.getPaidAt()), ZoneOffset.UTC);
        return snapshots.markPaidOnce(TENANT, orderNo, paidFactNo, paidAt,
                LocalDateTime.now(ZoneOffset.UTC), "SYSTEM");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int recordCancelledFromOrder(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            var order = orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo, TENANT)
                    .eq(OrdOrder::getOrderNo, orderNo).last("FOR UPDATE"));
            if (order == null || !List.of(OrdOrder.CANCELLED, OrdOrder.CLOSED).contains(order.getStatus())) return 0;
            if (order.getPaidAt() != null && order.getPaidAt() > 0) throw BizException.of(ErrorCode.CONFLICT);
            var rows = snapshots.selectList(Wrappers.<OrdCardSaleSnapshot>lambdaQuery()
                    .eq(OrdCardSaleSnapshot::getTenantNo, TENANT).eq(OrdCardSaleSnapshot::getOrderNo, orderNo)
                    .orderByAsc(OrdCardSaleSnapshot::getOrderLineNo, OrdCardSaleSnapshot::getUnitIndex,
                            OrdCardSaleSnapshot::getEntitlementIndex));
            for (var row : rows) {
                var source = new SourceRef("CORE", "PAID_CARD_ORDER", row.getOrderNo(), row.getOrderLineNo(),
                        row.getUnitIndex(), row.getEntitlementIndex(), 0);
                if (!source.grantKey().equals(row.getSourceKey()) || row.getPaidAt() != null
                        || row.getPaidFactNo() != null || !order.getUserNo().equals(row.getUserNo())
                        || !order.getCurrency().equals(row.getCurrency())) throw BizException.of(ErrorCode.CONFLICT);
                decisions.record(TENANT, source, "GRANT", DecisionRef.DecisionType.GRANT_ABORT, null, null,
                        row.getUserNo(), row.getEntityNo(), row.getStoreNo(), row.getCurrency(), row.getUnitPriceMinor(),
                        row.getSnapshotHash(), Instant.now());
            }
            return rows.size();
        });
    }

    private OrdCardSaleSnapshot toEntity(CardSaleSnapshotPlan.Snapshot snapshot, LocalDateTime now) throws Exception {
        var row = new OrdCardSaleSnapshot();
        row.setTenantNo(TENANT);
        row.setSnapshotNo(BizKey.next(BizKey.CARD_SALE_SNAPSHOT));
        row.setSourceKey(snapshot.source().grantKey());
        row.setOrderNo(snapshot.order().orderNo());
        row.setSubOrderNo(snapshot.line().subOrderNo());
        row.setOrderLineNo(snapshot.line().orderLineNo());
        row.setUnitIndex(snapshot.unitIndex());
        row.setEntitlementIndex(snapshot.entitlementIndex());
        row.setUserNo(snapshot.order().userNo());
        row.setEntityNo(snapshot.line().entityNo());
        row.setStoreNo(snapshot.line().storeNo());
        row.setGoodsNo(snapshot.line().goodsNo());
        row.setSkuNo(snapshot.line().skuNo());
        row.setTemplateVersionNo(snapshot.rule().templateVersionNo());
        row.setUnitPriceMinor(snapshot.line().unitPriceMinor());
        row.setCurrency(snapshot.order().currency());
        row.setRuleSnapshot(snapshot.rule().ruleSnapshot());
        row.setSnapshotHash(snapshot.snapshotHash());
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy("SYSTEM");
        row.setUpdatedBy("SYSTEM");
        return row;
    }
}
