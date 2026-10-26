package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.service.support.BusinessCommandJournal;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CreateCartReq;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.food.dto.ReservationPreorderContracts.ConvertReq;
import ai.neargo.shop.food.dto.ReservationPreorderContracts.PreorderVO;
import ai.neargo.shop.food.dto.ReservationPreorderContracts.SaveReq;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.ReservationPreorderService;
import ai.neargo.shop.idem.IdempotencyService;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persisted preorders are drafts; conversion uses the original real staff order contract and key.
 */
public class ReservationPreorderServiceImpl implements ReservationPreorderService {
    private final BusinessCommandJournal journal;
    private final FoodBusinessOperationsMapper mapper;
    private final TransactionTemplate tx;
    private final IdempotencyService idem;
    private final CheckCartService carts;
    private final DiningOrderService orders;
    private final ObjectMapper json;
    private final Clock clock;

    public ReservationPreorderServiceImpl(
            FoodBusinessOperationsMapper mapper,
            PlatformTransactionManager manager,
            IdempotencyService idem,
            CheckCartService carts,
            DiningOrderService orders,
            ObjectMapper json,
            Clock clock) {
        this.mapper = mapper;
        journal = new BusinessCommandJournal(mapper, json);
        tx = new TransactionTemplate(manager);
        this.idem = idem;
        this.carts = carts;
        this.orders = orders;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public PreorderVO save(
            String e, String s, String a, String reservation, SaveReq req, String key) {
        valid(e, s, a, reservation, key);
        if (req == null
                || req.lines() == null
                || req.lines().isEmpty()
                || req.lines().size() > 100
                || req.cutoffAt() == null) bad();
        var refs = new HashSet<String>();
        for (var line : req.lines())
            if (line == null || line.sourceLineRef() == null || !refs.add(line.sourceLineRef()))
                bad();
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope("SAVE_PREORDER", e, s),
                                a,
                                FoodCommandFingerprint.hash(
                                        reservation, json.writeValueAsString(req)),
                                PreorderVO.class,
                                () -> {
                                    var appointment = reservation(e, s, reservation, true);
                                    if (number(appointment, "version") != req.reservationVersion()
                                            || !Set.of("HELD", "CONFIRMED")
                                                    .contains(appointment.text("status")))
                                        conflict();
                                    Instant end = timestamp(appointment, "start_at");
                                    if (!req.cutoffAt().isAfter(clock.instant())
                                            || req.cutoffAt().isAfter(end)) bad();
                                    if (mapper.reservationPreorderCountReservationPreorder(
                                                    e, s, reservation)
                                            > 0) conflict();
                                    String no = BizKey.next("FRPO");
                                    mapper.reservationPreorderInsertReservationPreorder(
                                            e,
                                            s,
                                            no,
                                            reservation,
                                            reservation,
                                            LocalDateTime.ofInstant(req.cutoffAt(), ZoneOffset.UTC),
                                            json.writeValueAsString(req.lines()),
                                            a,
                                            a);
                                    return get(e, s, reservation, no);
                                }));
    }

    @Override
    public PreorderVO get(String e, String s, String reservation, String no) {
        var row = preorder(e, s, reservation, no, false);
        var tree = json.readTree((String) row.text("selection_snapshot"));
        var lines = new ArrayList<OrderLineInput>();
        tree.forEach(n -> lines.add(json.treeToValue(n, OrderLineInput.class)));
        return new PreorderVO(
                no,
                reservation,
                (String) row.text("status"),
                timestamp(row, "cutoff_at"),
                List.copyOf(lines),
                (String) row.text("cart_no"),
                (String) row.text("batch_no"),
                number(row, "version"));
    }

    @Override
    public CartResultVO prepareCart(
            String e,
            String s,
            String a,
            String reservation,
            String no,
            long expected,
            String key) {
        valid(e, s, a, reservation, key);
        // Cart writes join their own short transactions; deterministic keys make partial
        // preparation recoverable.
        String check =
                tx.execute(
                        t -> {
                            var ap = reservation(e, s, reservation, true);
                            var p = preorder(e, s, reservation, no, true);
                            if (!"ARRIVED".equals(ap.text("status"))
                                    || ap.text("check_no") == null
                                    || !Set.of("DRAFT", "PREPARING", "READY")
                                            .contains(p.text("status"))) conflict();
                            if (!"DRAFT".equals(p.text("status"))
                                    && !a.equals(p.get("prepare_actor"))) conflict();
                            if ("DRAFT".equals(p.text("status"))) {
                                if (number(p, "version") != expected) conflict();
                                mapper.reservationPreorderUpdateReservationPreorder(a, a, e, no);
                            }
                            return (String) ap.text("check_no");
                        });
        var cart =
                carts.createCart(
                                e,
                                s,
                                a,
                                new CreateCartReq(check, null, null, "WAITER", "DINE_IN", null),
                                "RSP_CART_" + no)
                        .cart();
        var wanted = get(e, s, reservation, no).lines();
        for (var line : wanted) {
            var current = carts.getCart(e, s, cart.cartNo()).cart();
            var found =
                    current.lines().stream()
                            .filter(l -> l.sourceLineRef().equals(line.sourceLineRef()))
                            .findFirst();
            if (found.isPresent()) {
                if (!json.writeValueAsString(found.get()).equals(json.writeValueAsString(line)))
                    conflict();
                continue;
            }
            carts.replaceLine(
                    e,
                    s,
                    cart.cartNo(),
                    line.sourceLineRef(),
                    a,
                    new ReplaceCartLineReq(line, current.version()),
                    "RSP_LINE_" + no + "_" + line.sourceLineRef());
        }
        var ready = carts.getCart(e, s, cart.cartNo());
        tx.execute(
                t -> {
                    preorder(e, s, reservation, no, true);
                    mapper.reservationPreorderUpdateReservationPreorder2(cart.cartNo(), a, e, no);
                    return null;
                });
        return ready;
    }

    @Override
    public DiningOrderBatchVO convert(
            String e,
            String s,
            String a,
            String reservation,
            String no,
            ConvertReq req,
            String key) {
        valid(e, s, a, reservation, key);
        if (req == null || req.order() == null) bad();
        String hash = FoodCommandFingerprint.hash(json.writeValueAsString(req.order()));
        var frozen =
                tx.execute(
                        t -> {
                            var ap = reservation(e, s, reservation, true);
                            var row = preorder(e, s, reservation, no, true);
                            if (!"ARRIVED".equals(ap.text("status"))) conflict();
                            if (Set.of("CONVERTING", "CONVERTED").contains(row.text("status"))) {
                                if (!a.equals(row.get("convert_actor"))
                                        || !hash.equals(row.get("conversion_hash"))) conflict();
                                return (String) row.text("batch_no");
                            }
                            if (!"READY".equals(row.text("status"))
                                    || number(row, "version") != req.version()
                                    || !req.order().cartNo().equals(row.text("cart_no")))
                                conflict();
                            var cart = carts.getCart(e, s, req.order().cartNo()).cart();
                            if (!ap.text("check_no").equals(cart.checkNo())
                                    || cart.version() != req.order().version()) conflict();
                            if (!json.readTree(json.writeValueAsString(cart.lines()))
                                    .equals(json.readTree((String) row.text("selection_snapshot"))))
                                conflict();
                            mapper.reservationPreorderUpdateReservationPreorder3(
                                    hash, json.writeValueAsString(req.order()), a, a, e, no);
                            return (String) null;
                        });
        DiningOrderBatchVO batch =
                frozen == null
                        ? orders.staffPlace(e, s, a, req.order(), "RSVP_" + no)
                        : orders.recoverOriginal(e, s, frozen);
        tx.execute(
                t -> {
                    var row = preorder(e, s, reservation, no, true);
                    if (row.text("batch_no") != null
                            && !batch.batchNo().equals(row.text("batch_no"))) conflict();
                    String state =
                            Set.of("ACCEPTED", "BOUND").contains(batch.status())
                                    ? "CONVERTED"
                                    : "CONVERTING";
                    mapper.reservationPreorderUpdateReservationPreorder4(
                            state, batch.batchNo(), state, a, e, no);
                    return null;
                });
        return batch;
    }

    @Override
    public PreorderVO cancel(
            String e,
            String s,
            String a,
            String reservation,
            String no,
            long expected,
            String reason,
            String key) {
        valid(e, s, a, reservation, key);
        if (reason == null || reason.isBlank()) bad();
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope("CANCEL_PREORDER", e, s),
                                a,
                                FoodCommandFingerprint.hash(no, Long.toString(expected), reason),
                                PreorderVO.class,
                                () -> {
                                    reservation(e, s, reservation, true);
                                    var row = preorder(e, s, reservation, no, true);
                                    if (number(row, "version") != expected
                                            || !Set.of("DRAFT", "READY")
                                                    .contains(row.text("status"))) conflict();
                                    mapper.reservationPreorderUpdateReservationPreorder5(
                                            reason, a, e, no);
                                    return get(e, s, reservation, no);
                                }));
    }

    private FoodBusinessOperationRow reservation(String e, String s, String no, boolean lock) {
        return one(mapper.reservationPreorderSelectReservation(e, s, no, lock));
    }

    private FoodBusinessOperationRow preorder(
            String e, String s, String reservation, String no, boolean lock) {
        return one(
                mapper.reservationPreorderSelectReservationPreorder(e, s, reservation, no, lock));
    }

    private static FoodBusinessOperationRow one(List<FoodBusinessOperationRow> rows) {
        if (rows.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private static long number(FoodBusinessOperationRow row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static Instant timestamp(FoodBusinessOperationRow row, String key) {
        Object value = row.get(key);
        return value instanceof Timestamp t
                ? t.toLocalDateTime().toInstant(ZoneOffset.UTC)
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private static void valid(String... values) {
        for (var v : values) if (v == null || v.isBlank() || v.length() > 128) bad();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
