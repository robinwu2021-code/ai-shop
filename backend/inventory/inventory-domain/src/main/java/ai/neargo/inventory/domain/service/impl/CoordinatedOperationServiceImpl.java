package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.coordinated.CoordinatedOperations;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.OutboundService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.inventory.domain.support.InvEnums;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * 协同库存操作（{@link CoordinatedOperations}）。
 *
 * <p>每个命令先在 {@code inv_coordinated_reservation} 上按 reference 串行（插入占位行 + {@code FOR UPDATE}），
 * 之后才碰原有的预留与余额账：先到的 release 也会留下这行，于是迟到的 reserve 只能看到栅栏。
 *
 * <p>底下的 {@code inv_reservation} 行用 {@link ReservationService#LIFECYCLE_COORDINATED}：
 * 不到期回收（付款未知时不能放货），旧的按单号 / 履约单号动作一律 CONFLICT（{@link ReservationServiceImpl}），
 * 唯一的出口是这里的 release 与 ship。数量只记在原有的预留行、余额与流水里，这里不另设账。
 */
@Service
@ConditionalOnInventory
public class CoordinatedOperationServiceImpl implements CoordinatedOperations {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReservationService reservations;
    private final OutboundService outbound;

    public CoordinatedOperationServiceImpl(
            @Qualifier("invDataSource") DataSource ds,
            @Qualifier("invTransactionManager") PlatformTransactionManager manager,
            ReservationService reservations,
            OutboundService outbound) {
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(manager);
        this.reservations = reservations;
        this.outbound = outbound;
    }

    @Override
    public Evidence reserve(Reserve c) {
        if (c == null) throw bad();
        reference(c.reference());
        key(c.operationNo(), 64);
        binding(c.binding());
        quantity(c.quantity());
        return tx.execute(
                status -> {
                    claim(c.reference(), c.operationNo());
                    Head h = head(c.reference());
                    same(h.reserveOp, c.operationNo());
                    if (h.binding != null) {
                        same(h.binding, c.binding());
                        same(h.quantity, c.quantity());
                    }
                    if (h.released) return evidence(h, c.operationNo());
                    if (h.binding == null) {
                        validateActiveBinding(c.binding());
                        String id =
                                reservations.reserveCoordinated(
                                        c.binding().ownerId(),
                                        stockReference(c.reference()),
                                        new ReservationService.Line(
                                                c.binding().itemId(),
                                                c.binding().locationId(),
                                                c.quantity()));
                        jdbc.update(
                                "UPDATE inv_coordinated_reservation SET"
                                    + " owner_id=?,item_id=?,location_id=?,mapping_version=?,request_reference=?,original_quantity=?,reservation_id=?"
                                    + " WHERE id=?",
                                c.binding().ownerId(),
                                c.binding().itemId(),
                                c.binding().locationId(),
                                c.binding().mappingVersion(),
                                c.binding().requestReference(),
                                c.quantity(),
                                id,
                                h.id);
                    }
                    return evidence(head(c.reference()), c.operationNo());
                });
    }

    @Override
    public Evidence query(Reference r, String op) {
        reference(r);
        key(op, 64);
        return tx.execute(
                status -> {
                    Head h = head(r);
                    return h == null
                            ? new Evidence(Status.NOT_FOUND, op, 0, 0, 0, null, null)
                            : evidence(h, op);
                });
    }

    @Override
    public Evidence release(Reference r, String op) {
        reference(r);
        key(op, 64);
        return tx.execute(
                status -> {
                    claim(r, op);
                    Head h = head(r);
                    same(h.reserveOp, op);
                    if (!h.released) {
                        if (h.reservationId != null) {
                            Line l = line(h);
                            int remaining = l.quantity - l.issued - l.released;
                            if (remaining > 0) unhold(h, remaining);
                            jdbc.update(
                                    "UPDATE inv_reservation_line SET released_qty=qty-issued_qty,"
                                        + "updated_by='COORDINATED_RELEASE' WHERE reservation_id=?",
                                    h.reservationId);
                            jdbc.update(
                                    "UPDATE inv_reservation SET"
                                        + " status=?,released_at=UTC_TIMESTAMP(3) WHERE"
                                        + " reservation_id=?",
                                    l.issued == 0
                                            ? InvEnums.ReservationStatus.RELEASED
                                            : PARTIAL_RELEASED,
                                    h.reservationId);
                        }
                        jdbc.update(
                                "UPDATE inv_coordinated_reservation SET released=1 WHERE id=?",
                                h.id);
                    }
                    return evidence(head(r), op);
                });
    }

    @Override
    public Evidence ship(Shipment c) {
        if (c == null) throw bad();
        reference(c.reference());
        key(c.operationNo(), 64);
        quantity(c.quantity());
        key(c.carrier(), 64);
        key(c.trackingNo(), 128);
        key(c.operatorNo(), 64);
        return tx.execute(
                status -> {
                    Head h = head(c.reference());
                    if (h == null || h.binding == null)
                        throw InventoryException.of(InventoryError.NOT_FOUND);
                    if (h.reserveOp.equals(c.operationNo()))
                        throw InventoryException.of(InventoryError.CONFLICT);
                    Ship old = shipment(h, c.operationNo());
                    if (old != null) {
                        same(old.quantity, c.quantity());
                        same(old.carrier, c.carrier());
                        same(old.tracking, c.trackingNo());
                        same(old.operator, c.operatorNo());
                        return evidence(h, c.operationNo());
                    }
                    if (h.released) throw InventoryException.of(InventoryError.CONFLICT);
                    Line l = line(h);
                    if (c.quantity() > l.quantity - l.issued - l.released)
                        throw InventoryException.of(InventoryError.CONFLICT);
                    // 先退预留、再出库：与 ReservationServiceImpl#commit 同一顺序，中间不会凭空多出可用量
                    unhold(h, c.quantity());
                    String doc =
                            outbound.postDirectly(
                                    new OutboundService.Draft(
                                            h.binding.ownerId(),
                                            h.binding.locationId(),
                                            InvEnums.OutboundPurpose.SALE,
                                            stockReference(c.reference()),
                                            h.reservationId,
                                            null,
                                            LocalDateTime.now(),
                                            null,
                                            List.of(
                                                    new OutboundService.Line(
                                                            h.binding.itemId(),
                                                            c.quantity(),
                                                            null))),
                                    c.operatorNo());
                    jdbc.update(
                            "UPDATE inv_reservation_line SET"
                                + " issued_qty=issued_qty+?,outbound_no=?,issued_at=UTC_TIMESTAMP(3),updated_by=?"
                                + " WHERE reservation_id=?",
                            c.quantity(),
                            doc,
                            c.operatorNo(),
                            h.reservationId);
                    jdbc.update(
                            "UPDATE inv_reservation SET"
                                + " status=?,outbound_no=COALESCE(outbound_no,?),committed_at=UTC_TIMESTAMP(3)"
                                + " WHERE reservation_id=?",
                            l.issued + c.quantity() == l.quantity
                                    ? InvEnums.ReservationStatus.COMMITTED
                                    : InvEnums.ReservationStatus.PARTIALLY_COMMITTED,
                            doc,
                            h.reservationId);
                    jdbc.update(
                            "INSERT INTO"
                                + " inv_coordinated_shipment(client_code,external_ref,operation_no,quantity,carrier,tracking_no,operator_no,outbound_no)"
                                + " VALUES (?,?,?,?,?,?,?,?)",
                            c.reference().client(),
                            c.reference().externalRef(),
                            c.operationNo(),
                            c.quantity(),
                            c.carrier(),
                            c.trackingNo(),
                            c.operatorNo(),
                            doc);
                    return evidence(h, c.operationNo());
                });
    }

    // ────────────────────────────────────────────────────────────────────

    private static final String PARTIAL_RELEASED = "PARTIAL_RELEASED";

    private void claim(Reference r, String op) {
        jdbc.update(
                "INSERT INTO"
                    + " inv_coordinated_reservation(client_code,external_ref,reserve_operation_no)"
                    + " VALUES (?,?,?) ON DUPLICATE KEY UPDATE id=id",
                r.client(),
                r.externalRef(),
                op);
    }

    private Head head(Reference r) {
        var rows =
                jdbc.query(
                        "SELECT * FROM inv_coordinated_reservation WHERE client_code=? AND"
                            + " external_ref=? FOR UPDATE",
                        (rs, n) ->
                                new Head(
                                        rs.getLong("id"),
                                        r,
                                        rs.getString("reserve_operation_no"),
                                        rs.getString("owner_id") == null
                                                ? null
                                                : new Binding(
                                                        rs.getString("owner_id"),
                                                        rs.getString("item_id"),
                                                        rs.getString("location_id"),
                                                        rs.getString("mapping_version"),
                                                        rs.getString("request_reference")),
                                        rs.getInt("original_quantity"),
                                        rs.getString("reservation_id"),
                                        rs.getBoolean("released")),
                        r.client(),
                        r.externalRef());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 读并核对底下的预留行；与协同记录对不上（被旧路径改过、被手工改过）一律不出证据。 */
    private Line line(Head h) {
        var heads =
                jdbc.queryForList(
                        "SELECT lifecycle_version,status,owner_id FROM inv_reservation WHERE"
                            + " reservation_id=? FOR UPDATE",
                        h.reservationId);
        if (heads.size() != 1
                || !Integer.valueOf(ReservationService.LIFECYCLE_COORDINATED)
                        .equals(((Number) heads.getFirst().get("lifecycle_version")).intValue())
                || !h.binding.ownerId().equals(heads.getFirst().get("owner_id")))
            throw InventoryException.of(InventoryError.CONFLICT);
        var lines =
                jdbc.query(
                        "SELECT qty,issued_qty,released_qty FROM inv_reservation_line WHERE"
                            + " reservation_id=? FOR UPDATE",
                        (rs, n) -> new Line(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                        h.reservationId);
        if (lines.size() != 1) throw InventoryException.of(InventoryError.CONFLICT);
        Line l = lines.getFirst();
        if (l.quantity != h.quantity
                || l.issued < 0
                || l.released < 0
                || (long) l.issued + l.released > l.quantity)
            throw InventoryException.of(InventoryError.CONFLICT);
        String expected =
                h.released
                        ? (l.issued == 0 ? InvEnums.ReservationStatus.RELEASED : PARTIAL_RELEASED)
                        : l.issued == l.quantity
                                ? InvEnums.ReservationStatus.COMMITTED
                                : l.issued > 0
                                        ? InvEnums.ReservationStatus.PARTIALLY_COMMITTED
                                        : InvEnums.ReservationStatus.HELD;
        if (!expected.equals(heads.getFirst().get("status"))
                || (h.released ? l.issued + l.released != l.quantity : l.released != 0))
            throw InventoryException.of(InventoryError.CONFLICT);
        return l;
    }

    private Ship shipment(Head h, String op) {
        var rows =
                jdbc.query(
                        "SELECT * FROM inv_coordinated_shipment WHERE client_code=? AND"
                            + " external_ref=? AND operation_no=? FOR SHARE",
                        (rs, n) ->
                                new Ship(
                                        rs.getInt("quantity"),
                                        rs.getString("carrier"),
                                        rs.getString("tracking_no"),
                                        rs.getString("operator_no"),
                                        rs.getString("outbound_no")),
                        h.ref.client(),
                        h.ref.externalRef(),
                        op);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Evidence evidence(Head h, String op) {
        Line l = h.reservationId == null ? new Line(0, 0, 0) : line(h);
        int held = l.quantity - l.issued - l.released;
        if (h.reserveOp.equals(op))
            return new Evidence(
                    h.released ? Status.RELEASED : Status.RESERVED,
                    op,
                    h.released ? 0 : h.quantity,
                    held,
                    l.issued,
                    h.binding,
                    null);
        Ship s = shipment(h, op);
        return new Evidence(
                s == null ? Status.NOT_FOUND : Status.SHIPPED,
                op,
                s == null ? 0 : s.quantity,
                held,
                l.issued,
                h.binding,
                s == null ? null : s.document);
    }

    private void unhold(Head h, int qty) {
        if (jdbc.update(
                        "UPDATE inv_stock_balance SET reserved=reserved-?,version=version+1 WHERE"
                            + " owner_id=? AND item_id=? AND location_id=? AND reserved>=?",
                        qty,
                        h.binding.ownerId(),
                        h.binding.itemId(),
                        h.binding.locationId(),
                        qty)
                != 1) throw InventoryException.of(InventoryError.CONFLICT);
    }

    private void validateActiveBinding(Binding b) {
        var found =
                jdbc.queryForList(
                        "SELECT i.item_id FROM inv_owner o JOIN inv_item i ON i.owner_id=o.owner_id"
                            + " JOIN inv_location l ON l.owner_id=o.owner_id WHERE o.owner_id=? AND"
                            + " i.item_id=? AND l.location_id=? AND o.status='ACTIVE' AND"
                            + " i.status='ACTIVE' AND i.retired_at IS NULL AND l.status='ACTIVE'"
                            + " FOR SHARE",
                        String.class,
                        b.ownerId(),
                        b.itemId(),
                        b.locationId());
        if (found.size() != 1) throw InventoryException.of(InventoryError.NOT_FOUND);
    }

    /** 底下预留行的单号：协调方单号的摘要，不与任何订单号重名，旧的按单号动作也就碰不到它。 */
    static String stockReference(Reference r) {
        try {
            return "X:"
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    (r.client() + "\0" + r.externalRef())
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static InventoryException bad() {
        return InventoryException.of(InventoryError.BAD_REQUEST);
    }

    private static void reference(Reference r) {
        if (r == null) throw bad();
        key(r.client(), 32);
        key(r.externalRef(), 64);
    }

    private static void binding(Binding b) {
        if (b == null) throw bad();
        key(b.ownerId(), 32);
        key(b.itemId(), 32);
        key(b.locationId(), 32);
        key(b.mappingVersion(), 64);
        if (b.requestReference() != null) key(b.requestReference(), 64);
    }

    private static void key(String s, int max) {
        if (s == null || s.isBlank() || s.length() > max || s.indexOf('\0') >= 0) throw bad();
    }

    private static void quantity(int q) {
        if (q <= 0) throw bad();
    }

    private static void same(Object a, Object b) {
        if (!Objects.equals(a, b)) throw InventoryException.of(InventoryError.CONFLICT);
    }

    private record Head(
            long id,
            Reference ref,
            String reserveOp,
            Binding binding,
            int quantity,
            String reservationId,
            boolean released) {}

    private record Line(int quantity, int issued, int released) {}

    private record Ship(
            int quantity, String carrier, String tracking, String operator, String document) {}
}
