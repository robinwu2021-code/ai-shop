package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateCustomerCartReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceSessionReq;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Challenge;
import ai.neargo.shop.food.dto.OfflineDraftContracts.ChallengeRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Enroll;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Envelope;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Lease;
import ai.neargo.shop.food.dto.OfflineDraftContracts.LeaseProof;
import ai.neargo.shop.food.dto.OfflineDraftContracts.LeaseRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Receipt;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Replay;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Signer;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Upload;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.OfflineDraftMapper;
import ai.neargo.shop.food.policy.OfflineDraftSignatures;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.OfflineDraftService;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Persistent draft audit and authenticated online replay; no offline order, funds or inventory
 * execution.
 */
public final class OfflineDraftServiceImpl implements OfflineDraftService {
    private final OfflineDraftMapper db;
    private final DiningIdentityService identities;
    private final CheckCartService carts;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OfflineDraftServiceImpl(
            OfflineDraftMapper db,
            DiningIdentityService identities,
            CheckCartService carts,
            ObjectMapper json,
            Clock clock,
            PlatformTransactionManager manager) {
        this.db = db;
        this.identities = identities;
        this.carts = carts;
        this.json = json;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Challenge challenge(String e, String s, String d, ChallengeRequest r) {
        scope(e, s, d);
        require(r != null);
        text(r.publicKey(), 128);
        text(r.credential(), 128);
        OfflineDraftSignatures.key(r.publicKey());
        String no = BizKey.next("FODC");
        var party =
                identities
                        .createDeviceSession(
                                e,
                                s,
                                d,
                                r.credential(),
                                new DeviceSessionReq(300),
                                "OFFLINE_CHALLENGE:" + no)
                        .party();
        var device = current(e, s, d, party.deviceGeneration());
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        var challenge =
                new Challenge(
                        no,
                        e,
                        s,
                        d,
                        device.generation(),
                        PricingSnapshotHash.of(r.publicKey()),
                        Base64.getEncoder().encodeToString(nonce),
                        clock.instant()
                                .plusSeconds(300)
                                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        atomic(
                () -> {
                    var row = stamp(e, s, d);
                    row.put("challenge_no", no);
                    row.put("device_no", d);
                    row.put("device_generation", device.generation());
                    row.put("public_key", r.publicKey());
                    row.put("challenge_json", json.writeValueAsString(challenge));
                    row.put("expires_at", OfflineDraftMapper.time(challenge.validUntil()));
                    db.insert("challenge", row);
                    return null;
                });
        return challenge;
    }

    @Override
    public Signer enroll(String e, String s, String d, Enroll r) {
        scope(e, s, d);
        require(r != null);
        id(r.challengeNo());
        text(r.signature(), 128);
        text(r.credential(), 128);
        var challenge =
                atomic(
                        () ->
                                required(
                                        db.one(
                                                "challenge",
                                                e,
                                                s,
                                                "challenge_no",
                                                r.challengeNo(),
                                                false)));
        require(d.equals(challenge.text("device_no")));
        var party =
                identities
                        .createDeviceSession(
                                e,
                                s,
                                d,
                                r.credential(),
                                new DeviceSessionReq(300),
                                "OFFLINE_ENROLL:" + r.challengeNo())
                        .party();
        current(e, s, d, party.deviceGeneration());
        var value = json.readValue(challenge.text("challenge_json"), Challenge.class);
        require(
                value.validUntil().isAfter(clock.instant())
                        && value.deviceGeneration() == party.deviceGeneration());
        OfflineDraftSignatures.verify(
                challenge.text("public_key"), r.signature(), "FOOD_OFFLINE_CHALLENGE_V1", value);
        return atomic(
                () -> {
                    var locked =
                            required(
                                    db.one(
                                            "challenge",
                                            e,
                                            s,
                                            "challenge_no",
                                            r.challengeNo(),
                                            true));
                    current(e, s, d, value.deviceGeneration());
                    var existing = db.signer(e, s, d, value.deviceGeneration());
                    if (existing != null) {
                        require(existing.text("public_key_hash").equals(value.publicKeyHash()));
                        return signer(existing);
                    }
                    var row = stamp(e, s, d);
                    row.put("key_no", BizKey.next("FODK"));
                    row.put("device_no", d);
                    row.put("device_generation", value.deviceGeneration());
                    row.put("public_key", locked.text("public_key"));
                    row.put("public_key_hash", value.publicKeyHash());
                    db.insert("signer", row);
                    db.completeChallenge(e, s, r.challengeNo(), (String) row.get("key_no"));
                    return signer(
                            required(db.one("signer", e, s, "key_no", row.get("key_no"), false)));
                });
    }

    @Override
    public Lease state(String e, String s, String d, CartAccess access) {
        scope(e, s, d);
        var party = identities.authenticateDeviceSession(e, s, access);
        require(d.equals(party.deviceNo()));
        return atomic(
                () -> {
                    var row = db.one("writer", e, s, "store_no", s, false);
                    if (row == null)
                        return new Lease(
                                null,
                                e,
                                s,
                                d,
                                party.deviceGeneration(),
                                null,
                                0,
                                null,
                                0,
                                "DRAFT_CAPTURE_ONLY");
                    return new Lease(
                            row.text("lease_no"),
                            e,
                            s,
                            row.text("device_no"),
                            row.get("device_generation") == null
                                    ? 0
                                    : row.number("device_generation"),
                            row.text("key_no"),
                            row.number("fence"),
                            row.get("expires_at") == null ? null : row.instant("expires_at"),
                            row.number("accepted_through"),
                            "DRAFT_CAPTURE_ONLY");
                });
    }

    @Override
    public Lease acquire(String e, String s, String d, LeaseRequest r) {
        scope(e, s, d);
        require(
                r != null
                        && r.expectedFence() >= 0
                        && r.lifetimeSeconds() >= 60
                        && r.lifetimeSeconds() <= 900);
        id(r.keyNo());
        text(r.requestKey(), 128);
        text(r.signature(), 128);
        var key = key(e, s, d, r.keyNo());
        var proof =
                new LeaseProof(
                        e,
                        s,
                        d,
                        key.number("device_generation"),
                        r.keyNo(),
                        r.expectedFence(),
                        r.lifetimeSeconds(),
                        r.requestKey());
        OfflineDraftSignatures.verify(
                key.text("public_key"), r.signature(), "FOOD_OFFLINE_LEASE_V1", proof);
        return atomic(
                () -> {
                    db.writer(e, s, clock.instant());
                    var writer = required(db.one("writer", e, s, "store_no", s, true));
                    current(e, s, d, key.number("device_generation"));
                    var old = db.audit(e, s, d, "ACQUIRE", r.requestKey());
                    if (old != null) {
                        require(old.text("request_hash").equals(PricingSnapshotHash.of(proof)));
                        return json.readValue(old.text("result_json"), Lease.class);
                    }
                    require(writer.number("fence") == r.expectedFence());
                    require(
                            writer.get("expires_at") == null
                                    || !writer.instant("expires_at").isAfter(clock.instant())
                                    || d.equals(writer.text("device_no")));
                    var result =
                            new Lease(
                                    BizKey.next("FODL"),
                                    e,
                                    s,
                                    d,
                                    key.number("device_generation"),
                                    r.keyNo(),
                                    Math.addExact(r.expectedFence(), 1),
                                    clock.instant()
                                            .plusSeconds(r.lifetimeSeconds())
                                            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                                    0,
                                    "DRAFT_CAPTURE_ONLY");
                    db.lease(
                            e,
                            s,
                            d,
                            result.deviceGeneration(),
                            r.keyNo(),
                            result.leaseNo(),
                            result.fence(),
                            result.validUntil(),
                            clock.instant());
                    audit(
                            e,
                            s,
                            d,
                            "ACQUIRE",
                            r.requestKey(),
                            proof,
                            r.signature(),
                            "GRANTED",
                            null,
                            result);
                    return result;
                });
    }

    @Override
    public Receipt capture(String e, String s, String d, Upload r) {
        scope(e, s, d);
        require(r != null && r.envelope() != null);
        var envelope = r.envelope();
        validate(e, s, d, envelope);
        text(r.signature(), 128);
        var key = key(e, s, d, envelope.keyNo());
        require(key.number("device_generation") == envelope.deviceGeneration());
        OfflineDraftSignatures.verify(
                key.text("public_key"), r.signature(), "FOOD_OFFLINE_DRAFT_V1", envelope);
        return atomic(
                () -> {
                    db.writer(e, s, clock.instant());
                    var writer = required(db.one("writer", e, s, "store_no", s, true));
                    String hash = PricingSnapshotHash.of(envelope);
                    var old = db.sequence(e, s, envelope.leaseNo(), envelope.sequence());
                    if (old != null && !old.text("payload_hash").equals(hash))
                        return rejected(
                                e,
                                s,
                                d,
                                writer,
                                envelope,
                                r.signature(),
                                "SEQUENCE_CONTENT_CONFLICT");
                    if (!valid(writer, envelope))
                        return rejected(
                                e, s, d, writer, envelope, r.signature(), "EXPIRED_OR_STALE_FENCE");
                    var device = identities.device(e, s, d);
                    if (!"ACTIVE".equals(device.status())
                            || device.generation() != envelope.deviceGeneration())
                        return rejected(
                                e,
                                s,
                                d,
                                writer,
                                envelope,
                                r.signature(),
                                "DEVICE_GENERATION_CHANGED");
                    if (old != null) return receipt(old, writer.number("accepted_through"));
                    if (envelope.sequence() != Math.addExact(writer.number("accepted_through"), 1))
                        return rejected(e, s, d, writer, envelope, r.signature(), "SEQUENCE_GAP");
                    String no = BizKey.next("FODD"), receipt = BizKey.next("FODA");
                    var row = stamp(e, s, d);
                    row.put("draft_no", no);
                    row.put("device_no", d);
                    row.put("device_generation", envelope.deviceGeneration());
                    row.put("key_no", envelope.keyNo());
                    row.put("lease_no", envelope.leaseNo());
                    row.put("fence", envelope.fence());
                    row.put("sequence_no", envelope.sequence());
                    row.put("payload_json", json.writeValueAsString(envelope));
                    row.put("payload_hash", hash);
                    row.put("signature_value", r.signature());
                    row.put("signature_hash", PricingSnapshotHash.of(r.signature()));
                    row.put("status", "CAPTURED");
                    row.put("receipt_no", receipt);
                    db.insert("draft", row);
                    db.acknowledge(e, s, envelope.sequence());
                    var result =
                            new Receipt(
                                    receipt,
                                    no,
                                    "CAPTURED",
                                    envelope.sequence(),
                                    hash,
                                    null,
                                    0,
                                    null);
                    audit(
                            e,
                            s,
                            d,
                            "CAPTURE",
                            envelope.leaseNo() + ":" + envelope.sequence(),
                            envelope,
                            r.signature(),
                            "CAPTURED",
                            null,
                            result);
                    return result;
                });
    }

    @Override
    public Receipt replay(String e, String s, String d, Replay r) {
        scope(e, s, d);
        require(r != null && r.access() != null);
        id(r.draftNo());
        var party = identities.authenticateDeviceSession(e, s, r.access());
        require(d.equals(party.deviceNo()));
        try {
            var start =
                    atomic(
                            () -> {
                                var row =
                                        required(
                                                db.one(
                                                        "draft",
                                                        e,
                                                        s,
                                                        "draft_no",
                                                        r.draftNo(),
                                                        true));
                                require(d.equals(row.text("device_no")));
                                if ("COMPLETE".equals(row.text("status"))
                                        || "REVIEW_REQUIRED".equals(row.text("status"))) return row;
                                var envelope =
                                        json.readValue(row.text("payload_json"), Envelope.class);
                                var writer = required(db.one("writer", e, s, "store_no", s, true));
                                if (!valid(writer, envelope)
                                        || party.deviceGeneration() != envelope.deviceGeneration()
                                        || row.text("session_no") != null
                                                && !row.text("session_no")
                                                        .equals(party.sessionNo()))
                                    return review(
                                            e, s, d, row, "LEASE_GENERATION_OR_SESSION_CHANGED");
                                // The original owner authenticates the token again inside the cart
                                // transaction.
                                identities.authenticateDeviceSession(e, s, r.access());
                                db.begin(e, s, r.draftNo(), party.sessionNo());
                                if (row.text("cart_no") == null) {
                                    var cart =
                                            carts.customerCreateCart(
                                                    s,
                                                    r.access(),
                                                    new CreateCustomerCartReq(
                                                            null,
                                                            party.sessionNo(),
                                                            envelope.fulfillment(),
                                                            null),
                                                    "OFFLINE_CREATE:" + r.draftNo());
                                    db.cart(e, s, r.draftNo(), cart.cart().cartNo());
                                }
                                return required(
                                        db.one("draft", e, s, "draft_no", r.draftNo(), false));
                            });
            if (!"REPLAYING".equals(start.text("status")))
                return receipt(start, start.number("sequence_no"));
            var envelope = json.readValue(start.text("payload_json"), Envelope.class);
            for (int index = (int) start.number("applied_lines");
                    index < envelope.lines().size();
                    index++) {
                final int step = index;
                boolean applied =
                        atomic(
                                () -> {
                                    var row =
                                            required(
                                                    db.one(
                                                            "draft",
                                                            e,
                                                            s,
                                                            "draft_no",
                                                            r.draftNo(),
                                                            true));
                                    if (!"REPLAYING".equals(row.text("status"))) return false;
                                    var writer =
                                            required(db.one("writer", e, s, "store_no", s, true));
                                    if (!valid(writer, envelope)) {
                                        review(e, s, d, row, "EXPIRED_OR_STALE_FENCE");
                                        return false;
                                    }
                                    require(party.sessionNo().equals(row.text("session_no")));
                                    var live =
                                            identities.authenticateDeviceSession(e, s, r.access());
                                    require(live.deviceGeneration() == envelope.deviceGeneration());
                                    if (row.number("applied_lines") > step) return true;
                                    require(row.number("applied_lines") == step);
                                    var cart =
                                            carts.customerGetCart(row.text("cart_no"), r.access());
                                    String ownerKey = "OFFLINE_LINE:" + r.draftNo() + ":" + step;
                                    var line = envelope.lines().get(step);
                                    var result =
                                            carts.customerReplaceLine(
                                                    row.text("cart_no"),
                                                    line.sourceLineRef(),
                                                    r.access(),
                                                    new ReplaceCartLineReq(
                                                            line, cart.cart().version()),
                                                    ownerKey);
                                    var progress = stamp(e, s, d);
                                    progress.put("draft_no", r.draftNo());
                                    progress.put("line_index", step);
                                    progress.put("source_line_ref", line.sourceLineRef());
                                    progress.put("cart_version", result.version());
                                    progress.put("owner_key", ownerKey);
                                    db.insert("line", progress);
                                    db.progress(e, s, r.draftNo(), step + 1);
                                    return true;
                                });
                if (!applied) break;
            }
            return atomic(
                    () -> {
                        var row = required(db.one("draft", e, s, "draft_no", r.draftNo(), true));
                        if ("REPLAYING".equals(row.text("status"))
                                && row.number("applied_lines") == envelope.lines().size()) {
                            String no = BizKey.next("FODA");
                            db.finish(e, s, r.draftNo(), "COMPLETE", null, no);
                            row = required(db.one("draft", e, s, "draft_no", r.draftNo(), false));
                            audit(
                                    e,
                                    s,
                                    d,
                                    "REPLAY",
                                    r.draftNo(),
                                    envelope,
                                    null,
                                    "COMPLETE",
                                    null,
                                    receipt(row, envelope.sequence()));
                        }
                        return receipt(row, envelope.sequence());
                    });
        } catch (BizException originalRejected) {
            return atomic(
                    () -> {
                        var row = required(db.one("draft", e, s, "draft_no", r.draftNo(), true));
                        require(d.equals(row.text("device_no")));
                        return receipt(
                                review(
                                        e,
                                        s,
                                        d,
                                        row,
                                        "OWNER_" + originalRejected.errorCode().name()),
                                row.number("sequence_no"));
                    });
        }
    }

    private FoodBusinessOperationRow review(
            String e, String s, String d, FoodBusinessOperationRow row, String reason) {
        if ("COMPLETE".equals(row.text("status"))) return row;
        String no = BizKey.next("FODA");
        db.finish(e, s, row.text("draft_no"), "REVIEW_REQUIRED", reason, no);
        var fresh = required(db.one("draft", e, s, "draft_no", row.text("draft_no"), false));
        audit(
                e,
                s,
                d,
                "REVIEW",
                row.text("draft_no"),
                json.readValue(row.text("payload_json"), Envelope.class),
                null,
                "REVIEW_REQUIRED",
                reason,
                receipt(fresh, row.number("sequence_no")));
        return fresh;
    }

    private Receipt rejected(
            String e,
            String s,
            String d,
            FoodBusinessOperationRow writer,
            Envelope env,
            String signature,
            String reason) {
        String hash = PricingSnapshotHash.of(env),
                key = PricingSnapshotHash.of(java.util.List.of(hash, reason));
        var old = db.audit(e, s, d, "REJECT", key);
        if (old != null) return json.readValue(old.text("result_json"), Receipt.class);
        var result =
                new Receipt(
                        BizKey.next("FODA"),
                        null,
                        "REVIEW_REQUIRED",
                        db.capturedThrough(e, s, env.leaseNo()),
                        hash,
                        null,
                        0,
                        reason);
        audit(e, s, d, "REJECT", key, env, signature, "REVIEW_REQUIRED", reason, result);
        return result;
    }

    private boolean valid(FoodBusinessOperationRow writer, Envelope env) {
        return Objects.equals(writer.text("lease_no"), env.leaseNo())
                && writer.number("fence") == env.fence()
                && Objects.equals(writer.text("device_no"), env.deviceNo())
                && writer.number("device_generation") == env.deviceGeneration()
                && Objects.equals(writer.text("key_no"), env.keyNo())
                && writer.get("expires_at") != null
                && writer.instant("expires_at").isAfter(clock.instant());
    }

    private FoodBusinessOperationRow key(String e, String s, String d, String no) {
        var key = atomic(() -> required(db.one("signer", e, s, "key_no", no, false)));
        require(d.equals(key.text("device_no")));
        return key;
    }

    private ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceVO current(
            String e, String s, String d, long gen) {
        var result = identities.device(e, s, d);
        require("ACTIVE".equals(result.status()) && result.generation() == gen);
        return result;
    }

    private Signer signer(FoodBusinessOperationRow row) {
        return new Signer(
                row.text("key_no"),
                row.text("entity_no"),
                row.text("store_no"),
                row.text("device_no"),
                row.number("device_generation"),
                row.text("public_key_hash"));
    }

    private Receipt receipt(FoodBusinessOperationRow row, long seq) {
        return new Receipt(
                row.text("receipt_no"),
                row.text("draft_no"),
                row.text("status"),
                seq,
                row.text("payload_hash"),
                row.text("cart_no"),
                (int) row.number("applied_lines"),
                row.text("reason"));
    }

    private void audit(
            String e,
            String s,
            String d,
            String kind,
            String key,
            Object payload,
            String signature,
            String status,
            String reason,
            Object result) {
        var row = stamp(e, s, d);
        row.put("receipt_no", result instanceof Receipt r ? r.receiptNo() : BizKey.next("FODA"));
        row.put("device_no", d);
        row.put("operation_kind", kind);
        row.put("request_key", key);
        row.put("request_hash", PricingSnapshotHash.of(payload));
        row.put("signature_hash", signature == null ? null : PricingSnapshotHash.of(signature));
        row.put("status", status);
        row.put("reason", reason);
        row.put("original_payload", json.writeValueAsString(payload));
        row.put("result_json", json.writeValueAsString(result));
        db.insert("audit", row);
    }

    private Map<String, Object> stamp(String e, String s, String actor) {
        var row = new LinkedHashMap<String, Object>();
        row.put("entity_no", e);
        row.put("store_no", s);
        row.put("created_by", actor);
        row.put("updated_by", actor);
        row.put("created_at", OfflineDraftMapper.time(clock.instant()));
        row.put("updated_at", OfflineDraftMapper.time(clock.instant()));
        return row;
    }

    private void validate(String e, String s, String d, Envelope env) {
        require(
                e.equals(env.entityNo())
                        && s.equals(env.storeNo())
                        && d.equals(env.deviceNo())
                        && env.deviceGeneration() > 0
                        && env.fence() > 0
                        && env.sequence() > 0
                        && java.util.Set.of("PICKUP", "DELIVERY").contains(env.fulfillment())
                        && env.lines() != null
                        && !env.lines().isEmpty()
                        && env.lines().size() <= 100);
        id(env.keyNo());
        id(env.leaseNo());
        var seen = new HashSet<String>();
        for (var line : env.lines()) {
            require(line != null);
            id(line.sourceLineRef());
            id(line.menuNo());
            id(line.menuItemNo());
            id(line.menuRevisionNo());
            id(line.goodsNo());
            id(line.skuNo());
            require(
                    seen.add(line.sourceLineRef())
                            && line.seatNo() == null
                            && line.courseNo() == null
                            && line.qty() != null
                            && line.qty().signum() > 0
                            && line.qty().scale() <= 6
                            && line.qty().compareTo(new java.math.BigDecimal("999999")) <= 0);
        }
        require(json.writeValueAsString(env).length() <= 131072);
    }

    private <T> T atomic(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private static FoodBusinessOperationRow required(FoodBusinessOperationRow row) {
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private static void scope(String e, String s, String d) {
        id(e);
        id(s);
        id(d);
    }

    private static void id(String value) {
        text(value, 32);
    }

    private static void text(String value, int max) {
        require(value != null && !value.isBlank() && value.length() <= max);
    }

    private static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
