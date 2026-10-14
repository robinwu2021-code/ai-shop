package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.shop.pay.mapper.points.PointsPostingMapper;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.posting.PostingResult;
import ai.neargo.shop.pay.points.store.PostingStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Mapper details stay here; every financial method runs only under the posting service's pay transaction. */
@Repository
public class MybatisPostingStore implements PostingStore {
    private final PointsPostingMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    public MybatisPostingStore(PointsPostingMapper mapper) { this.mapper = mapper; }

    @Override
    public PostingResult claim(Voucher voucher, String hash, String no) {
        json.readTree(voucher.resultSnapshot());
        try {
            mapper.claim(no, voucher.bizRef(), hash, voucher.typeCode(), voucher.currency(),
                    voucher.rate().pointsUnit(), voucher.rate().minorUnit(), voucher.resultSnapshot());
            return new PostingResult(no, false, voucher.resultSnapshot());
        } catch (DuplicateKeyException conflict) {
            Map<String, Object> prior = mapper.voucher(voucher.bizRef());
            if (prior == null || !hash.equals(prior.get("payload_hash"))) {
                throw new IllegalArgumentException("POINTS_IDEMPOTENCY_CONFLICT", conflict);
            }
            return new PostingResult((String) prior.get("voucher_no"), true, (String) prior.get("result_json"));
        }
    }

    @Override
    public void applyConditions(Voucher voucher, String no) {
        String type = voucher.typeCode();
        Map<String, Object> definition = mapper.lockType(type);
        require(definition != null && voucher.currency().equals(definition.get("currency"))
                && number(definition, "rate_points") == voucher.rate().pointsUnit()
                && number(definition, "rate_minor") == voucher.rate().minorUnit(), "POINTS_DIMENSION_MISMATCH");
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
            require(mapper.moveAccount(key, key.bucket().column(), entry.getValue()) == 1,
                    "POINTS_BALANCE_INSUFFICIENT");
            long after = number(mapper.lockAccount(key), key.bucket().column());
            mapper.ledger(UUID.randomUUID().toString(), no, key, key.bucket().name(), entry.getValue(), after);
        }
        Map<FundBucket, Long> funds = new EnumMap<>(FundBucket.class);
        for (FundMove move : voucher.fundMoves()) { funds.merge(move.bucket(), move.deltaMinor(), Math::addExact); }
        require(mapper.movePool(type, funds.getOrDefault(FundBucket.U, 0L), funds.getOrDefault(FundBucket.C, 0L),
                funds.getOrDefault(FundBucket.D, 0L), funds.getOrDefault(FundBucket.B, 0L), voucher.reserveDelta()) == 1,
                "POINTS_POOL_OR_RESERVE_INSUFFICIENT");
        Map<String, Object> pool = mapper.lockPool(type);
        for (var entry : funds.entrySet()) {
            mapper.flow(UUID.randomUUID().toString(), no, type, entry.getKey().name(), entry.getValue(),
                    number(pool, entry.getKey().name().toLowerCase() + "_minor"));
        }
        if (voucher.reserveDelta() != 0) {
            mapper.flow(UUID.randomUUID().toString(), no, type, "RESERVED_B", voucher.reserveDelta(),
                    number(pool, "reserved_b_minor"));
        }
        for (var evidence : voucher.evidenceRefs().stream()
                .sorted(Comparator.comparing(e -> e.evidenceNo())).toList()) {
            require(mapper.consumeEvidence(evidence.evidenceNo(), evidence.currency(), evidence.externalMinor(), no) == 1,
                    "POINTS_EVIDENCE_UNVERIFIED_OR_REUSED");
        }
        for (PositionMove move : voucher.positionMoves().stream().sorted(Comparator.comparing(PositionMove::kind)
                .thenComparing(PositionMove::positionNo)).toList()) {
            json.readTree(move.payload());
            require(move.originalPoints() >= 0 && move.expectedVersion() >= -1, "POINTS_POSITION_INVALID");
            long revision = move.expectedVersion();
            if (revision == -1) {
                mapper.createPosition(move.kind().table(), type, move);
                revision = 0;
            }
            require(mapper.movePosition(move.kind().table(), type, move, revision) == 1,
                    "POINTS_POSITION_STATE_CONFLICT");
        }
        for (ReserveMove move : voucher.reserveMoves().stream()
                .sorted(Comparator.comparing(ReserveMove::reserveNo)).toList()) {
            Map<String, Object> redemption = mapper.lockPosition(PositionMove.Kind.REDEEM.table(), move.positionNo());
            require(redemption != null && type.equals(redemption.get("type_code"))
                    && voucher.rate().toMinor(number(redemption, "original_points")) == move.originalMinor(),
                    "POINTS_RESERVE_POSITION_MISMATCH");
            long revision = move.expectedVersion();
            if (revision == -1) {
                mapper.createReserve(type, move);
                revision = 0;
            }
            require(mapper.moveReserve(type, move, revision) == 1, "POINTS_RESERVE_STATE_CONFLICT");
        }
        require(mapper.reserveTotal(type) == number(pool, "reserved_b_minor"), "POINTS_RESERVE_TOTAL_MISMATCH");
        checkBacking(voucher, pool);
        mapper.outbox(type, "POINTS:" + no,
                json.writeValueAsString(Map.of("kind", "POINTS_POSTED", "voucherNo", no, "bizRef", voucher.bizRef())));
    }

    private void checkBacking(Voucher voucher, Map<String, Object> pool) {
        long total = 0;
        long issue = 0;
        long userStore = 0;
        for (Map<String, Object> row : mapper.totals(voucher.typeCode())) {
            long value = number(row, "points_total");
            total = Math.addExact(total, value);
            HolderType holder = HolderType.valueOf((String) row.get("holder_type"));
            if (holder == HolderType.ISSUE) { issue = Math.addExact(issue, value); }
            if (holder == HolderType.USER || holder == HolderType.STORE) {
                userStore = Math.addExact(userStore, value);
            }
        }
        require(total == 0 && voucher.rate().toMinor(issue) == number(pool, "u_minor")
                && voucher.rate().toMinor(userStore) == number(pool, "c_minor"), "POINTS_BACKING_MISMATCH");
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) { throw new IllegalStateException("Missing ledger column " + key); }
        return new BigInteger(value.toString()).longValueExact();
    }

    private void require(boolean valid, String reason) {
        if (!valid) { throw new IllegalStateException(reason); }
    }
}
