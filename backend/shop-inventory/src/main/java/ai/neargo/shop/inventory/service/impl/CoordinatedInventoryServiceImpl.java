package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.*;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

import javax.sql.DataSource;

/**
 * All commands serialize on the reference before touching the existing reservation or stock ledger.
 */
@Service
@ConditionalOnInventory
public class CoordinatedInventoryServiceImpl implements CoordinatedInventoryService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReservationService reservations;
    private final OutboundService outbound;

    public CoordinatedInventoryServiceImpl(
            @Qualifier("invDataSource") DataSource ds,
            @Qualifier("invTransactionManager") PlatformTransactionManager manager,
            ReservationService reservations,
            OutboundService outbound) {
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(manager);
        this.reservations = reservations;
        this.outbound = outbound;
    }

    public Evidence reserve(Reserve c) {
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
                                reservations.reserve(
                                        c.binding().ownerId(),
                                        stockReference(c.reference()),
                                        List.of(
                                                new ReservationService.Line(
                                                        c.binding().itemId(),
                                                        c.binding().locationId(),
                                                        c.quantity())),
                                        0,
                                        3);
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
                                    "UPDATE inv_reservation_line SET released_qty=qty-issued_qty"
                                        + " WHERE reservation_id=?",
                                    h.reservationId);
                            jdbc.update(
                                    "UPDATE inv_reservation SET"
                                        + " status=?,released_at=UTC_TIMESTAMP(3) WHERE"
                                        + " reservation_id=?",
                                    l.issued == 0 ? "RELEASED" : "PARTIAL_RELEASED",
                                    h.reservationId);
                        }
                        jdbc.update(
                                "UPDATE inv_coordinated_reservation SET released=1 WHERE id=?",
                                h.id);
                    }
                    return evidence(head(r), op);
                });
    }

    public Evidence ship(Shipment c) {
        reference(c.reference());
        key(c.operationNo(), 64);
        quantity(c.quantity());
        key(c.carrier(), 64);
        key(c.trackingNo(), 128);
        key(c.operatorNo(), 64);
        return tx.execute(
                status -> {
                    Head h = head(c.reference());
                    if (h == null || h.binding == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    if (h.reserveOp.equals(c.operationNo()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    Ship old = shipment(h, c.operationNo());
                    if (old != null) {
                        same(old.quantity, c.quantity());
                        same(old.carrier, c.carrier());
                        same(old.tracking, c.trackingNo());
                        same(old.operator, c.operatorNo());
                        return evidence(h, c.operationNo());
                    }
                    if (h.released) throw BizException.of(ErrorCode.CONFLICT);
                    Line l = line(h);
                    if (c.quantity() > l.quantity - l.issued - l.released)
                        throw BizException.of(ErrorCode.CONFLICT);
                    unhold(h, c.quantity());
                    String doc =
                            outbound.postDirectly(
                                    new OutboundService.Draft(
                                            h.binding.ownerId(),
                                            h.binding.locationId(),
                                            "SALE",
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
                                    ? "COMMITTED"
                                    : ai.neargo.shop.inventory.support.InvEnums.ReservationStatus
                                            .PARTIALLY_COMMITTED,
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

    private Line line(Head h) {
        jdbc.queryForObject(
                "SELECT reservation_id FROM inv_reservation WHERE reservation_id=? FOR UPDATE",
                String.class,
                h.reservationId);
        var lines =
                jdbc.query(
                        "SELECT qty,issued_qty,released_qty FROM inv_reservation_line WHERE"
                            + " reservation_id=? FOR UPDATE",
                        (rs, n) -> new Line(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                        h.reservationId);
        if (lines.size() != 1) throw BizException.of(ErrorCode.CONFLICT);
        return lines.getFirst();
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
                != 1) throw BizException.of(ErrorCode.CONFLICT);
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
        if (found.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
    }

    private static String stockReference(Reference r) {
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

    private static void reference(Reference r) {
        if (r == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        key(r.client(), 32);
        key(r.externalRef(), 64);
    }

    private static void binding(Binding b) {
        if (b == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        key(b.ownerId(), 32);
        key(b.itemId(), 32);
        key(b.locationId(), 32);
        key(b.mappingVersion(), 64);
        if (b.requestReference() != null) key(b.requestReference(), 64);
    }

    private static void key(String s, int max) {
        if (s == null || s.isBlank() || s.length() > max || s.indexOf('\0') >= 0)
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void quantity(int q) {
        if (q <= 0) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void same(Object a, Object b) {
        if (!Objects.equals(a, b)) throw BizException.of(ErrorCode.CONFLICT);
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
