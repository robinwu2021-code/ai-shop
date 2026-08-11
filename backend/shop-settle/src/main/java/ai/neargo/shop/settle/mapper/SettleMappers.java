package ai.neargo.shop.settle.mapper;

import ai.neargo.shop.settle.entity.PtsUserAccount;
import ai.neargo.shop.settle.entity.PtsUserLedger;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlPayment;
import ai.neargo.shop.settle.entity.StlPointsPool;
import ai.neargo.shop.settle.entity.StlSplitLog;
import ai.neargo.shop.settle.entity.StlPurchaseInvoice;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** settle 域的 Mapper 集合。 */
public final class SettleMappers {

    private SettleMappers() {
    }

    public interface BillMapper extends BaseMapper<StlBill> {
    }

    /** 采购进项票（自营）。发票代码+号码联合唯一，挡住同一张票冲两个周期的账。 */
    public interface PurchaseInvoiceMapper extends BaseMapper<StlPurchaseInvoice> {
    }

    public interface SplitLogMapper extends BaseMapper<StlSplitLog> {
    }

    public interface PaymentMapper extends BaseMapper<StlPayment> {
    }

    public interface PointsPoolMapper extends BaseMapper<StlPointsPool> {
    }

    public interface PointsAccountMapper extends BaseMapper<PtsUserAccount> {
    }

    public interface PointsLedgerMapper extends BaseMapper<PtsUserLedger> {
    }
}
