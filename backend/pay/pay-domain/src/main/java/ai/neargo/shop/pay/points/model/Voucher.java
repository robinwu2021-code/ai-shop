package ai.neargo.shop.pay.points.model;

import java.util.List;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable command and result snapshot for the sole points accounting entry. */
public record Voucher(String bizRef, String typeCode, String currency, RedeemRate rate,
                      List<PointsMove> pointsMoves, List<FundMove> fundMoves,
                      List<PositionMove> positionMoves, long reserveDelta,
                      List<EvidenceRef> evidenceRefs, String resultSnapshot, List<ReserveMove> reserveMoves,
                      List<BudgetLockMove> budgetMoves,List<ReceivableMove> receivableMoves) {
    public Voucher {
        pointsMoves = List.copyOf(pointsMoves);
        fundMoves = List.copyOf(fundMoves);
        positionMoves = List.copyOf(positionMoves);
        evidenceRefs = List.copyOf(evidenceRefs);
        reserveMoves = List.copyOf(reserveMoves);
        budgetMoves = List.copyOf(budgetMoves);
        receivableMoves = List.copyOf(receivableMoves);
    }
    public Voucher(String bizRef,String typeCode,String currency,RedeemRate rate,
                   List<PointsMove> pointsMoves,List<FundMove> fundMoves,List<PositionMove> positionMoves,
                   long reserveDelta,List<EvidenceRef> evidenceRefs,String resultSnapshot,
                   List<ReserveMove> reserveMoves,
                   List<BudgetLockMove> budgetMoves) {
        this(bizRef,typeCode,currency,rate,pointsMoves,fundMoves,positionMoves,reserveDelta,evidenceRefs,
                resultSnapshot,reserveMoves,budgetMoves,List.of());
    }
    public Voucher(String bizRef,String typeCode,String currency,RedeemRate rate,
                   List<PointsMove> pointsMoves,List<FundMove> fundMoves,List<PositionMove> positionMoves,
                   long reserveDelta,List<EvidenceRef> evidenceRefs,String resultSnapshot,
                   List<ReserveMove> reserveMoves) {
        this(bizRef,typeCode,currency,rate,pointsMoves,fundMoves,positionMoves,reserveDelta,evidenceRefs,
                resultSnapshot,reserveMoves,List.of());
    }
    public Voucher(String bizRef, String typeCode, String currency, RedeemRate rate,
                   List<PointsMove> pointsMoves, List<FundMove> fundMoves, List<PositionMove> positionMoves,
                   long reserveDelta, List<EvidenceRef> evidenceRefs, String resultSnapshot) {
        this(bizRef, typeCode, currency, rate, pointsMoves, fundMoves, positionMoves, reserveDelta,
                evidenceRefs, resultSnapshot, List.of());
    }

    /** Checks conservation before the transaction acquires any ledger locks. */
    public void validateV1ToV4() {
        if (bizRef == null || bizRef.isBlank() || typeCode == null || typeCode.isBlank()
                || currency == null || currency.isBlank() || resultSnapshot == null) {
            throw new IllegalArgumentException("Missing immutable voucher identity or result");
        }
        Objects.requireNonNull(rate, "rate");
        for (BudgetLockMove move:budgetMoves) {
            if ((move.bucket()!=FundBucket.U && move.bucket()!=FundBucket.B) || move.deltaMinor()==0) {
                throw new IllegalArgumentException("Invalid outgoing budget reservation");
            }
        }
        long reserveTotal = 0;
        for (ReserveMove reserve : reserveMoves) {
            if (reserve.reserveNo() == null || reserve.positionNo() == null || reserve.originalMinor() < 0
                    || reserve.expectedVersion() < -1) {
                throw new IllegalArgumentException("Invalid reserve position");
            }
            reserveTotal = Math.addExact(reserveTotal, reserve.remainingDelta());
        }
        if (reserveTotal != reserveDelta) {
            throw new IllegalArgumentException("Reserve aggregate does not match its position records");
        }
        BigInteger total = BigInteger.ZERO;
        BigInteger issue = BigInteger.ZERO;
        BigInteger userStore = BigInteger.ZERO;
        for (PointsMove move : pointsMoves) {
            AccountKey key = move.account();
            if (!typeCode.equals(key.typeCode()) || key.holderNo() == null || key.holderNo().isBlank()
                    || !legal(key.holderType(), key.bucket())) {
                throw new IllegalArgumentException("Illegal points account dimension");
            }
            rate.requireAligned(move.deltaPoints());
            BigInteger delta = BigInteger.valueOf(move.deltaPoints());
            total = total.add(delta);
            if (key.holderType() == HolderType.ISSUE) { issue = issue.add(delta); }
            if (key.holderType() == HolderType.USER || key.holderType() == HolderType.STORE) {
                userStore = userStore.add(delta);
            }
        }
        if (total.signum() != 0) { throw new IllegalArgumentException("Unbalanced points voucher"); }
        Map<FundBucket, BigInteger> funds = new EnumMap<>(FundBucket.class);
        BigInteger cashChange = BigInteger.ZERO;
        for (FundMove move : fundMoves) {
            Objects.requireNonNull(move.bucket(), "fund bucket");
            BigInteger delta = BigInteger.valueOf(move.deltaMinor());
            funds.merge(move.bucket(), delta, BigInteger::add);
            cashChange = cashChange.add(delta);
        }
        if (rate.toMinor(issue.longValueExact()) != funds.getOrDefault(FundBucket.U, BigInteger.ZERO)
                .longValueExact()
                || rate.toMinor(userStore.longValueExact()) != funds.getOrDefault(FundBucket.C, BigInteger.ZERO)
                .longValueExact()) {
            throw new IllegalArgumentException("Points rights lack real U/C backing");
        }
        BigInteger receivableChange=BigInteger.ZERO;
        for (ReceivableMove move:receivableMoves) {
            if (move.receivableNo()==null || move.receivableNo().isBlank() || move.storeNo()==null
                    || move.storeNo().isBlank() || move.expectedVersion() < -1 || move.originalMinor()<=0
                    || move.recoveredDelta()<0 || move.payload()==null) {
                throw new IllegalArgumentException("Invalid store receivable move");
            }
            if (move.expectedVersion()==-1) {
                receivableChange=receivableChange.add(BigInteger.valueOf(move.originalMinor()));
            }
            receivableChange=receivableChange.subtract(BigInteger.valueOf(move.recoveredDelta()));
        }
        if (!receivableMoves.isEmpty() && !receivableChange.negate()
                .equals(funds.getOrDefault(FundBucket.B,BigInteger.ZERO))) {
            throw new IllegalArgumentException("Receivable change does not match actual refund buffer funding");
        }
        BigInteger external = BigInteger.ZERO;
        for (EvidenceRef evidence : evidenceRefs) {
            if (!currency.equals(evidence.currency()) || evidence.evidenceNo() == null
                    || evidence.evidenceNo().isBlank() || (evidence.purpose()==null)!=(evidence.sourceRef()==null)
                    || (evidence.purpose()!=null && (evidence.purpose().isBlank() || evidence.sourceRef().isBlank()))) {
                throw new IllegalArgumentException("Invalid money evidence currency or identity");
            }
            external = external.add(BigInteger.valueOf(evidence.externalMinor()));
        }
        if (!external.equals(cashChange)) {
            throw new IllegalArgumentException("Funds change lacks matching real money evidence");
        }
    }

    private boolean legal(HolderType holder, Bucket bucket) {
        if (holder == null || bucket == null) { return false; }
        return switch (holder) {
            case MINT -> bucket == Bucket.ISSUED;
            case ISSUE -> bucket == Bucket.ISSUABLE;
            case USER -> bucket == Bucket.PENDING || bucket == Bucket.AVAILABLE || bucket == Bucket.HELD;
            case STORE -> bucket == Bucket.PENDING || bucket == Bucket.AVAILABLE;
            case RECEIVE -> bucket == Bucket.REFUND_RESERVED || bucket == Bucket.RECIRCULATABLE;
        };
    }
}
