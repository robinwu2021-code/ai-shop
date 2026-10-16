package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.shop.pay.mapper.points.PointsPostingMapper;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.BudgetLockMove;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.ReceivableMove;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy;
import ai.neargo.shop.pay.points.posting.PostingResult;
import ai.neargo.shop.pay.points.statement.StoreStatementService.Snapshot;
import ai.neargo.shop.pay.points.store.PostingStore;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Mapper details stay here; every financial method runs only under the posting service's pay
 * transaction.
 */
@Repository
public class MybatisPostingStore implements PostingStore {
    private final PointsPostingMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    public MybatisPostingStore(PointsPostingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void recordEvidence(EvidenceRef e) {
        try {
            mapper.registerEvidence(
                    e.evidenceNo(), e.currency(), e.externalMinor(), e.purpose(), e.sourceRef());
        } catch (DuplicateKeyException conflict) {
            var existing = mapper.evidence(e.evidenceNo());
            require(
                    existing != null
                            && e.currency().equals(existing.get("currency"))
                            && number(existing, "external_minor") == e.externalMinor()
                            && number(existing, "verified") == 1
                            && Objects.equals(existing.get("binding_purpose"), e.purpose())
                            && Objects.equals(existing.get("binding_ref"), e.sourceRef()),
                    "POINTS_EVIDENCE_CONFLICT");
        }
    }

    @Override
    public PostingResult claim(Voucher voucher, String hash, String no) {
        json.readTree(voucher.resultSnapshot());
        try {
            mapper.claim(
                    no,
                    voucher.bizRef(),
                    hash,
                    voucher.typeCode(),
                    voucher.currency(),
                    voucher.rate().pointsUnit(),
                    voucher.rate().minorUnit(),
                    voucher.resultSnapshot());
            return new PostingResult(no, false, voucher.resultSnapshot());
        } catch (DuplicateKeyException conflict) {
            Map<String, Object> prior = mapper.voucher(voucher.bizRef());
            if (prior == null || !hash.equals(prior.get("payload_hash"))) {
                throw new IllegalArgumentException("POINTS_IDEMPOTENCY_CONFLICT", conflict);
            }
            return new PostingResult(
                    (String) prior.get("voucher_no"), true, (String) prior.get("result_json"));
        }
    }

    @Override
    public void applyConditions(Voucher voucher, String no) {
        String type = voucher.typeCode();
        Map<String, Object> definition = mapper.lockType(type);
        require(
                definition != null
                        && voucher.currency().equals(definition.get("currency"))
                        && number(definition, "rate_points") == voucher.rate().pointsUnit()
                        && number(definition, "rate_minor") == voucher.rate().minorUnit(),
                "POINTS_DIMENSION_MISMATCH");
        require(mapper.lockPool(type) != null, "POINTS_POOL_MISSING");
        Map<AccountKey, Long> deltas = new TreeMap<>();
        for (PointsMove move : voucher.pointsMoves()) {
            deltas.merge(move.account(), move.deltaPoints(), Math::addExact);
        }
        for (AccountKey key : deltas.keySet()) {
            if (mapper.lockAccount(key) == null) {
                mapper.createAccount(key);
                require(mapper.lockAccount(key) != null, "POINTS_ACCOUNT_MISSING");
            }
        }
        for (var entry : deltas.entrySet()) {
            AccountKey key = entry.getKey();
            require(
                    mapper.moveAccount(key, key.bucket().column(), entry.getValue()) == 1,
                    "POINTS_BALANCE_INSUFFICIENT");
            long after = number(mapper.lockAccount(key), key.bucket().column());
            mapper.ledger(
                    UUID.randomUUID().toString(),
                    no,
                    key,
                    key.bucket().name(),
                    entry.getValue(),
                    after);
        }
        Map<FundBucket, Long> funds = new EnumMap<>(FundBucket.class);
        for (FundMove move : voucher.fundMoves()) {
            funds.merge(move.bucket(), move.deltaMinor(), Math::addExact);
        }
        Map<FundBucket, Long> budget = new EnumMap<>(FundBucket.class);
        for (BudgetLockMove move : voucher.budgetMoves()) {
            budget.merge(move.bucket(), move.deltaMinor(), Math::addExact);
        }
        require(
                mapper.movePool(
                                type,
                                funds.getOrDefault(FundBucket.U, 0L),
                                funds.getOrDefault(FundBucket.C, 0L),
                                funds.getOrDefault(FundBucket.D, 0L),
                                funds.getOrDefault(FundBucket.B, 0L),
                                voucher.reserveDelta(),
                                budget.getOrDefault(FundBucket.U, 0L),
                                budget.getOrDefault(FundBucket.B, 0L))
                        == 1,
                "POINTS_POOL_OR_RESERVE_INSUFFICIENT");
        Map<String, Object> pool = mapper.lockPool(type);
        for (var entry : funds.entrySet()) {
            mapper.flow(
                    UUID.randomUUID().toString(),
                    no,
                    type,
                    entry.getKey().name(),
                    entry.getValue(),
                    number(pool, entry.getKey().name().toLowerCase() + "_minor"));
        }
        if (voucher.reserveDelta() != 0) {
            mapper.flow(
                    UUID.randomUUID().toString(),
                    no,
                    type,
                    "RESERVED_B",
                    voucher.reserveDelta(),
                    number(pool, "reserved_b_minor"));
        }
        for (var entry : budget.entrySet()) {
            mapper.flow(
                    UUID.randomUUID().toString(),
                    no,
                    type,
                    "LOCKED_" + entry.getKey().name(),
                    entry.getValue(),
                    number(
                            pool,
                            entry.getKey() == FundBucket.U ? "locked_u_minor" : "locked_b_minor"));
        }
        for (var evidence :
                voucher.evidenceRefs().stream()
                        .sorted(Comparator.comparing(e -> e.evidenceNo()))
                        .toList()) {
            require(
                    mapper.consumeEvidence(
                                    evidence.evidenceNo(),
                                    evidence.currency(),
                                    evidence.externalMinor(),
                                    no,
                                    evidence.purpose(),
                                    evidence.sourceRef())
                            == 1,
                    "POINTS_EVIDENCE_UNVERIFIED_OR_REUSED");
        }
        for (PositionMove move :
                voucher.positionMoves().stream()
                        .sorted(
                                Comparator.comparing(PositionMove::kind)
                                        .thenComparing(PositionMove::positionNo))
                        .toList()) {
            json.readTree(move.payload());
            require(
                    move.originalPoints() >= 0 && move.expectedVersion() >= -1,
                    "POINTS_POSITION_INVALID");
            long revision = move.expectedVersion();
            if (revision == -1) {
                mapper.createPosition(move.kind().table(), type, move);
                revision = 0;
            }
            require(
                    mapper.movePosition(move.kind().table(), type, move, revision) == 1,
                    "POINTS_POSITION_STATE_CONFLICT");
            if (move.kind() == PositionMove.Kind.STATEMENT) {
                var statement = json.readValue(move.payload(), Snapshot.class);
                var period = statement.period();
                require(
                        move.positionNo().equals(statement.statementNo())
                                && type.equals(period.typeCode())
                                && period.fromMillis() < period.toMillis()
                                && statement.current().number() > 0,
                        "POINTS_STATEMENT_SNAPSHOT_INVALID");
                require(
                        mapper.indexStatement(
                                        move.positionNo(),
                                        type,
                                        period.storeNo(),
                                        period.fromMillis(),
                                        period.toMillis(),
                                        period.routeKey() == null ? "DEFAULT" : period.routeKey(),
                                        statement.current().number())
                                == 1,
                        "POINTS_STATEMENT_SNAPSHOT_INVALID");
                for (String source : statement.positionIds()) {
                    require(
                            mapper.bindStatement(source, type, period.storeNo(), move.positionNo())
                                    == 1,
                            "POINTS_STATEMENT_BINDING_CONFLICT");
                }
            }
            if (move.kind() == PositionMove.Kind.REFUND) {
                var snapshot = json.readTree(move.payload());
                var command = snapshot.path("command");
                var preparation = snapshot.path("preparation");
                require(
                        type.equals(command.path("typeCode").asText())
                                && move.positionNo()
                                        .equals(preparation.path("preparationNo").asText())
                                && move.positionNo()
                                        .equals(
                                                PointsKeys.position(
                                                        "F",
                                                        type,
                                                        command.path("afterSaleNo").asText()))
                                && !command.path("subOrderNo").asText().isBlank()
                                && preparation.path("inputHash").asText().length() == 64,
                        "POINTS_REFUND_SNAPSHOT_INVALID");
                require(
                        mapper.indexRefund(
                                        move.positionNo(),
                                        command.path("afterSaleNo").asText(),
                                        command.path("subOrderNo").asText(),
                                        preparation.path("inputHash").asText())
                                == 1,
                        "POINTS_REFUND_SNAPSHOT_INVALID");
            }
            if (move.kind() == PositionMove.Kind.TRANSFER) {
                var snapshot = json.readTree(move.payload());
                var request = snapshot.path("request");
                var command = snapshot.path("fundsCommand");
                String action = request.path("action").asText();
                if ("WITHDRAW_U".equals(action) || "WITHDRAW_B".equals(action)) {
                    String bucket = "WITHDRAW_U".equals(action) ? "U" : "B";
                    long amount = request.path("amountMinor").longValue();
                    require(
                            type.equals(request.path("typeCode").asText())
                                    && amount > 0
                                    && voucher.currency().equals(command.path("currency").asText())
                                    && amount == command.path("amountMinor").longValue()
                                    && move.positionNo().equals(command.path("ticketNo").asText())
                                    && move.positionNo()
                                            .equals(
                                                    PointsKeys.position(
                                                            "W",
                                                            type,
                                                            request.path("requestNo").asText()))
                                    && !command.path("providerIdem").asText().isBlank()
                                    && "TRANSFER".equals(command.path("purpose").asText())
                                    && ("U".equals(bucket)
                                            ? voucher.rate().toMinor(move.originalPoints())
                                                    == amount
                                            : move.originalPoints() == 0),
                            "POINTS_WITHDRAW_SNAPSHOT_INVALID");
                    require(
                            mapper.indexWithdrawal(
                                            move.positionNo(),
                                            bucket,
                                            amount,
                                            command.path("providerIdem").asText())
                                    == 1,
                            "POINTS_WITHDRAW_SNAPSHOT_INVALID");
                }
            }
            if (move.kind() == PositionMove.Kind.PAYOUT) {
                var snapshot = json.readTree(move.payload());
                var ticket = snapshot.path("ticket");
                var command = snapshot.path("fundsCommand");
                long gross = ticket.path("gross").longValue();
                long offset = ticket.path("offset").longValue();
                long cash = ticket.path("cash").longValue();
                require(
                        gross > 0
                                && offset >= 0
                                && cash >= 0
                                && Math.addExact(offset, cash) == gross
                                && voucher.currency().equals(command.path("currency").asText())
                                && move.positionNo().equals(ticket.path("ticketNo").asText())
                                && move.positionNo().equals(command.path("ticketNo").asText())
                                && !ticket.path("providerIdem").asText().isBlank()
                                && ticket.path("providerIdem")
                                        .asText()
                                        .equals(command.path("providerIdem").asText())
                                && command.path("amountMinor").longValue() == cash
                                && voucher.rate().toMinor(move.originalPoints()) == gross,
                        "POINTS_PAYOUT_SNAPSHOT_INVALID");
                require(
                        mapper.indexPayout(
                                        move.positionNo(),
                                        ticket.path("providerIdem").asText(),
                                        gross,
                                        offset,
                                        cash,
                                        command.path("currency").asText())
                                == 1,
                        "POINTS_PAYOUT_SNAPSHOT_INVALID");
                if (!"FAILED".equals(move.nextState())) {
                    for (var source : snapshot.path("command").path("positionIds")) {
                        require(
                                mapper.bindPayout(source.asText(), type, move.positionNo()) == 1,
                                "POINTS_PAYOUT_BINDING_CONFLICT");
                    }
                }
            }
            if (move.kind() == PositionMove.Kind.REDEEM && "PAYABLE".equals(move.nextState())) {
                mapper.releasePayoutBinding(move.positionNo());
            }
            if (move.kind() == PositionMove.Kind.GRANT || move.kind() == PositionMove.Kind.REDEEM) {
                var payload = json.readTree(move.payload());
                if (payload.hasNonNull("subOrderNo") && payload.hasNonNull("userNo")) {
                    mapper.indexOrderPosition(
                            move.kind().table(),
                            move.positionNo(),
                            payload.get("subOrderNo").asText(),
                            payload.get("userNo").asText());
                    if (move.kind() == PositionMove.Kind.REDEEM) {
                        mapper.indexRedeemStore(
                                move.positionNo(),
                                payload.path("orderNo").asText(),
                                payload.path("share").path("storeNo").asText());
                    }
                }
            }
        }
        for (ReserveMove move :
                voucher.reserveMoves().stream()
                        .sorted(Comparator.comparing(ReserveMove::reserveNo))
                        .toList()) {
            Map<String, Object> redemption =
                    mapper.lockPosition(PositionMove.Kind.REDEEM.table(), move.positionNo());
            require(
                    redemption != null
                            && type.equals(redemption.get("type_code"))
                            && voucher.rate().toMinor(number(redemption, "original_points"))
                                    == move.originalMinor(),
                    "POINTS_RESERVE_POSITION_MISMATCH");
            long revision = move.expectedVersion();
            if (revision == -1) {
                mapper.createReserve(type, move);
                revision = 0;
            }
            require(mapper.moveReserve(type, move, revision) == 1, "POINTS_RESERVE_STATE_CONFLICT");
        }
        for (ReceivableMove move :
                voucher.receivableMoves().stream()
                        .sorted(Comparator.comparing(ReceivableMove::receivableNo))
                        .toList()) {
            json.readTree(move.payload());
            long expected = move.expectedVersion();
            if (expected == -1) {
                mapper.createReceivable(type, move);
                expected = 0;
            }
            require(
                    mapper.moveReceivable(type, move, expected) == 1,
                    "POINTS_RECEIVABLE_STATE_CONFLICT");
        }
        require(
                mapper.lockedWithdrawalTotal(type, "U") == number(pool, "locked_u_minor")
                        && mapper.lockedWithdrawalTotal(type, "B")
                                == number(pool, "locked_b_minor"),
                "POINTS_WITHDRAW_LOCK_TOTAL_MISMATCH");
        require(
                mapper.reserveTotal(type) == number(pool, "reserved_b_minor"),
                "POINTS_RESERVE_TOTAL_MISMATCH");
        if (voucher.bizRef().startsWith("GRANT:") || voucher.bizRef().startsWith("HOLD:")) {
            long at = System.currentTimeMillis();
            long expiry =
                    ExpiryPolicy.regionalDeadline(
                            at,
                            Math.toIntExact(number(definition, "idle_days")),
                            ZoneId.of("Asia/Shanghai"));
            for (AccountKey key :
                    deltas.keySet().stream()
                            .filter(k -> k.holderType() == HolderType.USER)
                            .map(
                                    k ->
                                            new AccountKey(
                                                    k.typeCode(),
                                                    k.holderType(),
                                                    k.holderNo(),
                                                    Bucket.AVAILABLE))
                            .distinct()
                            .toList()) {
                mapper.renewUser(type, key.holderNo(), at, expiry);
            }
        }
        checkBacking(voucher, pool);
        mapper.outbox(
                type,
                "POINTS:" + no,
                json.writeValueAsString(
                        Map.of(
                                "kind",
                                "POINTS_POSTED",
                                "voucherNo",
                                no,
                                "bizRef",
                                voucher.bizRef())));
    }

    private void checkBacking(Voucher voucher, Map<String, Object> pool) {
        long total = 0;
        long issue = 0;
        long userStore = 0;
        for (Map<String, Object> row : mapper.totals(voucher.typeCode())) {
            long value = number(row, "points_total");
            total = Math.addExact(total, value);
            HolderType holder = HolderType.valueOf((String) row.get("holder_type"));
            if (holder == HolderType.ISSUE) {
                issue = Math.addExact(issue, value);
            }
            if (holder == HolderType.USER || holder == HolderType.STORE) {
                userStore = Math.addExact(userStore, value);
            }
        }
        require(
                total == 0
                        && voucher.rate().toMinor(issue) == number(pool, "u_minor")
                        && voucher.rate().toMinor(userStore) == number(pool, "c_minor"),
                "POINTS_BACKING_MISMATCH");
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing ledger column " + key);
        }
        return new BigInteger(value.toString()).longValueExact();
    }

    private void require(boolean valid, String reason) {
        if (!valid) {
            throw new IllegalStateException(reason);
        }
    }
}
