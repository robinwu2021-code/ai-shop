package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.AssignServiceTaskReq;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Separate DIRECT schema; old appointment snapshots and plan/hash domains remain immutable. */
public record DirectTaskPlan(
        String schema,
        ExecutionScope scope,
        String workOrderNo,
        String taskNo,
        String planNo,
        long planRevision,
        long publishedTaskRevision,
        long scopeRevision,
        String bindingNo,
        long bindingRevision,
        long bindingVersion,
        String sourceNo,
        String sourceLineNo,
        long sourceRevision,
        String sourceHash,
        String taskSnapshotHash,
        WorkOrderTaskSnapshotVO definition,
        Instant startAt,
        Instant endAt,
        List<AssignServiceTaskReq.Selection> assignments,
        String taskPlan,
        String taskPlanHash) {
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    public DirectTaskPlan {
        if (!"BEAUTY_DIRECT_PLAN_V1".equals(schema)
                || scope == null
                || planRevision < 1
                || publishedTaskRevision < 1
                || scopeRevision < 1
                || bindingRevision < 1
                || bindingVersion < 0
                || sourceRevision < 1
                || definition == null
                || startAt == null
                || endAt == null
                || !endAt.equals(startAt.plusSeconds(definition.durationMin() * 60L)))
            throw invalid();
        for (String ref : List.of(workOrderNo, taskNo, planNo, bindingNo, sourceNo, sourceLineNo)) {
            if (ref.isBlank() || ref.length() > 64 || !ref.equals(ref.strip())) throw invalid();
        }
        hashValue(sourceHash);
        hashValue(taskSnapshotHash);
        hashValue(taskPlanHash);
        assignments = List.copyOf(assignments);
        String expected = resourcePlan(taskNo, definition, startAt, assignments);
        if (!expected.equals(taskPlan) || !ResourcePlanContract.hash(expected).equals(taskPlanHash))
            throw invalid();
    }

    /**
     * Server reconstruction prevents a caller from asserting roles, demand quantities or
     * qualification exemptions.
     */
    public static String resourcePlan(
            String task,
            WorkOrderTaskSnapshotVO definition,
            Instant start,
            List<AssignServiceTaskReq.Selection> selections) {
        if (task == null
                || task.isBlank()
                || definition == null
                || definition.schemaVersion() != 1
                || start == null
                || start.getNano() % 1_000_000 != 0
                || selections == null
                || selections.isEmpty()
                || selections.size() > 100
                || definition.requirements().isEmpty()) throw invalid();
        List<ResourcePlanContract.Demand> demands = new ArrayList<>();
        List<ResourcePlanContract.Qualification> qualifications = new ArrayList<>();
        var seen = new HashSet<String>();
        for (var rule : definition.requirements()) {
            if (rule.qty() < 1 || rule.qty() > 100 || !seen.add(rule.requirementNo()))
                throw invalid();
            var matching =
                    selections.stream()
                            .filter(a -> rule.requirementNo().equals(a.requirementNo()))
                            .sorted(
                                    Comparator.comparingInt(
                                            AssignServiceTaskReq.Selection::ordinal))
                            .toList();
            if (matching.size() != rule.qty()) throw invalid();
            for (int ordinal = 0; ordinal < matching.size(); ordinal++) {
                var choice = matching.get(ordinal);
                if (choice.ordinal() != ordinal
                        || ("STAFF".equals(rule.resourceType())) != (choice.personnelNo() != null))
                    throw invalid();
                String group = task + ":" + rule.requirementNo() + ":" + ordinal;
                demands.add(
                        new ResourcePlanContract.Demand(
                                choice.resourceNo(),
                                group,
                                1,
                                List.of(
                                        new ResourcePlanContract.Interval(
                                                start.plusSeconds(
                                                        (rule.offsetMin()
                                                                        - definition
                                                                                .bufferBeforeMin())
                                                                * 60L),
                                                start.plusSeconds(
                                                        (rule.offsetMin()
                                                                        + rule.durationMin()
                                                                        + definition
                                                                                .bufferAfterMin())
                                                                * 60L)))));
                qualifications.add(
                        new ResourcePlanContract.Qualification(
                                choice.resourceNo(),
                                group,
                                choice.personnelNo() == null ? null : rule.skillGoodsNo(),
                                choice.personnelNo() == null ? null : definition.skuNo(),
                                choice.personnelNo() != null));
            }
        }
        if (demands.size() != selections.size()) throw invalid();
        return ResourcePlanContract.encode(demands, qualifications);
    }

    /**
     * Canonical equality also rejects missing/null/default fields and coercion inside nested
     * records.
     */
    public static DirectTaskPlan decode(String bytes) {
        if (bytes == null || bytes.length() > 65536) throw invalid();
        var root = JSON.readTree(bytes);
        var plan = JSON.readValue(bytes, DirectTaskPlan.class);
        if (!root.equals(JSON.readTree(ServiceExecutionContract.canonical(plan)))) throw invalid();
        return plan;
    }

    public String hash() {
        return IdempotencyScope.requestHash("BEAUTY_DIRECT_PLAN_V1", canonical());
    }

    public String canonical() {
        return ServiceExecutionContract.canonical(this);
    }

    private static void hashValue(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid DIRECT plan");
    }

    /**
     * Strict DIRECT START parser binds the copied exact plan to the genuine execution/source
     * original.
     */
    public static void validateStart(
            String bytes, ServiceTaskExecutionServiceImpl.StartCommand command) {
        if (bytes == null
                || bytes.length() > 262144
                || !JSON.readTree(bytes)
                        .equals(JSON.readTree(ServiceExecutionContract.canonical(command)))
                || !"START_DIRECT_V2".equals(command.schema())) throw invalid();
        hashValue(command.requestHash());
        hashValue(command.requestKeyHash());
        hashValue(command.bindingHash());
        var envelope = ExecutionPlan.decode(command.intent().frozenPlan());
        var plan = DirectTaskPlan.decode(envelope.directPlan());
        var binding = command.intent().binding();
        String hash =
                IdempotencyScope.requestHash(
                        "BEAUTY_DIRECT_EXECUTION_PLAN_V2", command.intent().frozenPlan());
        if (!hash.equals(command.intent().frozenPlanHash())
                || !hash.equals(binding.planHash())
                || !plan.scope().equals(binding.scope())
                || !plan.workOrderNo().equals(binding.workOrderNo())
                || !plan.taskNo().equals(binding.taskNo())
                || plan.scopeRevision() != binding.scopeRevision()
                || plan.publishedTaskRevision() > binding.taskRevision()
                || !plan.bindingNo().equals(binding.bindingNo())
                || plan.bindingRevision() != binding.bindingRevision()
                || !plan.sourceNo().equals(binding.subOrderNo())
                || !plan.sourceLineNo().equals(binding.sourceLineNo())
                || plan.sourceRevision() != binding.sourceRevision()
                || !plan.sourceHash().equals(binding.sourceHash())
                || !plan.taskSnapshotHash().equals(binding.executionSnapshotHash())
                || !plan.definition().definitionVersionNo().equals(binding.definitionVersionNo())
                || !command.intent()
                        .resourceCommandHash()
                        .equals(
                                IdempotencyScope.requestHash(
                                        "BEAUTY_DIRECT_EXECUTION_RESOURCE_V2",
                                        binding.executionNo(),
                                        command.intent().resourceCommandNo(),
                                        hash,
                                        binding.bindingNo(),
                                        Integer.toString(binding.unitOrdinal())))) throw invalid();
    }

    /** Immutable submission wrapper uses its own discriminator, never the old START record. */
    public record Command(
            String schema,
            String requestHash,
            String requestKeyHash,
            String operatorNamespaceHash,
            DirectTaskPlan plan,
            String planHash) {
        public Command {
            if (!"DIRECT_PLAN_V1".equals(schema) || plan == null || !plan.hash().equals(planHash))
                throw invalid();
            hashValue(requestHash);
            hashValue(requestKeyHash);
            hashValue(operatorNamespaceHash);
        }

        public static Command decode(String bytes) {
            if (bytes == null || bytes.length() > 131072) throw invalid();
            var root = JSON.readTree(bytes);
            var result = JSON.readValue(bytes, Command.class);
            if (!root.equals(JSON.readTree(ServiceExecutionContract.canonical(result))))
                throw invalid();
            DirectTaskPlan.decode(ServiceExecutionContract.canonical(result.plan()));
            return result;
        }
    }

    /**
     * Frozen START captures exact plan identity/bytes, independently of later generic task
     * versions.
     */
    public record ExecutionPlan(
            String schema,
            String directPlanNo,
            long directPlanRevision,
            String directPlanHash,
            String directPlan,
            String taskSnapshotHash,
            String taskPlan,
            String taskPlanHash) {
        public ExecutionPlan {
            var plan = DirectTaskPlan.decode(directPlan);
            if (!"BEAUTY_DIRECT_EXECUTION_PLAN_V2".equals(schema)
                    || !plan.planNo().equals(directPlanNo)
                    || plan.planRevision() != directPlanRevision
                    || !plan.hash().equals(directPlanHash)
                    || !plan.taskSnapshotHash().equals(taskSnapshotHash)
                    || !plan.taskPlan().equals(taskPlan)
                    || !plan.taskPlanHash().equals(taskPlanHash)) throw invalid();
        }

        public static ExecutionPlan from(DirectTaskPlan plan) {
            return new ExecutionPlan(
                    "BEAUTY_DIRECT_EXECUTION_PLAN_V2",
                    plan.planNo(),
                    plan.planRevision(),
                    plan.hash(),
                    plan.canonical(),
                    plan.taskSnapshotHash(),
                    plan.taskPlan(),
                    plan.taskPlanHash());
        }

        public static ExecutionPlan decode(String bytes) {
            var root = JSON.readTree(bytes);
            var result = JSON.readValue(bytes, ExecutionPlan.class);
            if (!root.equals(JSON.readTree(ServiceExecutionContract.canonical(result))))
                throw invalid();
            return result;
        }
    }
}
