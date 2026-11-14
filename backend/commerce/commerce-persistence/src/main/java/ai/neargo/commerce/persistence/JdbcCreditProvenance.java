package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.mall.MallException;
import ai.neargo.commerce.core.spi.CreditSlice;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Maps original per-cycle credit intervals to immutable FIFO consumption facts; no new ledger. */
final class JdbcCreditProvenance {
    private final DataSource ds;
    private final JdbcTemplate jdbc;

    JdbcCreditProvenance(DataSource ds) {
        this.ds = ds;
        jdbc = new JdbcTemplate(ds);
    }

    List<CreditSlice> source(StoreRef store, String periodNo, long credit) {
        JdbcWriteGuard.require(ds, store.tenantNo());
        if (credit == 0) return List.of();
        var orders =
                jdbc.queryForList(
                        "SELECT i.order_no FROM mch_subscription_period p JOIN"
                            + " mall_fulfillment_task t ON t.tenant_no=p.tenant_no AND"
                            + " t.task_no=p.source_ref_no AND t.task_type='DIGITAL' JOIN"
                            + " mall_order_component c ON c.tenant_no=t.tenant_no AND"
                            + " c.order_component_no=t.order_component_no JOIN mall_order_item i ON"
                            + " i.tenant_no=c.tenant_no AND i.order_item_no=c.order_item_no WHERE"
                            + " p.tenant_no=? AND p.store_no=? AND p.period_no=? FOR SHARE",
                        String.class,
                        store.tenantNo(),
                        store.storeNo(),
                        periodNo);
        if (orders.size() != 1) throw new MallException("CREDIT_PROVENANCE_MISSING");
        record Source(String no, long credit) {}
        var sources =
                jdbc.query(
                        "SELECT p.period_no,p.credit_basis_minor FROM mch_subscription_period p"
                            + " JOIN mall_fulfillment_task t ON t.tenant_no=p.tenant_no AND"
                            + " t.task_no=p.source_ref_no AND t.task_type='DIGITAL' JOIN"
                            + " mall_order_component c ON c.tenant_no=t.tenant_no AND"
                            + " c.order_component_no=t.order_component_no JOIN mall_order_item i ON"
                            + " i.tenant_no=c.tenant_no AND i.order_item_no=c.order_item_no WHERE"
                            + " p.tenant_no=? AND p.store_no=? AND i.order_no=? ORDER BY"
                            + " c.id,t.unit_seq FOR SHARE",
                        (r, n) -> new Source(r.getString(1), r.getLong(2)),
                        store.tenantNo(),
                        store.storeNo(),
                        orders.getFirst());
        long start = 0;
        boolean found = false;
        for (var source : sources) {
            if (source.no().equals(periodNo)) {
                if (source.credit() != credit)
                    throw new MallException("CREDIT_PROVENANCE_MISMATCH");
                found = true;
                break;
            }
            start = Math.addExact(start, source.credit());
        }
        if (!found) throw new MallException("CREDIT_PROVENANCE_MISSING");
        long end = Math.addExact(start, credit);
        var consumed =
                jdbc.query(
                        "SELECT"
                            + " e.ledger_no,e.credit_lot_no,e.amount_minor,l.parent_provenance,l.source_type,l.source_ref_no"
                            + " FROM mch_subscription_credit_ledger e JOIN"
                            + " mch_subscription_credit_lot l ON l.tenant_no=e.tenant_no AND"
                            + " l.store_no=e.store_no AND l.credit_lot_no=e.credit_lot_no WHERE"
                            + " e.tenant_no=? AND e.store_no=? AND e.biz_ref_no=? AND"
                            + " e.event_type='CONSUME' ORDER BY CASE WHEN"
                            + " l.source_type='CONVERSION' AND l.source_ref_no=e.biz_ref_no THEN 1"
                            + " ELSE 0 END,e.id FOR SHARE",
                        (r, n) ->
                                new CreditSlice(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getLong(3),
                                        r.getString(4),
                                        r.getString(5),
                                        r.getString(6)),
                        store.tenantNo(),
                        store.storeNo(),
                        orders.getFirst());
        long offset = 0;
        var result = new ArrayList<CreditSlice>();
        long covered = 0;
        for (var use : consumed) {
            long next = Math.addExact(offset, use.amount());
            long amount = Math.max(0, Math.min(end, next) - Math.max(start, offset));
            if (amount > 0) {
                result.add(
                        new CreditSlice(
                                use.ledgerNo(),
                                use.lotNo(),
                                amount,
                                use.parentProvenance(),
                                use.sourceType(),
                                use.sourceRefNo()));
                covered = Math.addExact(covered, amount);
            }
            offset = next;
        }
        if (covered != credit) throw new MallException("CREDIT_PROVENANCE_MISSING");
        return List.copyOf(result);
    }
}
