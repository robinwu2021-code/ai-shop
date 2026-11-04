package ai.neargo.commerce.persistence.trade;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallOrderRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MallOrderRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(MallOrderRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `mall_order`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`order_no`,`store_no`,`entity_no`,`buyer_user_no`,`business_type_code`,`quote_no`,`intent`,`currency`,`gross_minor`,`discount_minor`,`deal_minor`,`credit_used_minor`,`cash_due_minor`,`cash_paid_minor`,`cash_refunded_minor`,`pay_status`,`fulfillment_status`,`refund_status`,`pay_deadline`,`paid_at`,`closed_at`,`idempotency_key`,`request_hash`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.orderNo(),
                row.storeNo(),
                row.entityNo(),
                row.buyerUserNo(),
                row.businessTypeCode(),
                row.quoteNo(),
                row.intent(),
                row.currency(),
                row.grossMinor(),
                row.discountMinor(),
                row.dealMinor(),
                row.creditUsedMinor(),
                row.cashDueMinor(),
                row.cashPaidMinor(),
                row.cashRefundedMinor(),
                row.payStatus(),
                row.fulfillmentStatus(),
                row.refundStatus(),
                row.payDeadline(),
                row.paidAt(),
                row.closedAt(),
                row.idempotencyKey(),
                row.requestHash());
    }

    public Optional<MallOrderRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mall_order` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MallOrderRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("order_no"),
                                        rs.getString("store_no"),
                                        rs.getString("entity_no"),
                                        rs.getString("buyer_user_no"),
                                        rs.getString("business_type_code"),
                                        rs.getString("quote_no"),
                                        rs.getString("intent"),
                                        rs.getString("currency"),
                                        rs.getObject("gross_minor", Long.class),
                                        rs.getObject("discount_minor", Long.class),
                                        rs.getObject("deal_minor", Long.class),
                                        rs.getObject("credit_used_minor", Long.class),
                                        rs.getObject("cash_due_minor", Long.class),
                                        rs.getObject("cash_paid_minor", Long.class),
                                        rs.getObject("cash_refunded_minor", Long.class),
                                        rs.getString("pay_status"),
                                        rs.getString("fulfillment_status"),
                                        rs.getString("refund_status"),
                                        rs.getObject("pay_deadline", Long.class),
                                        rs.getObject("paid_at", Long.class),
                                        rs.getObject("closed_at", Long.class),
                                        rs.getString("idempotency_key"),
                                        rs.getString("request_hash")),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
