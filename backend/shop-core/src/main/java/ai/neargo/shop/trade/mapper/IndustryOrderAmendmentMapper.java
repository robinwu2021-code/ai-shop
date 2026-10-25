package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.Applied;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.Debt;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.FeeApplication;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.FeeLine;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.Journal;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.Line;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.ReductionColumn;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.ReturnDraft;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

import javax.sql.DataSource;

/** All original unpaid-amendment SQL and JDBC conversion stay behind typed mapper results. */
@Repository
public class IndustryOrderAmendmentMapper {
    private final JdbcTemplate db;

    public IndustryOrderAmendmentMapper(@Qualifier("dataSource") DataSource ds) {
        db = new JdbcTemplate(ds);
    }

    private static final RowMapper<Journal> JOURNAL =
            (r, n) ->
                    new Journal(
                            r.getLong("id"),
                            r.getString("tenant_no"),
                            r.getString("entity_no"),
                            r.getString("store_no"),
                            r.getString("order_no"),
                            r.getString("sub_order_no"),
                            r.getString("amendment_no"),
                            r.getString("operation_key"),
                            r.getString("request_hash"),
                            r.getString("kind"),
                            r.getString("source_return_no"),
                            r.getString("source_hash"),
                            r.getString("command_snapshot"),
                            r.getString("evidence_snapshot"),
                            r.getString("inventory_snapshot"),
                            r.getString("status"),
                            r.getString("actor_no"),
                            r.getLong("expected_sub_version"),
                            r.getString("currency"),
                            r.getLong("receivable_delta_minor"),
                            r.getString("replacement_cart_no"),
                            r.getObject("net_delta_minor", Long.class),
                            r.getObject("tax_delta_minor", Long.class));
    private static final RowMapper<Line> LINE =
            (r, n) ->
                    new Line(
                            r.getLong("order_item_id"),
                            r.getString("source_component_ref"),
                            r.getBigDecimal("quantity"),
                            r.getBigDecimal("physical_reduction_qty"),
                            r.getBigDecimal("cancel_qty"),
                            r.getString("unit"),
                            r.getString("disposition"),
                            r.getLong("receivable_reduction_minor"),
                            r.getLong("net_reduction_minor"),
                            r.getLong("tax_reduction_minor"));

