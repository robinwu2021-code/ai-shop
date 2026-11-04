package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository;
import ai.neargo.commerce.core.mall.MallSnapshot.*;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import javax.sql.DataSource;

/** Payment attempts and execution tasks remain in their original owning tables. */
public final class JdbcFulfillmentRepository implements FulfillmentRepository {
    private final DataSource ds;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcFulfillmentRepository(DataSource ds) {
        this.ds = ds;
        jdbc = new JdbcTemplate(ds);
    }

    private void guard(String t) {
        JdbcWriteGuard.require(ds, t);
    }

    private String encode(Object o) {
        return json.writeValueAsString(o);
    }

    private <T> T decode(String s, Class<T> t) {
        return json.readValue(s, t);
    }

    public Optional<Location> locate(String t, String no) {
        guard(t);
        return jdbc
                .query(
                        "SELECT store_no,currency FROM mall_order WHERE tenant_no=? AND order_no=?",
                        (r, n) -> new Location(new StoreRef(t, r.getString(1)), r.getString(2)),
                        t,
                        no)
                .stream()
                .findFirst();
    }

    public List<Operation> operations(String t, String no) {
        guard(t);
        var out = new ArrayList<Operation>();
        jdbc.query(
                "SELECT request_snapshot,outcome_snapshot FROM mall_payment_ref WHERE tenant_no=?"
                        + " AND order_no=? ORDER BY id FOR UPDATE",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        r -> {
                            if (r.getString(1) == null)
                                throw new MallException("LEGACY_PAYMENT_UNPROVEN");
                            var op = decode(r.getString(1), Operation.class);
                            out.add(
                                    r.getString(2) == null
                                            ? op
                                            : op.paymentResult(
                                                    decode(r.getString(2), PaymentOutcome.class)));
                        },
                t,
                no);
        jdbc.query(
                "SELECT request_snapshot,outcome_snapshot FROM mall_fulfillment_task WHERE"
                    + " tenant_no=? AND order_no=? AND task_type<>'DIGITAL' ORDER BY id FOR UPDATE",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        r -> {
                            if (r.getString(1) == null)
                                throw new MallException("LEGACY_TASK_UNPROVEN");
                            var op = decode(r.getString(1), Operation.class);
                            out.add(
                                    r.getString(2) == null
                                            ? op
                                            : op.inventoryResult(
                                                    decode(
                                                            r.getString(2),
                                                            InventoryOutcome.class)));
                        },
                t,
                no);
        return List.copyOf(out);
    }

    public void save(String t, Operation op) {
        guard(t);
        var immutable =
                new Operation(
                        op.operationNo(),
                        op.orderNo(),
                        op.componentNo(),
                        op.kind(),
                        op.payment(),
                        op.inventory(),
                        op.shipment(),
                        null,
                        null);
        String request = encode(immutable),
                hash = ai.neargo.commerce.core.spi.FactDigest.of(List.of(request));
        boolean pay = op.kind().equals("PAYMENT");
        String table = pay ? "mall_payment_ref" : "mall_fulfillment_task",
                key = pay ? "payment_attempt_no" : "task_no";
        var existing =
                jdbc.query(
                        "SELECT request_hash FROM "
                                + table
                                + " WHERE tenant_no=? AND "
                                + key
                                + "=? FOR UPDATE",
                        (r, n) -> Optional.ofNullable(r.getString(1)),
                        t,
                        op.operationNo());
        Object result = pay ? op.paymentResult() : op.inventoryResult();
        String outcome = result == null ? null : encode(result),
                status =
                        result == null
                                ? "UNKNOWN"
                                : pay
                                        ? op.paymentResult().status().name()
                                        : op.inventoryResult().status().name();
        if (!existing.isEmpty()) {
            if (!existing.getFirst().orElse("").equals(hash))
                throw new MallException("OPERATION_CONFLICT");
            jdbc.update(
                    "UPDATE "
                            + table
                            + " SET outcome_snapshot=?,status=?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                            + " WHERE tenant_no=? AND "
                            + key
                            + "=?",
                    outcome,
                    status,
                    t,
                    op.operationNo());
        } else if (pay) {
            var c = op.payment();
            jdbc.update(
                    "INSERT INTO"
                        + " mall_payment_ref(tenant_no,created_at,updated_at,version,deleted,payment_attempt_no,order_no,pay_order_no,receiver_account_no,payment_business_type,currency,amount_minor,status,request_snapshot,request_hash,outcome_snapshot,dispatch_state)"
                        + " VALUES"
                        + " (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0,?,?,NULL,?,'PLATFORM_MALL',?,?,?,?,?,?,'DISPATCHED')",
                    t,
                    op.operationNo(),
                    op.orderNo(),
                    c.platformMerchantNo(),
                    c.amount().currency(),
                    c.amount().minor(),
                    status,
                    request,
                    hash,
                    outcome);
        } else {
            Integer seq =
                    jdbc.queryForObject(
                            "SELECT COALESCE(MAX(unit_seq),0)+1 FROM mall_fulfillment_task WHERE"
                                    + " tenant_no=? AND order_component_no=? AND task_type=?",
                            Integer.class,
                            t,
                            op.componentNo(),
                            op.kind());
            jdbc.update(
                    "INSERT INTO"
                        + " mall_fulfillment_task(tenant_no,created_at,updated_at,version,deleted,task_no,order_component_no,unit_seq,task_type,status,attempt_count,next_retry_at,order_no,request_snapshot,request_hash,outcome_snapshot,dispatch_state)"
                        + " VALUES"
                        + " (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0,?,?,?,?,?,1,0,?,?,?,?,'DISPATCHED')",
                    t,
                    op.operationNo(),
                    op.componentNo(),
                    seq,
                    op.kind(),
                    status,
                    op.orderNo(),
                    request,
                    hash,
                    outcome);
        }
        if (pay && op.paymentResult() != null) {
            var r = op.paymentResult();
            jdbc.update(
                    "UPDATE mall_payment_ref SET"
                        + " pay_order_no=COALESCE(pay_order_no,?),provider_paid_at=COALESCE(provider_paid_at,?),closure_reference=COALESCE(closure_reference,?)"
                        + " WHERE tenant_no=? AND payment_attempt_no=?",
                    r.paymentNo().orElse(null),
                    r.paidAt().map(java.time.Instant::toEpochMilli).orElse(null),
                    r.closureReference().orElse(null),
                    t,
                    op.operationNo());
        }
    }

