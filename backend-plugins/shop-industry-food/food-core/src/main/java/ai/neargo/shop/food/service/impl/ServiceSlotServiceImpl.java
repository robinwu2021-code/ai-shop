package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.service.support.BusinessCommandJournal;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimReq;
import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimVO;
import ai.neargo.shop.food.dto.ServiceSlotContracts.CreateReq;
import ai.neargo.shop.food.dto.ServiceSlotContracts.SlotVO;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.lifecycle.ServiceSlotLifecycle;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.idem.IdempotencyService;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@Transactional(transactionManager = "transactionManager")
public class ServiceSlotServiceImpl implements ServiceSlotService, ServiceSlotLifecycle {
    private final BusinessCommandJournal journal;
    private final FoodBusinessOperationsMapper mapper;
    private final IdempotencyService idem;
    private final Clock clock;
    private final ObjectMapper json;

    public ServiceSlotServiceImpl(
            FoodBusinessOperationsMapper mapper,
            IdempotencyService idem,
            Clock clock,
            ObjectMapper json) {
        this.mapper = mapper;
        journal = new BusinessCommandJournal(mapper, json);
        this.idem = idem;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public void resizeSource(
            String e, String store, String actor, String source, BigDecimal quantity) {
        requirePrimary();
        quantity(quantity);
        var claims = mapper.capacityBySource(e, store, source);
        if (claims.size() != 1) conflict();
        var row = claims.getFirst();
        var slot = slot(e, store, row.text("slot_no"), true);
        if (!"RESERVED".equals(row.text("status"))) conflict();
        BigDecimal delta = quantity.subtract(row.decimal("quantity"));
        if (delta.signum() > 0 && !clock.instant().isBefore(slot.instant("order_cutoff_at")))
            conflict();
        if (mapper.changeReservedCapacity(e, row.text("slot_no"), delta, actor) != 1
                || mapper.resizeReservedCapacity(
                                e, store, source, quantity, row.number("version"), actor)
                        != 1) conflict();
    }

    @Override
    public void assertScheduledCart(String e, String store, String cart, Instant scheduledAt) {
        var claims = mapper.capacityBySource(e, store, cart);
        if (claims.size() != 1
                || !Set.of("RESERVED", "CONSUMED").contains(claims.getFirst().text("status")))
            conflict();
        var serviceSlot = slot(e, store, claims.getFirst().text("slot_no"), false);
        if (scheduledAt == null
                || !scheduledAt.equals(serviceSlot.instant("starts_at"))
                || !"ORDER".equals(serviceSlot.text("unit"))) conflict();
    }

    @Override
    public ClaimVO reserveScheduledCart(
            String e, String store, String actor, String cart, Instant scheduledAt, String key) {
        requirePrimary();
        if (scheduledAt == null
                || !scheduledAt.isAfter(clock.instant())
                || scheduledAt.getNano() % 1_000_000 != 0) bad();
        var matches = mapper.scheduledOrderSlots(e, store, local(scheduledAt));
        if (matches.size() != 1) conflict();
        return reserve(
                e,
                store,
                actor,
                new ClaimReq(matches.getFirst().text("slot_no"), cart, BigDecimal.ONE),
                key);
    }

    @Override
    public void releaseCart(String e, String store, String actor, String cart) {
        requirePrimary();
        var claims = mapper.capacityBySource(e, store, cart);
        if (claims.isEmpty()) return;
        if (claims.size() != 1) conflict();
        var row = claims.getFirst();
        if ("RELEASED".equals(row.text("status"))) return;
        if (!"RESERVED".equals(row.text("status")) || row.text("batch_no") != null) conflict();
        transition(
                e,
                store,
                actor,
                row.text("claim_no"),
                row.number("version"),
                "RELEASE",
                "CART_SLOT_RELEASE_" + cart);
    }

    @Override
    public void bindOrder(String e, String store, String actor, String cart, String batch) {
        requirePrimary();
        var claims = mapper.capacityBySource(e, store, cart);
        if (claims.isEmpty()) return;
        if (claims.size() != 1) conflict();
        var row = claims.getFirst();
        slot(e, store, row.text("slot_no"), true);
        var original = one(mapper.capacityOriginalBatch(e, store, batch));
        if (!cart.equals(original.text("cart_no"))
                || !"ACCEPTED".equals(original.text("status"))
                || "RELEASED".equals(row.text("status"))
                || row.text("batch_no") != null && !batch.equals(row.text("batch_no"))) conflict();
        if ("RESERVED".equals(row.text("status")))
            transition(
                    e,
                    store,
                    actor,
                    row.text("claim_no"),
                    row.number("version"),
                    "CONSUME",
                    "SLOT_ACCEPT_" + batch);
        if (mapper.bindCapacityBatch(e, store, cart, batch, actor) != 1) conflict();
        mapper.createScheduledDispatch(
                e,
                store,
                batch,
                local(slot(e, store, row.text("slot_no"), false).instant("dispatch_at")),
                actor);
    }

    @Override
    public void releaseOrder(String e, String store, String actor, String batch) {
        requirePrimary();
        var claims = mapper.capacityByBatch(e, store, batch);
        if (claims.isEmpty()) return;
        if (claims.size() != 1) conflict();
        var row = claims.getFirst();
        var serviceSlot = slot(e, store, row.text("slot_no"), true);
        var original = one(mapper.capacityOriginalBatch(e, store, batch));
        if (!"CANCELLED".equals(original.text("status"))) conflict();
        if ("RELEASED".equals(row.text("status"))) return;
        if ("RESERVED".equals(row.text("status"))) {
            transition(
                    e,
                    store,
                    actor,
                    row.text("claim_no"),
                    row.number("version"),
                    "RELEASE",
                    "SLOT_CANCEL_" + batch);
            return;
        }
        // Capacity already dispatched remains consumed; cancellation cannot undo the production
        // window.
        if (!clock.instant().isBefore(serviceSlot.instant("dispatch_at"))) return;
        if (mapper.releaseConsumedCapacity(e, row.text("slot_no"), row.decimal("quantity"), actor)
                        != 1
                || mapper.markCapacityReleased(e, row.text("claim_no"), actor) != 1) conflict();
    }

    private static void requirePrimary() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Capacity lifecycle must join original primary transaction");
    }

