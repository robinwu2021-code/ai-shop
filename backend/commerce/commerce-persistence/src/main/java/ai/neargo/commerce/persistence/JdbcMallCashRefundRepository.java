package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.mall.MallException;
import ai.neargo.commerce.core.offline.CashRefundRepository;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** An offline payout can stand alone or complete an existing native late-payment refund. */
public final class JdbcMallCashRefundRepository implements CashRefundRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SELECT_PAYOUT = """
            SELECT command_snapshot->'$.offlinePayout' FROM mall_refund WHERE tenant_no=?
              AND JSON_CONTAINS_PATH(command_snapshot,'one','$.offlinePayout')
            """;
    private final DataSource ds;
    private final JdbcTemplate jdbc;

    public JdbcMallCashRefundRepository(DataSource ds) {
        this.ds = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public Optional<Refund> find(String tenant, String no, boolean lock) {
        JdbcWriteGuard.require(ds, tenant);
        return jdbc.query(SELECT_PAYOUT + " AND refund_no=?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> JSON.readValue(r.getString(1), Refund.class), tenant, no).stream().findFirst();
    }

    public List<Refund> list(String tenant, String order) {
        JdbcWriteGuard.require(ds, tenant);
        return jdbc.query(SELECT_PAYOUT + " AND order_no=? ORDER BY refund_no",
                (r, n) -> JSON.readValue(r.getString(1), Refund.class), tenant, order);
    }

    public List<String> pending(String tenant, String after, int limit) {
        JdbcWriteGuard.require(ds, tenant);
        return jdbc.queryForList("""
                SELECT refund_no FROM mall_refund WHERE tenant_no=? AND refund_no>?
                  AND command_snapshot->>'$.offlinePayout.status' IN ('PREPARED','PAYOUT_ACCEPTED')
                ORDER BY refund_no LIMIT ?
                """, String.class, tenant, after == null ? "" : after, limit);
    }

    public void save(Refund refund) {
        String tenant = refund.target().store().tenantNo();
        JdbcWriteGuard.require(ds, tenant);
        String no = refund.command().refundNo();
        String order = refund.target().orderNo(), store = refund.target().store().storeNo();
        String payment = refund.command().paymentNo(), payload = JSON.writeValueAsString(refund);
        try {
            var rows = jdbc.queryForList("""
                    SELECT order_no,store_no,JSON_CONTAINS_PATH(policy_snapshot,'one','$.refundNo') AS native_refund,
                           command_snapshot->'$.payment' AS original_command
                    FROM mall_refund WHERE tenant_no=? AND refund_no=? FOR UPDATE
                    """, tenant, no);
            if (rows.isEmpty()) {
                if (no.startsWith("late-")) throw new MallException("REFUND_ORIGINAL_UNPROVEN");
                jdbc.update("""
                        INSERT INTO mall_refund
                          (tenant_no,created_at,updated_at,created_by,updated_by,version,deleted,refund_no,order_no,
                           store_no,refund_channel,payment_no,reason,policy_snapshot,requested_cash_minor,
                           approved_cash_minor,restore_credit_minor,status,command_snapshot)
                        VALUES (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?,1,0,?,?,?,'OFFLINE',?,?,JSON_OBJECT(),
                                ?,?,0,?,JSON_OBJECT('offlinePayout',CAST(? AS JSON)))
                        """, tenant, refund.reviewer(), refund.reviewer(), no, order, store, payment,
                        refund.command().reason(), refund.payment().amount().minor(), refund.payment().amount().minor(),
                        refund.status(), payload);
                return;
            }
            var row = rows.getFirst();
            boolean nativeRefund = ((Number) row.get("native_refund")).intValue() == 1;
            if (!order.equals(row.get("order_no")) || !store.equals(row.get("store_no"))
                    || nativeRefund && (!no.startsWith("late-") || row.get("original_command") == null
                    || !refund.payment().equals(JSON.readValue(row.get("original_command").toString(),
                            ai.neargo.commerce.api.port.PaymentRefundCommand.class))))
                throw new MallException("REFUND_ORIGINAL_UNPROVEN");
            jdbc.update("""
                    UPDATE mall_refund SET command_snapshot=JSON_SET(command_snapshot,'$.offlinePayout',CAST(? AS JSON)),
                        refund_channel='OFFLINE',payment_no=?,status=IF(?,status,?),version=version+1,
                        updated_at=UTC_TIMESTAMP(3),updated_by=? WHERE tenant_no=? AND refund_no=?
                    """, payload, payment, nativeRefund, refund.status(), refund.reviewer(), tenant, no);
        } catch (DuplicateKeyException conflict) {
            throw new MallException("REFUND_ALREADY_PENDING");
        }
    }
}
