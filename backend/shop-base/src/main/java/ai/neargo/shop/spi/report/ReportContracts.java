package ai.neargo.shop.spi.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Immutable report facts; no payment command, SQL expression or public identity authorization. */
public final class ReportContracts {
    private ReportContracts() {}

    public enum Owner { CORE, FOOD, PAY, INVENTORY, EXPENSE, REPORT }
    public enum Coverage { COMPLETE, EMPTY_VERIFIED, PARTIAL, UNKNOWN }
    public enum ValueType { COUNT, QUANTITY, MONEY, DURATION }
    public enum Aggregation { SUM, LAST, DISTINCT, DERIVED }
    public enum Attribution { ORIGINAL_ORDER, OCCURRENCE, SCHEDULED_SERVICE }
    public enum DayKind { FROZEN_BUSINESS_DAY, POLICY_INTERVAL }
    public enum BuildStatus { QUEUED, RUNNING, VALIDATING, PUBLISHED, FAILED, CANCELLED }

    public static String identifier(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("REPORT_INVALID_IDENTIFIER");
        }
        return value;
    }

    public record Scope(String tenantNo, String entityNo, String storeNo) {
        public Scope {
            identifier(tenantNo, 32); identifier(entityNo, 64); identifier(storeNo, 64);
        }
    }

    public record Partition(Scope scope, LocalDate businessDate, int metricVersion) {
        public Partition {
            Objects.requireNonNull(scope); Objects.requireNonNull(businessDate);
            if (metricVersion < 1) throw new IllegalArgumentException("REPORT_INVALID_VERSION");
        }
    }

    public record DayEvidence(String dayNo, String policyNo, long policyVersion, String timezone,
            int cutoverMinute, Instant startsAt, Instant endsAt, DayKind kind) {
        public DayEvidence(String dayNo,String policyNo,long policyVersion,String timezone,int cutoverMinute,Instant startsAt,Instant endsAt) {
            this(dayNo,policyNo,policyVersion,timezone,cutoverMinute,startsAt,endsAt,DayKind.FROZEN_BUSINESS_DAY);
        }
        public DayEvidence {
            identifier(dayNo, 64); identifier(policyNo, 64); identifier(timezone, 64);
            Objects.requireNonNull(startsAt); Objects.requireNonNull(endsAt);
            if (policyVersion < 0 || cutoverMinute < 0 || cutoverMinute >= 1440
                    || !startsAt.isBefore(endsAt)) throw new IllegalArgumentException("REPORT_INVALID_DAY");
            ZoneId.of(timezone);
            if(kind==null) kind=DayKind.FROZEN_BUSINESS_DAY;
        }
        /** A later operational row can confirm a previously derived interval; it cannot change its boundaries. */
        public boolean accepts(DayEvidence actual) {
            return equals(actual) || kind==DayKind.POLICY_INTERVAL && actual.kind==DayKind.FROZEN_BUSINESS_DAY
                    && policyNo.equals(actual.policyNo) && policyVersion==actual.policyVersion && timezone.equals(actual.timezone)
                    && cutoverMinute==actual.cutoverMinute && startsAt.equals(actual.startsAt) && endsAt.equals(actual.endsAt);
        }
    }

    public record SourceKey(Owner owner, String family) {
        public SourceKey { Objects.requireNonNull(owner); identifier(family, 48); }
    }

    public record SourceQuery(Partition partition, DayEvidence day, String actorNo,
            List<String> sourceRefs, int rowLimit) {
        public SourceQuery {
            Objects.requireNonNull(partition); Objects.requireNonNull(day); identifier(actorNo, 64);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
            sourceRefs.forEach(ref -> identifier(ref,128));
            if (rowLimit < 1 || rowLimit > 100000 || sourceRefs.size() > rowLimit)
                throw new IllegalArgumentException("REPORT_INVALID_LIMIT");
        }
    }

    public record MetricValue(ValueType type, Long integerValue, BigDecimal quantityValue,
            Long amountMinor, String currency, String unit, Coverage coverage, String unknownReason,
            Long knownInteger, BigDecimal knownQuantity, Long knownAmount) {
        public MetricValue {
            Objects.requireNonNull(type); Objects.requireNonNull(coverage);
            boolean known = coverage == Coverage.COMPLETE || coverage == Coverage.EMPTY_VERIFIED;
            int fields = (integerValue == null ? 0 : 1) + (quantityValue == null ? 0 : 1)
                    + (amountMinor == null ? 0 : 1);
            if ((known && fields != 1) || (!known && fields != 0)) invalidValue();
            if (!known) identifier(unknownReason, 64);
            if (known && unknownReason != null) invalidValue();
            if (known && (knownInteger != null || knownQuantity != null || knownAmount != null)) invalidValue();
            if (type == ValueType.MONEY) {
                if ((known || knownAmount != null) && (currency == null || !currency.matches("[A-Z]{3}"))) invalidValue();
                if (known && amountMinor == null) invalidValue();
                if (integerValue != null || quantityValue != null || knownInteger != null || knownQuantity != null) invalidValue();
            } else if (type == ValueType.QUANTITY) {
                if ((known || knownQuantity != null) && (unit == null || unit.isBlank())) invalidValue();
                if (known && quantityValue == null) invalidValue();
                if (integerValue != null || amountMinor != null || knownInteger != null || knownAmount != null) invalidValue();
            } else {
                if (known && integerValue == null) invalidValue();
                if (quantityValue != null || amountMinor != null || knownQuantity != null || knownAmount != null) invalidValue();
            }
            quantityValue = exactQuantity(quantityValue); knownQuantity = exactQuantity(knownQuantity);
        }
        private static void invalidValue() { throw new IllegalArgumentException("REPORT_INVALID_VALUE"); }
        private static BigDecimal exactQuantity(BigDecimal q) {
            if (q == null) return null;
            BigDecimal exact = q.setScale(6, RoundingMode.UNNECESSARY);
            if (exact.precision() > 24) invalidValue();
            return exact;
        }
        public static MetricValue count(long n) {
            return new MetricValue(ValueType.COUNT, n, null, null, null, null,
                    Coverage.COMPLETE, null, null, null, null);
        }
        public static MetricValue money(long n, String c) {
            return new MetricValue(ValueType.MONEY, null, null, n, c, null,
                    Coverage.COMPLETE, null, null, null, null);
        }
        public static MetricValue quantity(BigDecimal n, String u) {
            return new MetricValue(ValueType.QUANTITY, null, n, null, null, u,
                    Coverage.COMPLETE, null, null, null, null);
        }
    }

    public record MetricFact(String metricCode, String aggregationSet, Attribution attribution,
            Map<String, String> dimensions, Map<String, String> labels, MetricValue value) {
        public MetricFact {
            identifier(metricCode, 64); identifier(aggregationSet, 48);
            Objects.requireNonNull(attribution); Objects.requireNonNull(value);
            dimensions = Map.copyOf(new TreeMap<>(Objects.requireNonNull(dimensions)));
            labels = labels == null ? Map.of() : Map.copyOf(labels);
            if (dimensions.size() > 20 || labels.size() > 12) throw new IllegalArgumentException("REPORT_DIMENSION_LIMIT");
            dimensions.forEach((k,v) -> { identifier(k, 48); identifier(v, 128); });
            labels.forEach((k,v) -> { identifier(k, 48); identifier(v, 255); });
        }
    }

    public record SourceSnapshot(Partition partition, SourceKey source, String snapshotRef,
            String sourceVersion, Instant asOfAt, long rowCount, String checksum,
            Coverage coverage, String unknownReason, List<MetricFact> facts, List<String> sourceRefs) {
        public SourceSnapshot {
            Objects.requireNonNull(partition); Objects.requireNonNull(source); Objects.requireNonNull(asOfAt);
            Objects.requireNonNull(coverage); asOfAt = asOfAt.truncatedTo(ChronoUnit.MICROS); facts = List.copyOf(facts); sourceRefs = List.copyOf(sourceRefs);
            if (rowCount < 0) throw new IllegalArgumentException("REPORT_INVALID_ROW_COUNT");
            boolean complete = coverage == Coverage.COMPLETE || coverage == Coverage.EMPTY_VERIFIED;
            if (complete) {
                identifier(snapshotRef, 128); identifier(sourceVersion, 128);
                if (checksum == null || !checksum.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("REPORT_INVALID_CHECKSUM");
                if (coverage == Coverage.EMPTY_VERIFIED && rowCount != 0)
                    throw new IllegalArgumentException("REPORT_EMPTY_PROOF_CONFLICT");
                if (unknownReason != null) throw new IllegalArgumentException("REPORT_INVALID_COVERAGE");
            } else identifier(unknownReason, 64);
        }
    }

    public record BuildReq(Partition partition, DayEvidence day, String actorNo,
            String requestKey, String specHash, Set<SourceKey> requiredSources,
            long dirtyGeneration, String reason, String jobRunId) {
        public BuildReq {
            Objects.requireNonNull(partition); Objects.requireNonNull(day); identifier(actorNo, 64);
            identifier(requestKey, 128); identifier(reason, 24);
            if (specHash == null || !specHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("REPORT_INVALID_SPEC_HASH");
            requiredSources = Set.copyOf(requiredSources);
            if (requiredSources.isEmpty() || dirtyGeneration < 0)
                throw new IllegalArgumentException("REPORT_REQUIRED_SOURCE_MISSING");
        }
    }

    public record BuildLease(Partition partition, String buildNo, long attemptNo, long fence,
            long expectedGeneration, String leaseOwner, Instant leaseUntil, String requestHash,
            boolean alreadyPublished) {}

    public record Publication(Partition partition, String buildNo, long attemptNo, long generation,
            Instant publishedAt, String specHash) {}

    public record BuildProblem(Partition partition,String buildNo,BuildStatus status,String errorCode,
            Instant retryAfter,Instant leaseUntil) {}

    public record PublishedDay(Publication publication, DayEvidence day, List<MetricFact> facts,
            List<SourceSnapshot> sources) {
        public PublishedDay { facts = List.copyOf(facts); sources = List.copyOf(sources); }
    }
}