    private static void changed(int n) {
        if (n != 1) throw new MallException("RESERVATION_CONFLICT");
    }

    public void settleHolds(Order o, boolean consume) {
        var q = o.quote();
        var s = q.store();
        guard(s.tenantNo());
        if (!q.digital()) return;
        changed(
                jdbc.update(
                        "UPDATE mch_subscription_reservation SET"
                                + " status=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE"
                                + " tenant_no=? AND order_no=? AND status='RESERVED'",
                        consume ? "CONSUMED" : "RELEASED",
                        s.tenantNo(),
                        o.orderNo()));
        if (q.credit() > 0) {
            changed(
                    jdbc.update(
                            "UPDATE mch_subscription_credit_account SET"
                                + " reserved_minor=reserved_minor-?,balance_minor=balance_minor-?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                + " WHERE tenant_no=? AND store_no=? AND currency=? AND"
                                + " reserved_minor>=? AND balance_minor>=?",
                            q.credit(),
                            consume ? q.credit() : 0,
                            s.tenantNo(),
                            s.storeNo(),
                            q.currency(),
                            q.credit(),
                            q.credit()));
            for (var use : q.creditUses()) {
                changed(
                        jdbc.update(
                                "UPDATE mch_subscription_credit_lot SET"
                                    + " reserved_minor=reserved_minor-?,remaining_minor=remaining_minor-?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                    + " WHERE tenant_no=? AND store_no=? AND credit_lot_no=? AND"
                                    + " reserved_minor>=? AND remaining_minor>=?",
                                use.amount(),
                                consume ? use.amount() : 0,
                                s.tenantNo(),
                                s.storeNo(),
                                use.lotNo(),
                                use.amount(),
                                use.amount()));
                jdbc.update(
                        "INSERT INTO"
                            + " mch_subscription_credit_ledger(tenant_no,created_at,updated_at,version,deleted,ledger_no,store_no,currency,credit_lot_no,event_type,biz_ref_no,event_seq,amount_minor,balance_delta_minor,reserved_delta_minor,reason)"
                            + " VALUES"
                            + " (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0,?,?,?,?,?,?,2,?,?,?,'order"
                            + " settlement')",
                        s.tenantNo(),
                        UUID.randomUUID().toString(),
                        s.storeNo(),
                        q.currency(),
                        use.lotNo(),
                        consume ? "CONSUME" : "RELEASE",
                        o.orderNo(),
                        use.amount(),
                        consume ? -use.amount() : 0,
                        -use.amount());
            }
        }
        changed(
                jdbc.update(
                        "UPDATE mch_subscription_account SET"
                            + " pending_change_order_no=NULL,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                            + " WHERE tenant_no=? AND store_no=? AND pending_change_order_no=?",
                        s.tenantNo(),
                        s.storeNo(),
                        o.orderNo()));
    }

