package ai.neargo.commerce.persistence;

import static ai.neargo.commerce.core.mall.MallSnapshot.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.spi.AccountSnapshot;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

import javax.sql.DataSource;

/** Native MySQL current reads and guarded writes in the caller's identical local transaction. */
public final class JdbcMallRepository implements MallRepository {
    private final DataSource ds;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcMallRepository(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
        jdbc = new JdbcTemplate(ds);
    }

    private void guard(String tenant) {
        JdbcWriteGuard.require(ds, tenant);
    }

    private String encode(Object v) {
        return json.writeValueAsString(v);
    }

    private <T> T decode(String v, Class<T> type) {
        return json.readValue(v, type);
    }

    private static MallException stale() {
        return new MallException("VERSION_CONFLICT");
    }

    private static void changed(int n) {
        if (n != 1) throw stale();
    }

    private LinkedHashMap<String, Object> row(String tenant, String actor, long at) {
        var r = new LinkedHashMap<String, Object>();
        r.put("tenant_no", tenant);
        r.put("created_by", actor);
        r.put("updated_by", actor);
        var time = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneOffset.UTC);
        r.put("created_at", time);
        r.put("updated_at", time);
        r.put("version", 0);
        r.put("deleted", 0);
        return r;
    }

    private void insert(String table, Map<String, Object> row) {
        jdbc.update(
                "INSERT INTO "
                        + table
                        + " ("
                        + String.join(",", row.keySet())
                        + ") VALUES ("
                        + String.join(",", Collections.nCopies(row.size(), "?"))
                        + ")",
                row.values().toArray());
    }

    public CreditBalance lockBalance(StoreRef s, String currency) {
        guard(s.tenantNo());
        return jdbc
                .query(
                        "SELECT version,balance_minor-reserved_minor available FROM"
                            + " mch_subscription_credit_account WHERE tenant_no=? AND store_no=?"
                            + " AND currency=? FOR UPDATE",
                        (r, n) -> new CreditBalance(r.getLong(1), r.getLong(2)),
                        s.tenantNo(),
                        s.storeNo(),
                        currency)
                .stream()
                .findFirst()
                .orElse(new CreditBalance(-1, 0));
    }

    record Binding(String code, String end) {}

    record SourcePolicy(String policy, boolean gift, List<Binding> ends) {}

    public Sources lockSources(StoreRef s, long at) {
        guard(s.tenantNo());
        var plans = new ArrayList<BasePlan>();
        var currencies = new HashSet<String>();
        var permanent = new HashSet<String>();
        jdbc.query(
                "SELECT * FROM mch_subscription_period WHERE tenant_no=? AND store_no=? AND"
                        + " state='NORMAL' ORDER BY delivery_seq,id FOR UPDATE",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        r -> {
                            currencies.add(r.getString("currency"));
                            if ("FREE".equals(r.getString("tier_code"))
                                    && r.getObject("granted_duration_ms") == null) {
                                var policy =
                                        decode(
                                                r.getString("source_policy_snapshot"),
                                                SourcePolicy.class);
                                for (var e : policy.ends()) permanent.add(e.code());
                            }
                            Long end = r.getObject("effective_end_at", Long.class);
                            if (r.getString("plan_no") != null
                                    && !"FREE".equals(r.getString("tier_code"))
                                    && (end == null || end > at))
                                plans.add(
                                        new BasePlan(
                                                r.getString("plan_no"),
                                                PlanTier.valueOf(r.getString("tier_code"))));
                        },
                s.tenantNo(),
                s.storeNo());
        if (currencies.size() != 1) throw new MallException("STORE_CURRENCY_UNAVAILABLE");
        return new Sources(currencies.iterator().next(), plans, permanent);
    }

    public List<CreditLot> lockLots(StoreRef s, String currency, long at) {
        guard(s.tenantNo());
        return jdbc.query(
                "SELECT credit_lot_no,version,remaining_minor-reserved_minor available,expires_at"
                    + " FROM mch_subscription_credit_lot WHERE tenant_no=? AND store_no=? AND"
                    + " currency=? AND (expires_at IS NULL OR expires_at>?) ORDER BY id FOR UPDATE",
                (r, n) ->
                        new CreditLot(
                                r.getString(1),
                                r.getLong(2),
                                r.getLong(3),
                                r.getObject(4, Long.class)),
                s.tenantNo(),
                s.storeNo(),
                currency,
                at);
    }

    private record QuoteRequest(
            StoreRef store, String buyer, String currency, List<QuoteLine> lines) {}

    private String requestHash(QuoteRequest request) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            encode(request)
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void saveQuote(Quote q, String actor) {
        guard(q.store().tenantNo());
        var r = row(q.store().tenantNo(), actor, q.valuationAt());
        r.put("quote_no", q.quoteNo());
        r.put("store_no", q.store().storeNo());
        r.put("entity_no", q.entityNo());
        r.put("buyer_user_no", q.buyerNo());
        r.put("intent", q.intent());
        r.put("business_type_code", q.businessType());
        r.put("currency", q.currency());
        r.put("valuation_at", q.valuationAt());
        r.put("expires_at", q.expiresAt());
        r.put("account_version", q.entitlementVersion());
        r.put("credit_account_version", q.balanceVersion());
        r.put("pricing_policy_version", q.pricingPolicyVersion());
        r.put("stacking_policy_version", q.stackingPolicyVersion());
        r.put(
                "request_hash",
                requestHash(
                        new QuoteRequest(
                                q.store(),
                                q.buyerNo(),
                                q.currency(),
                                q.lines().stream()
                                        .map(
                                                l ->
                                                        new QuoteLine(
                                                                l.sku().skuVersionNo(),
                                                                l.quantity()))
                                        .toList())));
        r.put("line_snapshot", encode(q));
        r.put("conversion_snapshot", encode(q.creditUses()));
        r.put("schedule_preview_snapshot", "{}");
        r.put("price_minor", q.deal());
        r.put("new_conversion_minor", 0);
        r.put("credit_used_minor", q.credit());
        r.put("cash_due_minor", q.deal() - q.credit());
        r.put("status", "VALID");
        insert("mall_quote", r);
    }

    public Optional<Quote> quote(String tenant, String no) {
        guard(tenant);
        return jdbc
                .query(
                        "SELECT line_snapshot FROM mall_quote WHERE tenant_no=? AND quote_no=?",
                        (r, n) -> decode(r.getString(1), Quote.class),
                        tenant,
                        no)
                .stream()
                .findFirst();
    }

    private Optional<Order> findOrder(String tenant, String predicate, Object... args) {
        guard(tenant);
        var values = new ArrayList<Object>();
        values.add(tenant);
        values.addAll(Arrays.asList(args));
        return jdbc
                .query(
                        "SELECT o.*,q.line_snapshot quote_snapshot FROM mall_order o JOIN"
                            + " mall_quote q ON q.tenant_no=o.tenant_no AND q.quote_no=o.quote_no"
                            + " WHERE o.tenant_no=? AND "
                                + predicate
                                + " FOR UPDATE",
                        (r, n) -> {
                            String no = r.getString("order_no");
                            var addresses =
                                    jdbc.query(
                                            "SELECT address_snapshot FROM mall_shipment WHERE"
                                                    + " tenant_no=? AND order_no=? ORDER BY id FOR"
                                                    + " UPDATE",
                                            (s, i) -> decode(s.getString(1), ShippingAddress.class),
                                            tenant,
                                            no);
                            var axes =
                                    OrderState.parse(
                                            r.getString("pay_status"),
                                            r.getString("fulfillment_status"),
                                            r.getString("refund_status"));
                            return new Order(
                                    no,
                                    decode(r.getString("quote_snapshot"), Quote.class),
                                    r.getString("idempotency_key"),
                                    r.getString("request_hash"),
                                    addresses.isEmpty() ? null : addresses.getFirst(),
                                    axes,
                                    r.getObject("created_at", LocalDateTime.class)
                                            .toInstant(ZoneOffset.UTC)
                                            .toEpochMilli(),
                                    r.getLong("version"));
                        },
                        values.toArray())
                .stream()
                .findFirst();
    }

    public Optional<Order> byQuote(String tenant, String no) {
        return findOrder(tenant, "o.quote_no=?", no);
    }

    public Optional<Order> byIdempotency(String tenant, String buyer, String key) {
        return findOrder(tenant, "o.buyer_user_no=? AND o.idempotency_key=?", buyer, key);
    }

    public Optional<Order> order(String tenant, String no) {
        return findOrder(tenant, "o.order_no=?", no);
    }

    public List<OrderComponent> components(StoreRef store, String no) {
        guard(store.tenantNo());
        return jdbc.query(
                "SELECT c.* FROM mall_order o JOIN mall_order_item i ON i.tenant_no=o.tenant_no AND"
                    + " i.order_no=o.order_no JOIN mall_order_component c ON"
                    + " c.tenant_no=i.tenant_no AND c.order_item_no=i.order_item_no WHERE"
                    + " o.tenant_no=? AND o.store_no=? AND o.order_no=? ORDER BY c.id FOR UPDATE",
                (r, n) ->
                        new OrderComponent(
                                r.getString("order_component_no"),
                                r.getString("order_item_no"),
                                decode(r.getString("target_snapshot"), Component.class),
                                r.getString("fulfillment_status"),
                                r.getLong("version")),
                store.tenantNo(),
                store.storeNo(),
                no);
    }

    public void create(Order o, AccountSnapshot account, CreditBalance balance, String actor) {
        var q = o.quote();
        var s = q.store();
        guard(s.tenantNo());
        if (!account.store().equals(s)
                || !account.entityNo().equals(q.entityNo())
                || account.entitlementVersion() != q.entitlementVersion()
                || account.rowVersion() != q.accountVersion()
                || balance.version() != q.balanceVersion()) throw stale();
        try {
            if (q.digital())
                changed(
                        jdbc.update(
                                "UPDATE mch_subscription_account SET"
                                    + " pending_change_order_no=?,version=version+1,updated_by=?,updated_at=?"
                                    + " WHERE tenant_no=? AND store_no=? AND version=? AND"
                                    + " entitlement_version=? AND pending_change_order_no IS NULL",
                                o.orderNo(),
                                actor,
                                LocalDateTime.ofInstant(
                                        Instant.ofEpochMilli(o.createdAt()), ZoneOffset.UTC),
                                s.tenantNo(),
                                s.storeNo(),
                                account.rowVersion(),
                                account.entitlementVersion()));
            var r = row(s.tenantNo(), actor, o.createdAt());
            r.put("order_no", o.orderNo());
            r.put("store_no", s.storeNo());
            r.put("entity_no", q.entityNo());
            r.put("buyer_user_no", q.buyerNo());
            r.put("business_type_code", q.businessType());
            r.put("quote_no", q.quoteNo());
            r.put("intent", q.intent());
            r.put("currency", q.currency());
            long gross = q.gross();
            r.put("gross_minor", gross);
            r.put("discount_minor", gross - q.deal());
            r.put("deal_minor", q.deal());
            r.put("credit_used_minor", q.credit());
            r.put("cash_due_minor", q.deal() - q.credit());
            r.put("cash_paid_minor", 0);
            r.put("cash_refunded_minor", 0);
            r.put("pay_status", o.axes().payment().name());
            r.put("fulfillment_status", o.axes().fulfillment().name());
            r.put("refund_status", o.axes().refund().name());
            r.put("pay_deadline", q.expiresAt());
            r.put("idempotency_key", o.idempotencyKey());
            r.put("request_hash", o.requestHash());
            insert("mall_order", r);
            var hardwareItems = new ArrayList<ShipmentItem>();
            for (var line : q.lines()) {
                String itemNo = UUID.randomUUID().toString();
                var item = row(s.tenantNo(), actor, o.createdAt());
                item.put("order_item_no", itemNo);
                item.put("order_no", o.orderNo());
                item.put("sku_no", line.sku().skuNo());
                item.put("sku_version_no", line.sku().skuVersionNo());
                item.put("quantity", line.quantity());
                item.put("unit_price_minor", line.sku().price().minor());
                long list = Math.multiplyExact(line.sku().listPrice().minor(), line.quantity()),
                        deal = Math.multiplyExact(line.sku().price().minor(), line.quantity());
                item.put("gross_minor", list);
                item.put("discount_minor", list - deal);
                item.put("deal_minor", deal);
                item.put("sku_snapshot", encode(line));
                insert("mall_order_item", item);
                for (var component : line.components()) {
                    var a = component.allocation();
                    var c = a.component();
                    var part = row(s.tenantNo(), actor, o.createdAt());
                    String componentNo = UUID.randomUUID().toString();
                    part.put("order_component_no", componentNo);
                    if (c.kind() == ComponentKind.HARDWARE)
                        hardwareItems.add(
                                new ShipmentItem(
                                        componentNo, c.hardwareSkuNo(), a.cycles().size()));
                    part.put("order_item_no", itemNo);
                    part.put("component_no", c.componentNo());
                    part.put("component_type", c.kind().name());
                    part.put("target_snapshot", encode(component));
                    part.put("unit_count", a.cycles().size());
                    part.put("allocated_deal_minor", a.deal());
                    part.put("allocated_cash_minor", a.cash());
                    part.put("allocated_credit_minor", a.credit());
                    part.put(
                            "eligible_basis_minor",
                            a.cycles().stream()
                                    .mapToLong(ComponentAllocation.Cycle::eligibleBasis)
                                    .reduce(0, Math::addExact));
                    part.put("is_gift", c.gift() ? 1 : 0);
                    part.put("fulfillment_status", "PENDING");
                    insert("mall_order_component", part);
                }
            }
            if (q.hardware()) {
                var shipment = row(s.tenantNo(), actor, o.createdAt());
                shipment.put("shipment_no", UUID.randomUUID().toString());
                shipment.put("order_no", o.orderNo());
                shipment.put("shipment_items", encode(hardwareItems));
                shipment.put("address_snapshot", encode(o.shippingAddress()));
                shipment.put("status", "PENDING_INVENTORY");
                insert("mall_shipment", shipment);
            }
            if (q.digital()) {
                var hold = row(s.tenantNo(), actor, o.createdAt());
                hold.put("reservation_no", UUID.randomUUID().toString());
                hold.put("store_no", s.storeNo());
                hold.put("order_no", o.orderNo());
                hold.put("quote_no", q.quoteNo());
                hold.put("subscription_version", q.entitlementVersion());
                hold.put("credit_account_version", q.balanceVersion());
                hold.put("valuation_at", q.valuationAt());
                hold.put("pay_deadline", q.expiresAt());
                hold.put("status", "RESERVED");
                hold.put("source_periods_snapshot", encode(q.creditUses()));
                hold.put("reserved_credit_minor", q.credit());
                insert("mch_subscription_reservation", hold);
            }
            if (q.credit() > 0) {
                changed(
                        jdbc.update(
                                "UPDATE mch_subscription_credit_account SET"
                                    + " reserved_minor=reserved_minor+?,version=version+1,updated_by=?,updated_at=?"
                                    + " WHERE tenant_no=? AND store_no=? AND currency=? AND"
                                    + " version=? AND balance_minor-reserved_minor>=?",
                                q.credit(),
                                actor,
                                r.get("updated_at"),
                                s.tenantNo(),
                                s.storeNo(),
                                q.currency(),
                                balance.version(),
                                q.credit()));
                for (var use : q.creditUses()) {
                    changed(
                            jdbc.update(
                                    "UPDATE mch_subscription_credit_lot SET"
                                        + " reserved_minor=reserved_minor+?,version=version+1,updated_by=?,updated_at=?"
                                        + " WHERE tenant_no=? AND store_no=? AND currency=? AND"
                                        + " credit_lot_no=? AND version=? AND"
                                        + " remaining_minor-reserved_minor>=? AND (expires_at IS"
                                        + " NULL OR expires_at>?)",
                                    use.amount(),
                                    actor,
                                    r.get("updated_at"),
                                    s.tenantNo(),
                                    s.storeNo(),
                                    q.currency(),
                                    use.lotNo(),
                                    use.version(),
                                    use.amount(),
                                    o.createdAt()));
                    var entry = row(s.tenantNo(), actor, o.createdAt());
                    entry.put("ledger_no", UUID.randomUUID().toString());
                    entry.put("store_no", s.storeNo());
                    entry.put("currency", q.currency());
                    entry.put("credit_lot_no", use.lotNo());
                    entry.put("event_type", "RESERVE");
                    entry.put("biz_ref_no", o.orderNo());
                    entry.put("event_seq", 1);
                    entry.put("amount_minor", use.amount());
                    entry.put("balance_delta_minor", 0);
                    entry.put("reserved_delta_minor", use.amount());
                    entry.put("reason", "historical FIFO order reservation");
                    insert("mch_subscription_credit_ledger", entry);
                }
            }
        } catch (DuplicateKeyException race) {
            throw new MallException("RETRY_ORDER");
        }
    }

    public Order transition(Order before, OrderState.Axes after, String actor) {
        guard(before.quote().store().tenantNo());
        changed(
                jdbc.update(
                        "UPDATE mall_order SET"
                            + " pay_status=?,fulfillment_status=?,refund_status=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                            + " WHERE tenant_no=? AND order_no=? AND version=? AND pay_status=? AND"
                            + " fulfillment_status=? AND refund_status=?",
                        after.payment().name(),
                        after.fulfillment().name(),
                        after.refund().name(),
                        actor,
                        before.quote().store().tenantNo(),
                        before.orderNo(),
                        before.version(),
                        before.axes().payment().name(),
                        before.axes().fulfillment().name(),
                        before.axes().refund().name()));
        return before.withAxes(after);
    }
}
