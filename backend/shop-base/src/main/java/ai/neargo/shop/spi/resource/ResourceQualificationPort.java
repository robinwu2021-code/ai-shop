package ai.neargo.shop.spi.resource;

import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Pure current owner facts bind an exact query; they confer neither task authority nor capacity. */
public interface ResourceQualificationPort {
    Observation observe(Query query);

    enum Outcome { PROVEN, NOT_ELIGIBLE }

    /** Frozen industry policy is echoed exactly so the consumer can reject missing or weaker requirements. */
    record Query(String tenantNo, String entityNo, String storeNo, String requestHash, String frozenTaskPlanHash,
            List<ResourcePlanContract.Demand> demands, List<ResourcePlanContract.Qualification> qualifications) {
        public Query {
            var plan = new ResourcePlanContract.FrozenPlan(demands, qualifications);
            demands = plan.demands().stream().sorted(Comparator.comparing(ResourcePlanContract.Demand::resourceNo)
                    .thenComparing(ResourcePlanContract.Demand::sourceLineRef)).toList();
            qualifications = plan.qualifications().stream().sorted(
                    Comparator.comparing(ResourcePlanContract.Qualification::resourceNo)
                            .thenComparing(ResourcePlanContract.Qualification::sourceLineRef)
                            .thenComparing(q -> q.goodsNo() == null ? "" : q.goodsNo())
                            .thenComparing(q -> q.skuNo() == null ? "" : q.skuNo())).toList();
        }
    }

    record CoveredBucket(Instant startAt, Instant endAt, int qty) {}
    record SkillWitness(String skillNo, long version, String storeNo, String goodsNo, String skuNo,
            Instant validFrom, Instant validTo) {}
    record ScheduleWitness(String ruleNo, long version, String assignmentNo, Instant coveredStart,
            Instant coveredEnd) {}
    record EmploymentWitness(String assignmentNo, long version, Instant validFrom, Instant validTo) {}
    record QualifiedGroup(String resourceNo, String sourceLineRef, String personnelNo, long personnelRevision,
            long resourceRevision, List<CoveredBucket> buckets, List<SkillWitness> skills,
            List<ScheduleWitness> schedules, EmploymentWitness employment) {
        public QualifiedGroup {
            buckets = List.copyOf(buckets);
            skills = List.copyOf(skills);
            schedules = List.copyOf(schedules);
        }
    }
    record Observation(String schema, Outcome outcome, Query query, String queryHash,
            OperatorPersonnelFact currentPersonnel, List<QualifiedGroup> groups, Instant observedAt,
            Instant validUntil, String reason, String observationHash) {
        public Observation { groups = List.copyOf(groups); }
    }

    /** Canonical hashes bind facts and bytes only; no signature or durable permission is implied. */
    static String hash(Object value) {
        return IdempotencyScope.requestHash("RESOURCE_QUALIFICATION_V1",
                JsonMapper.builder().build().writeValueAsString(value));
    }

    static Observation observation(Outcome outcome, Query query, OperatorPersonnelFact personnel,
            List<QualifiedGroup> groups, Instant at, Instant until, String reason) {
        var unsigned = new Observation("RESOURCE_QUALIFICATION_OBSERVATION_V1", outcome, query, hash(query),
                personnel, groups, at, until, reason, null);
        return new Observation(unsigned.schema(), outcome, query, unsigned.queryHash(), personnel, groups,
                at, until, reason, hash(unsigned));
    }

    static boolean intact(Observation observation) {
        return observation != null && observation.equals(observation(observation.outcome(), observation.query(),
                observation.currentPersonnel(), observation.groups(), observation.observedAt(),
                observation.validUntil(), observation.reason()));
    }
}