    public void prepareDigital(Order o) {
        String t = o.quote().store().tenantNo();
        guard(t);
        for (var c :
                new JdbcMallRepository(ds, new JdbcCommerceEventPersistence(ds))
                        .components(o.quote().store(), o.orderNo())) {
            if (c.snapshot().allocation().component().kind() == ComponentKind.HARDWARE) continue;
            for (var cycle : c.snapshot().allocation().cycles()) {
                String ref = c.orderComponentNo() + ":" + cycle.sequence();
                String request = encode(c.snapshot());
                jdbc.update(
                        "INSERT INTO"
                            + " mall_fulfillment_task(tenant_no,created_at,updated_at,version,deleted,task_no,order_component_no,unit_seq,task_type,status,attempt_count,next_retry_at,order_no,request_snapshot,request_hash,dispatch_state)"
                            + " VALUES"
                            + " (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0,?,?,?,'DIGITAL','PENDING',0,0,?,?,?,'LOCAL_PENDING')",
                        t,
                        ref,
                        c.orderComponentNo(),
                        cycle.sequence(),
                        o.orderNo(),
                        request,
                        ai.neargo.commerce.core.spi.FactDigest.of(List.of(request)));
            }
        }
    }

    public void digitalCompleted(Order o) {
        guard(o.quote().store().tenantNo());
        String t = o.quote().store().tenantNo();
        for (var c :
                new JdbcMallRepository(ds, new JdbcCommerceEventPersistence(ds))
                        .components(o.quote().store(), o.orderNo())) {
            if (c.snapshot().allocation().component().kind() == ComponentKind.HARDWARE) continue;
            for (var cycle : c.snapshot().allocation().cycles()) {
                String ref = c.orderComponentNo() + ":" + cycle.sequence();
                String source =
                        jdbc.queryForObject(
                                "SELECT period_no FROM mch_subscription_period WHERE tenant_no=?"
                                        + " AND source_type='ORDER' AND source_ref_no=?",
                                String.class,
                                t,
                                ref);
                changed(
                        jdbc.update(
                                "UPDATE mall_fulfillment_task SET"
                                    + " status='SUCCEEDED',result_no=?,dispatch_state='LOCAL_COMMITTED',attempt_count=attempt_count+1,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                    + " WHERE tenant_no=? AND task_no=? AND task_type='DIGITAL' AND"
                                    + " status='PENDING'",
                                source,
                                t,
                                ref));
            }
        }
        jdbc.update(
                "UPDATE mall_order_component c JOIN mall_order_item i ON i.tenant_no=c.tenant_no"
                    + " AND i.order_item_no=c.order_item_no SET"
                    + " c.fulfillment_status='COMPLETED',c.version=c.version+1,c.updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE i.tenant_no=? AND i.order_no=? AND c.component_type<>'HARDWARE' AND"
                    + " c.fulfillment_status<>'COMPLETED'",
                o.quote().store().tenantNo(),
                o.orderNo());
    }

    public void paid(Order o, VerifiedPaymentFact f, String attempt) {
        guard(f.tenantNo());
        jdbc.update(
                "UPDATE mall_order SET cash_paid_minor=?,paid_at=? WHERE tenant_no=? AND"
                        + " order_no=?",
                f.paid().minor(),
                f.paidAt().toEpochMilli(),
                f.tenantNo(),
                o.orderNo());
        jdbc.update(
                "UPDATE mall_payment_ref SET"
                    + " pay_order_no=?,provider_paid_at=?,verified_fact_snapshot=COALESCE(verified_fact_snapshot,?),status='SUCCEEDED',version=version+1,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE tenant_no=? AND payment_attempt_no=?",
                f.paymentNo(),
                f.paidAt().toEpochMilli(),
                encode(f),
                f.tenantNo(),
                attempt);
    }

    public void shipment(Order o, Operation op) {
        String t = o.quote().store().tenantNo();
        guard(t);
        var c = op.shipment();
        jdbc.update(
                "INSERT INTO"
                    + " mall_shipment(tenant_no,created_at,updated_at,version,deleted,shipment_no,order_no,shipment_items,address_snapshot,carrier_code,tracking_no,status,shipped_at)"
                    + " VALUES (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0,?,?,?,?,?,?,'SHIPPED',?)",
                t,
                op.operationNo(),
                o.orderNo(),
                encode(
                        List.of(
                                new ShipmentItem(
                                        c.orderComponentNo(),
                                        op.inventory().skuNo(),
                                        Math.toIntExact(c.quantity())))),
                encode(o.shippingAddress()),
                c.carrierCode(),
                c.trackingNo(),
                System.currentTimeMillis());
    }
}
