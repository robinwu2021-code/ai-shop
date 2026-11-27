package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.service.support.CommandBusinessKey;
import ai.neargo.shop.food.service.support.IndustryFollowUp;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimReq;
import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimVO;
import ai.neargo.shop.food.dto.ServiceSlotContracts.CreateReq;
import ai.neargo.shop.food.dto.ServiceSlotContracts.SlotVO;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.FoodIndustryOperationsMapper;
import ai.neargo.shop.food.lifecycle.ServiceSlotLifecycle;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

/**
 * 时段与容量在业务域（行业库，ADR-068 修订二），订单、购物车、到店单在订单域。容量按「预占—确认」处理：
 *
 * <ul>
 *   <li>预占、改数量：先在行业库提交；调用方的订单事务随后回滚时撤回（{@link IndustryFollowUp#compensateOnRollback}）。
 *   <li>确认（接单消耗、绑批次）与释放：订单事务里只做校验读取，写入在订单提交后执行，按目标状态可重复。
 *   <li>不用永久命令回执：预占按来源号、确认与释放按目标状态、建时段按请求键派生的时段号查回首次结果。
 * </ul>
 */
public class ServiceSlotServiceImpl implements ServiceSlotService, ServiceSlotLifecycle {
    private final FoodBusinessOperationsMapper mapper;
    private final FoodIndustryOperationsMapper industry;
    private final RequestIdempotencyPort idem;
    private final TransactionTemplate industryTx;
    private final Clock clock;
    private final ObjectMapper json;