    @Override
    public SlotVO create(String e, String s, String a, CreateReq req, String key) {
        valid(e, s, a, key);
        if (req == null
                || req.startsAt() == null
                || req.endsAt() == null
                || req.orderCutoffAt() == null
                || req.dispatchAt() == null
                || !req.startsAt().isBefore(req.endsAt())
                || req.orderCutoffAt().isAfter(req.startsAt())
                || req.dispatchAt().isAfter(req.startsAt())
                || req.dispatchAt().isBefore(req.orderCutoffAt())
                || !req.orderCutoffAt().isAfter(clock.instant())) bad();
        quantity(req.capacity());
        valid(req.unit());
        return journal.execute(
                key,
                FoodCommandFingerprint.scope("CREATE_SERVICE_SLOT", e, s),
                a,
                FoodCommandFingerprint.hash(json.writeValueAsString(req)),
                SlotVO.class,
                () -> {
                    var stores = mapper.serviceSlotSelectStorePolicy(e, s);
                    if (stores.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
                    String no = BizKey.next("FSLT");
                    mapper.serviceSlotInsertServiceSlot(
                            e,
                            s,
                            no,
                            local(req.startsAt()),
                            local(req.endsAt()),
                            local(req.orderCutoffAt()),
                            local(req.dispatchAt()),
                            req.capacity(),
                            req.unit(),
                            a,
                            a);
                    return get(e, s, no);
                });
    }

    @Override
    public SlotVO get(String e, String s, String no) {
        var row = slot(e, s, no, false);
        return new SlotVO(
                no,
                time(row, "starts_at"),
                time(row, "ends_at"),
                time(row, "order_cutoff_at"),
                time(row, "dispatch_at"),
                decimal(row, "capacity"),
                decimal(row, "reserved_qty"),
                decimal(row, "consumed_qty"),
                (String) row.text("unit"),
                (String) row.text("status"),
                number(row, "version"));
    }

    @Override
    public ClaimVO reserve(String e, String s, String a, ClaimReq req, String key) {
        valid(e, s, a, key);
        if (req == null) bad();
        valid(req.slotNo(), req.sourceNo());
        quantity(req.quantity());
        return journal.execute(
                key,
                FoodCommandFingerprint.scope("RESERVE_SERVICE_SLOT", e, s),
                a,
                FoodCommandFingerprint.hash(json.writeValueAsString(req)),
                ClaimVO.class,
                () -> {
                    var row = slot(e, s, req.slotNo(), true);
                    var old = mapper.serviceSlotSelectCapacityClaim(e, s, req.sourceNo());
                    if (!old.isEmpty()) {
                        var c = old.getFirst();
                        if (!req.slotNo().equals(c.text("slot_no"))
                                || req.quantity().compareTo(decimal(c, "quantity")) != 0
                                || "RELEASED".equals(c.text("status"))) conflict();
                        return claim(c);
                    }
                    if (!"OPEN".equals(row.text("status"))
                            || !clock.instant().isBefore(time(row, "order_cutoff_at"))) conflict();
                    if (decimal(row, "reserved_qty")
                                    .add(decimal(row, "consumed_qty"))
                                    .add(req.quantity())
                                    .compareTo(decimal(row, "capacity"))
                            > 0) conflict();
                    String no = BizKey.next("FCAP");
                    mapper.serviceSlotInsertCapacityClaim(
                            e, s, no, req.slotNo(), req.sourceNo(), req.quantity(), a, a);
                    mapper.serviceSlotUpdateServiceSlot(req.quantity(), a, e, req.slotNo());
                    return claim(one(mapper.serviceSlotSelectCapacityClaim2(e, no)));
                });
    }

    @Override
    public ClaimVO transition(
            String e, String s, String a, String no, long expected, String action, String key) {
        valid(e, s, a, no, key);
        if (!Set.of("CONSUME", "RELEASE").contains(action)) bad();
        return journal.execute(
                key,
                FoodCommandFingerprint.scope("SERVICE_SLOT_" + action, e, s),
                a,
                FoodCommandFingerprint.hash(no, Long.toString(expected)),
                ClaimVO.class,
                () -> {
                    var before = one(mapper.serviceSlotSelectCapacityClaim3(e, s, no));
                    slot(e, s, (String) before.text("slot_no"), true);
                    var row = one(mapper.serviceSlotSelectCapacityClaim4(e, s, no));
                    String target = "CONSUME".equals(action) ? "CONSUMED" : "RELEASED";
                    if (target.equals(row.text("status"))) return claim(row);
                    if (number(row, "version") != expected
                            || !"RESERVED".equals(row.text("status"))) conflict();
                    BigDecimal qty = decimal(row, "quantity");
                    mapper.serviceSlotUpdateServiceSlot2(
                            qty,
                            "CONSUME".equals(action) ? qty : BigDecimal.ZERO,
                            a,
                            e,
                            row.text("slot_no"),
                            qty);
                    mapper.serviceSlotUpdateCapacityClaim(target, a, e, no);
                    return new ClaimVO(
                            no,
                            (String) row.text("slot_no"),
                            (String) row.text("source_no"),
                            qty,
                            target,
                            expected + 1);
                });
    }

    @Override
    public boolean dispatchDue(String e, String s, String source) {
        var rows = mapper.serviceSlotSelectCapacityClaim5(e, s, source);
        return rows.size() == 1
                && "CONSUMED".equals(rows.getFirst().text("status"))
                && !clock.instant().isBefore(time(rows.getFirst(), "dispatch_at"));
    }

    private FoodBusinessOperationRow slot(String e, String s, String no, boolean lock) {
        return one(mapper.serviceSlotSelectServiceSlot(e, s, no, lock));
    }

    private static ClaimVO claim(FoodBusinessOperationRow r) {
        return new ClaimVO(
                (String) r.text("claim_no"),
                (String) r.text("slot_no"),
                (String) r.text("source_no"),
                decimal(r, "quantity"),
                (String) r.text("status"),
                number(r, "version"));
    }

    private static FoodBusinessOperationRow one(List<FoodBusinessOperationRow> r) {
        if (r.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        return r.getFirst();
    }

    private static long number(FoodBusinessOperationRow r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private static BigDecimal decimal(FoodBusinessOperationRow r, String k) {
        return (BigDecimal) r.get(k);
    }

    private static Instant time(FoodBusinessOperationRow r, String k) {
        Object v = r.get(k);
        return v instanceof Timestamp t
                ? t.toLocalDateTime().toInstant(ZoneOffset.UTC)
                : ((LocalDateTime) v).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime local(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static void quantity(BigDecimal q) {
        if (q == null || q.signum() <= 0 || q.scale() > 6 || q.precision() > 18) bad();
    }

    private static void valid(String... v) {
        for (String s : v) if (s == null || s.isBlank() || s.length() > 128) bad();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