    public Journal byKey(Reference ref, String operation) {
        var rows =
                db.query(
                        "SELECT * FROM ord_industry_amendment WHERE tenant_no=? AND entity_no=? AND"
                                + " store_no=? AND operation_key=?",
                        JOURNAL,
                        ref.scope().tenantNo(),
                        ref.scope().entityNo(),
                        ref.scope().storeNo(),
                        operation);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Journal> pending(int limit) {
        return db.query(
                "SELECT * FROM ord_industry_amendment WHERE status IN('PREPARED','UNKNOWN') AND"
                        + " kind='RETURN' ORDER BY updated_at LIMIT ?",
                JOURNAL,
                limit);
    }

    public List<Line> lines(String no) {
        return db.query(
                "SELECT * FROM ord_industry_amendment_line WHERE amendment_no=? ORDER BY"
                        + " order_item_id",
                LINE,
                no);
    }

    public BigDecimal fulfilled(Reference ref, Long item, String component) {
        var values =
                db.query(
                        "SELECT fulfilled_qty FROM ord_industry_fulfillment_progress WHERE"
                            + " tenant_no=? AND entity_no=? AND sub_order_no=? AND order_item_id=?"
                            + " AND source_component_ref=?",
                        (r, n) -> r.getBigDecimal(1),
                        ref.scope().tenantNo(),
                        ref.scope().entityNo(),
                        ref.subOrderNo(),
                        item,
                        component);
        return values.isEmpty() ? BigDecimal.ZERO : values.getFirst();
    }

    public BigDecimal reduced(Reference ref, Long item, ReductionColumn type) {
        String column =
                switch (type) {
                    case QUANTITY -> "quantity";
                    case PHYSICAL -> "physical_reduction_qty";
                    case CANCEL -> "cancel_qty";
                };
        return db.queryForObject(
                "SELECT COALESCE(SUM(l."
                        + column
                        + "),0) FROM ord_industry_amendment_line l JOIN ord_industry_amendment a ON"
                        + " a.amendment_no=l.amendment_no WHERE a.tenant_no=? AND a.entity_no=? AND"
                        + " a.sub_order_no=? AND a.status='CONFIRMED' AND l.order_item_id=?",
                BigDecimal.class,
                ref.scope().tenantNo(),
                ref.scope().entityNo(),
                ref.subOrderNo(),
                item);
    }

    public long moneyReduced(Reference ref, Long item) {
        return db.queryForObject(
                "SELECT COALESCE(SUM(l.receivable_reduction_minor),0) FROM"
                        + " ord_industry_amendment_line l JOIN ord_industry_amendment a ON"
                        + " a.amendment_no=l.amendment_no WHERE a.tenant_no=? AND a.entity_no=? AND"
                        + " a.sub_order_no=? AND a.status='CONFIRMED' AND l.order_item_id=?",
                Long.class,
                ref.scope().tenantNo(),
                ref.scope().entityNo(),
                ref.subOrderNo(),
                item);
    }

    public long netReduced(Reference ref, Long item) {
        return db.queryForObject(
                "SELECT COALESCE(SUM(l.net_reduction_minor),0) FROM ord_industry_amendment_line l"
                        + " JOIN ord_industry_amendment a ON a.amendment_no=l.amendment_no WHERE"
                        + " a.tenant_no=? AND a.entity_no=? AND a.sub_order_no=? AND"
                        + " a.status='CONFIRMED' AND l.order_item_id=?",
                Long.class,
                ref.scope().tenantNo(),
                ref.scope().entityNo(),
                ref.subOrderNo(),
                item);
    }

    public void returnMoney(String no, long net, long tax) {
        db.update(
                "UPDATE ord_industry_amendment SET net_delta_minor=?,tax_delta_minor=? WHERE"
                        + " amendment_no=?",
                net,
                tax,
                no);
    }

    public long receivableAdjustment(String tenantNo, String entityNo, String orderNo) {
        return db.queryForObject(
                "SELECT COALESCE(SUM(amount_minor),0) FROM ord_industry_receivable_adjustment WHERE"
                        + " tenant_no=? AND entity_no=? AND order_no=?",
                Long.class,
                tenantNo,
                entityNo,
                orderNo);
    }

    public long pendingAmendments(String tenant, String sub) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM ord_industry_amendment WHERE tenant_no=? AND sub_order_no=?"
                        + " AND status IN('PREPARED','UNKNOWN')",
                Long.class,
                tenant,
                sub);
    }

    public long pendingFulfillments(String tenant, String sub) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM ord_industry_fulfillment WHERE tenant_no=? AND sub_order_no=?"
                        + " AND status IN('PREPARED','UNKNOWN')",
                Long.class,
                tenant,
                sub);
    }

    public void insert(ReturnDraft d) {
        db.update(
                "INSERT INTO"
                    + " ord_industry_amendment(tenant_no,entity_no,store_no,order_no,sub_order_no,amendment_no,operation_key,request_hash,kind,source_return_no,source_hash,command_snapshot,evidence_snapshot,status,actor_no,expected_sub_version,currency,receivable_delta_minor,replacement_cart_no,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                d.tenantNo(),
                d.entityNo(),
                d.storeNo(),
                d.orderNo(),
                d.subOrderNo(),
                d.amendmentNo(),
                d.operationKey(),
                d.requestHash(),
                d.kind(),
                d.sourceReturnNo(),
                d.sourceHash(),
                d.commandSnapshot(),
                d.evidenceSnapshot(),
                d.status(),
                d.actorNo(),
                d.expectedSubVersion(),
                d.currency(),
                d.receivableDeltaMinor(),
                d.replacementCartNo(),
                d.actorNo());
    }

    public void insertLine(String no, Line d, String actor) {
        db.update(
                "INSERT INTO"
                    + " ord_industry_amendment_line(amendment_no,order_item_id,source_component_ref,quantity,physical_reduction_qty,cancel_qty,unit,disposition,receivable_reduction_minor,net_reduction_minor,tax_reduction_minor,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                no,
                d.orderItemId(),
                d.sourceComponentRef(),
                d.quantity(),
                d.physicalReductionQty(),
                d.cancelQty(),
                d.unit(),
                d.disposition(),
                d.receivableReductionMinor(),
                d.netReductionMinor(),
                d.taxReductionMinor(),
                actor);
    }

    public void confirm(Long id, String inventory, String actor) {
        db.update(
                "UPDATE ord_industry_amendment SET"
                        + " status='CONFIRMED',inventory_snapshot=?,updated_by=? WHERE id=?",
                inventory,
                actor,
                id);
    }

    public void unknown(Long id) {
        db.update(
                "UPDATE ord_industry_amendment SET status='UNKNOWN' WHERE id=? AND"
                        + " status<>'CONFIRMED'",
                id);
    }

    public void feeMoney(String no, long net, long tax, String evidence) {
        db.update(
                "UPDATE ord_industry_amendment SET"
                        + " net_delta_minor=?,tax_delta_minor=?,evidence_snapshot=? WHERE"
                        + " amendment_no=?",
                net,
                tax,
                evidence,
                no);
    }

    public void appendDebt(Debt d) {
        db.update(
                "INSERT INTO"
                    + " ord_industry_receivable_adjustment(tenant_no,entity_no,store_no,order_no,sub_order_no,amendment_no,currency,amount_minor,source_hash,reason,created_by,net_delta_minor,tax_delta_minor)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                d.tenantNo(),
                d.entityNo(),
                d.storeNo(),
                d.orderNo(),
                d.subOrderNo(),
                d.amendmentNo(),
                d.currency(),
                d.amountMinor(),
                d.sourceHash(),
                d.reason(),
                d.actorNo(),
                d.netDeltaMinor(),
                d.taxDeltaMinor());
    }

    public FeeApplication feeApplication(Reference ref, String no) {
        var rows =
                db.query(
                        "SELECT quote_no,rule_no,revision_no,scope_no,fee_minor FROM"
                            + " ord_pricing_fee_application WHERE entity_no=? AND store_no=? AND"
                            + " application_no=? AND order_no=? AND sub_order_no=?",
                        (r, n) ->
                                new FeeApplication(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getString(4),
                                        r.getLong(5)),
                        ref.scope().entityNo(),
                        ref.scope().storeNo(),
                        no,
                        ref.orderNo(),
                        ref.subOrderNo());
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    public String quoteResult(Reference ref, String quote) {
        var rows =
                db.query(
                        "SELECT result_snapshot FROM ord_price_quote WHERE tenant_no=? AND"
                                + " entity_no=? AND store_no=? AND quote_no=?",
                        (r, n) -> r.getString(1),
                        ref.scope().tenantNo(),
                        ref.scope().entityNo(),
                        ref.scope().storeNo(),
                        quote);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    public void insertFeeLine(String no, FeeLine line, String actor) {
        db.update(
                "INSERT INTO"
                    + " ord_industry_fee_line_adjustment(amendment_no,order_item_id,source_component_ref,amount_minor,created_by)"
                    + " VALUES(?,?,?,?,?)",
                no,
                line.orderItemId(),
                line.sourceComponentRef(),
                line.amountMinor(),
                actor);
    }

    public long feeLineAdjustment(Reference ref, Long item) {
        return db.queryForObject(
                "SELECT COALESCE(SUM(l.amount_minor),0) FROM ord_industry_fee_line_adjustment l"
                        + " JOIN ord_industry_amendment a ON a.amendment_no=l.amendment_no WHERE"
                        + " a.tenant_no=? AND a.entity_no=? AND a.sub_order_no=? AND"
                        + " a.status='CONFIRMED' AND l.order_item_id=?",
                Long.class,
                ref.scope().tenantNo(),
                ref.scope().entityNo(),
                ref.subOrderNo(),
                item);
    }

    public List<Applied> applied(Reference ref) {
        return db.query(
                "SELECT amendment_no,source_hash,kind,inventory_snapshot,receivable_delta_minor"
                        + " FROM ord_industry_amendment WHERE tenant_no=? AND entity_no=? AND"
                        + " sub_order_no=? AND status='CONFIRMED' ORDER BY id",
                (r, n) ->
                        new Applied(
                                r.getString(1),
                                r.getString(2),
                                r.getString(3),
                                r.getString(4),
                                r.getLong(5)),
                ref.scope().tenantNo(),
                ref.scope().entityNo(),
                ref.subOrderNo());
    }
}