    public ServiceSlotServiceImpl(
            FoodBusinessOperationsMapper mapper,
            FoodIndustryOperationsMapper industry,
            RequestIdempotencyPort idem,
            Clock clock,
            ObjectMapper json,
            PlatformTransactionManager industryManager) {
        this.mapper = mapper;
        this.industry = industry;
        this.industryTx = new TransactionTemplate(industryManager);
        this.idem = idem;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public void resizeSource(
            String e, String store, String actor, String source, BigDecimal quantity) {
        requirePrimary();
        quantity(quantity);
        var claims = industry.capacityBySource(e, store, source);
        if (claims.size() != 1) conflict();
        var row = claims.getFirst();
        var slot = slot(e, store, row.text("slot_no"), true);
        if (!"RESERVED".equals(row.text("status"))) conflict();
        BigDecimal original = row.decimal("quantity");
        BigDecimal delta = quantity.subtract(original);
        if (delta.signum() > 0 && !clock.instant().isBefore(slot.instant("order_cutoff_at")))
            conflict();
        resize(e, store, actor, source, row.text("slot_no"), delta, quantity, row.number("version"));
        // 订单事务回滚：把数量改回原值（按当前版本，可重复；已不是预占的不动）。
        IndustryFollowUp.compensateOnRollback(
                () -> {
                    var current = industry.capacityBySource(e, store, source);
                    if (current.size() == 1
                            && "RESERVED".equals(current.getFirst().text("status"))
                            && current.getFirst().decimal("quantity").compareTo(quantity) == 0)
                        resize(e, store, actor, source, current.getFirst().text("slot_no"),
                                original.subtract(quantity), original, current.getFirst().number("version"));
                });
    }

    private void resize(String e, String store, String actor, String source, String slotNo,
            BigDecimal delta, BigDecimal quantity, long version) {
        industryTx.executeWithoutResult(
                status -> {
                    if (industry.changeReservedCapacity(e, slotNo, delta, actor) != 1
                            || industry.resizeReservedCapacity(e, store, source, quantity, version, actor)
                                    != 1) conflict();
                });
    }

    @Override
    public void assertScheduledCart(String e, String store, String cart, Instant scheduledAt) {
        var claims = industry.capacityBySource(e, store, cart);
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
        var matches = industry.scheduledOrderSlots(e, store, local(scheduledAt));
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
        var claims = industry.capacityBySource(e, store, cart);
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
        var claims = industry.capacityBySource(e, store, cart);
        if (claims.isEmpty()) return;
        if (claims.size() != 1) conflict();
        var row = claims.getFirst();
        slot(e, store, row.text("slot_no"), true);
        var original = one(mapper.capacityOriginalBatch(e, store, batch));
        if (!cart.equals(original.text("cart_no"))
                || !"ACCEPTED".equals(original.text("status"))
                || "RELEASED".equals(row.text("status"))
                || row.text("batch_no") != null && !batch.equals(row.text("batch_no"))) conflict();
        // 接单在订单域先提交；随后在业务域确认消耗、绑批次、建定时出品单（都按目标状态或批次号可重复）。
        String claimNo = row.text("claim_no");
        LocalDateTime dispatchAt =
                local(slot(e, store, row.text("slot_no"), false).instant("dispatch_at"));
        IndustryFollowUp.afterTradeCommit(
                () -> {
                    var current = one(industry.serviceSlotSelectCapacityClaim3(e, store, claimNo));
                    if ("RESERVED".equals(current.text("status")))
                        transitionNow(e, store, actor, claimNo, number(current, "version"), "CONSUME");
                    industryTx.executeWithoutResult(
                            status -> {
                                if (industry.bindCapacityBatch(e, store, cart, batch, actor) != 1) conflict();
                            });
                    industry.createScheduledDispatch(e, store, batch, dispatchAt, actor);
                });
    }

    @Override
    public void releaseOrder(String e, String store, String actor, String batch) {
        requirePrimary();
        var claims = industry.capacityByBatch(e, store, batch);
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
        // 取消在订单域先提交；随后在业务域释放已消耗的容量（已释放的不再释放）。
        String claimNo = row.text("claim_no"), slotNo = row.text("slot_no");
        BigDecimal qty = row.decimal("quantity");
        IndustryFollowUp.afterTradeCommit(
                () -> industryTx.executeWithoutResult(
                        status -> {
                            var current = one(industry.serviceSlotSelectCapacityClaim3(e, store, claimNo));
                            if ("RELEASED".equals(current.text("status"))) return;
                            if (industry.releaseConsumedCapacity(e, slotNo, qty, actor) != 1
                                    || industry.markCapacityReleased(e, claimNo, actor) != 1) conflict();
                        }));
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
        // 时段号由请求键派生：重放时时段已在就按它返回（同键不同内容按冲突拒绝）。
        String no = CommandBusinessKey.derive("FSLT", "CREATE_SERVICE_SLOT", e, s, a, key);
        var existing = industry.serviceSlotSelectServiceSlot(e, s, no, false);
        if (!existing.isEmpty()) {
            var slot = get(e, s, no);
            if (!slot.startsAt().equals(req.startsAt())
                    || !slot.endsAt().equals(req.endsAt())
                    || slot.capacity().compareTo(req.capacity()) != 0
                    || !slot.unit().equals(req.unit())) conflict();
            return slot;
        }
        return industryTx.execute(
                status -> {
                    var stores = industry.serviceSlotSelectStorePolicy(e, s);
                    if (stores.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
                    industry.serviceSlotInsertServiceSlot(
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
        // 预占先在业务域提交（来源号是业务键，重放按来源查回）；调用方的订单事务回滚时撤回本次预占。
        var created = new java.util.concurrent.atomic.AtomicBoolean();
        ClaimVO result = industryTx.execute(
                status -> {
                    var row = slot(e, s, req.slotNo(), true);
                    var old = industry.serviceSlotSelectCapacityClaim(e, s, req.sourceNo());
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
                    industry.serviceSlotInsertCapacityClaim(
                            e, s, no, req.slotNo(), req.sourceNo(), req.quantity(), a, a);
                    industry.serviceSlotUpdateServiceSlot(req.quantity(), a, e, req.slotNo());
                    created.set(true);
                    return claim(one(industry.serviceSlotSelectCapacityClaim2(e, no)));
                });
        if (created.get())
            IndustryFollowUp.compensateOnRollback(
                    () -> {
                        var current = industry.serviceSlotSelectCapacityClaim3(e, s, result.claimNo());
                        if (!current.isEmpty() && "RESERVED".equals(current.getFirst().text("status")))
                            transitionNow(e, s, a, result.claimNo(), number(current.getFirst(), "version"), "RELEASE");
                    });
        return result;
    }

    @Override
    public ClaimVO transition(
            String e, String s, String a, String no, long expected, String action, String key) {
        valid(e, s, a, no, key);
        if (!Set.of("CONSUME", "RELEASE").contains(action)) bad();
        if (!IndustryFollowUp.inTradeTransaction()) return transitionNow(e, s, a, no, expected, action);
        // 在订单事务里：现在只校验，写入等订单提交后执行（按目标状态可重复），返回将达到的结果。
        var row = one(industry.serviceSlotSelectCapacityClaim3(e, s, no));
        String target = "CONSUME".equals(action) ? "CONSUMED" : "RELEASED";
        if (target.equals(row.text("status"))) return claim(row);
        if (number(row, "version") != expected || !"RESERVED".equals(row.text("status"))) conflict();
        IndustryFollowUp.afterTradeCommit(() -> transitionNow(e, s, a, no, expected, action));
        return new ClaimVO(
                no,
                (String) row.text("slot_no"),
                (String) row.text("source_no"),
                decimal(row, "quantity"),
                target,
                expected + 1);
    }

    /** 业务域里把预占转为消耗或释放；已是目标状态的按原结果返回。 */
    private ClaimVO transitionNow(String e, String s, String a, String no, long expected, String action) {
        return industryTx.execute(
                status -> {
                    var before = one(industry.serviceSlotSelectCapacityClaim3(e, s, no));
                    slot(e, s, (String) before.text("slot_no"), true);
                    var row = one(industry.serviceSlotSelectCapacityClaim4(e, s, no));
                    String target = "CONSUME".equals(action) ? "CONSUMED" : "RELEASED";
                    if (target.equals(row.text("status"))) return claim(row);
                    if (number(row, "version") != expected
                            || !"RESERVED".equals(row.text("status"))) conflict();
                    BigDecimal qty = decimal(row, "quantity");
                    industry.serviceSlotUpdateServiceSlot2(
                            qty,
                            "CONSUME".equals(action) ? qty : BigDecimal.ZERO,
                            a,
                            e,
                            row.text("slot_no"),
                            qty);
                    industry.serviceSlotUpdateCapacityClaim(target, a, e, no);
                    return new ClaimVO(
                            no,
                            (String) row.text("slot_no"),
                            (String) row.text("source_no"),
                            qty,
                            target,
                            expected + 1);
                });
    }

    /**
     * 接单后补做（订单域为准）：来源购物车已接单、容量仍是预占未绑批次的，消耗容量、绑批次、建定时出品单。
     * 预占超过两分钟才处理，给接单提交后的那一步留出时间。返回补做的条数。
     */
    public int recoverBindings(int limit) {
        int done = 0;
        var before = LocalDateTime.ofInstant(clock.instant().minusSeconds(120), ZoneOffset.UTC);
        for (var row : industry.reservedUnboundClaims(before, limit)) {
            String e = row.text("entity_no"), store = row.text("store_no"), cart = row.text("source_no");
            var batches = mapper.acceptedBatchesForCart(e, store, cart);
            if (batches.size() != 1) continue;
            String batch = batches.getFirst().text("batch_no");
            transitionNow(e, store, "SYSTEM", row.text("claim_no"), number(row, "version"), "CONSUME");
            industryTx.executeWithoutResult(
                    status -> {
                        if (industry.bindCapacityBatch(e, store, cart, batch, "SYSTEM") != 1) conflict();
                    });
            industry.createScheduledDispatch(
                    e, store, batch, local(slot(e, store, row.text("slot_no"), false).instant("dispatch_at")), "SYSTEM");
            done++;
        }
        return done;
    }

    @Override
    public boolean dispatchDue(String e, String s, String source) {
        var rows = industry.serviceSlotSelectCapacityClaim5(e, s, source);
        return rows.size() == 1
                && "CONSUMED".equals(rows.getFirst().text("status"))
                && !clock.instant().isBefore(time(rows.getFirst(), "dispatch_at"));
    }

    private FoodBusinessOperationRow slot(String e, String s, String no, boolean lock) {
        return one(industry.serviceSlotSelectServiceSlot(e, s, no, lock));
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
