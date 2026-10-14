package ai.neargo.shop.trade.order.refund.port.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.order.mapper.RefundLedgerMapper;
import ai.neargo.shop.trade.order.refund.port.RefundLedgerStore;

import java.util.Map;

/** {@link RefundLedgerStore} 的库实现。读写都解除数据域（归属由调用方判过；B 端会话维度与订单表不一致时会拼 1=0）。 */
public class MybatisRefundLedgerStore implements RefundLedgerStore {

    private final RefundLedgerMapper mapper;
    private final String preparationNo;

    public MybatisRefundLedgerStore(RefundLedgerMapper mapper) {
        this(mapper,null);
    }

    /** The prepared write lane is used only while the source service holds the parent sub-order lock. */
    public MybatisRefundLedgerStore(RefundLedgerMapper mapper,String preparationNo) {
        this.mapper=mapper; this.preparationNo=preparationNo;
    }

    @Override
    public boolean addItemRefund(long itemId, int qty, long minor) {
        return DataScopeContext.executeWithoutScope(() -> preparationNo==null?mapper.addItemRefund(itemId,qty,minor):
                mapper.addPreparedItem(preparationNo,itemId,qty,minor)) == 1;
    }

    @Override
    public boolean addSubRefund(String subOrderNo, long minor) {
        return DataScopeContext.executeWithoutScope(() -> preparationNo==null?mapper.addSubRefund(subOrderNo,minor):
                mapper.addPreparedSub(preparationNo,subOrderNo,minor)) == 1;
    }

    @Override
    public long[] subRefundedAndPay(String subOrderNo) {
        Map<String, Object> row = DataScopeContext.executeWithoutScope(() -> mapper.subRefundedAndPay(subOrderNo));
        return new long[]{num(row, "refunded"), num(row, "pay")};
    }

    @Override
    public void setProgress(String subOrderNo, String progress) {
        DataScopeContext.executeWithoutScope(() -> mapper.setProgress(subOrderNo, progress));
    }

    /** H2 与 MySQL 返回的列名大小写不同，两种都认 */
    private static long num(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        Object v = row.containsKey(key) ? row.get(key) : row.get(key.toUpperCase());
        return v == null ? 0L : ((Number) v).longValue();
    }
}
