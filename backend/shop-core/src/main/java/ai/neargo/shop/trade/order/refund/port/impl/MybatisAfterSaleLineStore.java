package ai.neargo.shop.trade.order.refund.port.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.order.entity.OrdAfterSaleItem;
import ai.neargo.shop.trade.order.mapper.AfterSaleItemMapper;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.port.AfterSaleLineStore;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

/** {@link AfterSaleLineStore} 的库实现。解除数据域：归属由调用方（售后服务）判过。 */
public class MybatisAfterSaleLineStore implements AfterSaleLineStore {

    private final AfterSaleItemMapper mapper;

    public MybatisAfterSaleLineStore(AfterSaleItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void saveLines(String afterSaleNo, String subOrderNo, List<RefundLine> lines) {
        for (RefundLine l : lines) {
            OrdAfterSaleItem row = new OrdAfterSaleItem();
            row.setAfterSaleNo(afterSaleNo);
            row.setSubOrderNo(subOrderNo);
            row.setItemId(l.itemId());
            row.setQty(l.qty());
            row.setRefundMinor(l.amountMinor());
            row.setTenantNo("MAIN");
            row.setVersion(0L);
            row.setDeleted(0);
            DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
        }
    }

    @Override
    public List<RefundLine> lines(String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(Wrappers.<OrdAfterSaleItem>lambdaQuery()
                        .eq(OrdAfterSaleItem::getAfterSaleNo, afterSaleNo).orderByAsc(OrdAfterSaleItem::getId)))
                .stream()
                .map(r -> new RefundLine(r.getItemId(), r.getQty(), r.getRefundMinor() == null ? 0L : r.getRefundMinor()))
                .toList();
    }

    @Override
    public void updateAmounts(String afterSaleNo, List<RefundLine> lines) {
        for (RefundLine l : lines) {
            DataScopeContext.executeWithoutScope(() -> mapper.update(null, Wrappers.<OrdAfterSaleItem>lambdaUpdate()
                    .eq(OrdAfterSaleItem::getAfterSaleNo, afterSaleNo)
                    .eq(OrdAfterSaleItem::getItemId, l.itemId())
                    .set(OrdAfterSaleItem::getRefundMinor, l.amountMinor())));
        }
    }
}
