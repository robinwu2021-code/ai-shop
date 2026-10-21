package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentVO;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.AppointmentPlanItemVO;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDetailQuery;
import ai.neargo.shop.beauty.entity.BtyServiceRequirement;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort.Scope;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded P1 allocation search. Facts are advisory; only the real owner may reserve the resulting bytes. */
public final class AppointmentMatching {
    private static final int SEARCH_BUDGET = 10_000;
    private static final int PREPARATION_BUCKET_BUDGET = 100_000;
    private final ServiceCatalogQueryService catalog;
    private final ObjectProvider<ServiceCatalogFactsPort> stores;
    private final ObjectProvider<ResourceCandidatesPort> candidates;
    private final ObjectProvider<PersonnelFactsPort> personnel;
    private final ObjectProvider<ScheduleWindowsPort> schedules;
    private final BeautyMappers.ServiceRequirementMapper requirements;

    public AppointmentMatching(ServiceCatalogQueryService catalog, ObjectProvider<ServiceCatalogFactsPort> stores,
            ObjectProvider<ResourceCandidatesPort> candidates, ObjectProvider<PersonnelFactsPort> personnel,
            ObjectProvider<ScheduleWindowsPort> schedules, BeautyMappers.ServiceRequirementMapper requirements) {
        this.catalog = catalog;
        this.stores = stores;
        this.candidates = candidates;
        this.personnel = personnel;
        this.schedules = schedules;
        this.requirements = requirements;
    }
    public Scope scope(String storeNo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("appointment matching requires no caller transaction");
        }
        var store = stores.getObject().store(storeNo);
        if (store == null || !storeNo.equals(store.storeNo()) || !"ACTIVE".equals(store.status())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return new Scope("MAIN", store.entityNo(), store.storeNo());
    }
    public Matched match(Scope scope, boolean publicOnly, List<AppointmentItemReq> requested,
            List<AppointmentAssignmentReq> specified, String requestedPerson, OffsetDateTime startAt) {
        return match(scope, publicOnly, requested, specified, requestedPerson, startAt, null);
    }
    public Matched match(Scope scope, boolean publicOnly, List<AppointmentItemReq> requested,
            List<AppointmentAssignmentReq> specified, String requestedPerson, OffsetDateTime startAt,
            ResourceCandidatesPort.Replacement replacement) {
        try { return matchComplete(scope, publicOnly, requested, specified, requestedPerson, startAt, replacement); }
        catch (IncompleteSearch incomplete) { throw BizException.of(ErrorCode.BEAUTY_SEARCH_INCOMPLETE); }
    }
    private Matched matchComplete(Scope scope, boolean publicOnly, List<AppointmentItemReq> requested,
            List<AppointmentAssignmentReq> specified, String requestedPerson, OffsetDateTime startAt,
            ResourceCandidatesPort.Replacement replacement) {
        if (requested == null || requested.isEmpty() || requested.size() > 10 || startAt == null
                || !startAt.toInstant().isAfter(Instant.now()) || startAt.getNano() != 0
                || Math.floorMod(startAt.toEpochSecond(), 300) != 0) { bad(); }
        specified = specified == null ? List.of() : List.copyOf(specified);
        if (specified.size() > 30) { bad(); }
        var store = catalog.getPublicStore(scope.storeNo());
        if (startAt.toInstant().isAfter(Instant.now().plus(store.policy().bookingAheadDays(), ChronoUnit.DAYS))) {
            bad();
        }
        var pool = candidates.getObject().candidates(new ResourceCandidatesPort.Query(scope, publicOnly,
                startAt.toInstant(), 50)).stream()
                .sorted(Comparator.comparing(ResourceFactsPort.ResourceFact::resourceNo)).toList();
        if (pool.isEmpty()) { throw BizException.of(ErrorCode.CONFLICT); }
        Set<String> ids = pool.stream().map(ResourceFactsPort.ResourceFact::resourceNo).collect(Collectors.toSet());
        Set<String> people = pool.stream().map(ResourceFactsPort.ResourceFact::personnelNo)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        var staff = people.isEmpty() ? List.<PersonnelFactsPort.PersonnelFact>of()
                : personnel.getObject().getBatch(new PersonnelFactsPort.Query(scope.tenantNo(), scope.entityNo(),
                        scope.storeNo(), people, startAt.toInstant()));
        // Small bounded horizon includes before buffers. Full raw spans still must fit a proven schedule window.
        Instant from = startAt.toInstant().minus(1, ChronoUnit.DAYS);
        Instant to = startAt.toInstant().plus(10, ChronoUnit.DAYS);
        var windows = schedules.getObject().getBatch(new ScheduleWindowsPort.Query(scope.tenantNo(), scope.entityNo(),
                scope.storeNo(), ids, from, to));
        var capacity = candidates.getObject().capacity(new ResourceCandidatesPort.CapacityQuery(
                scope, ids, from, to, startAt.toInstant(), replacement));
        List<AppointmentPlanItemVO> items = new ArrayList<>();
        List<AppointmentAssignmentVO> assignments = new ArrayList<>();
        List<ResourcePlanContract.Demand> demands = new ArrayList<>();
        List<ResourcePlanContract.Qualification> qualifications = new ArrayList<>();
        var bucketBudget = new BucketBudget();
        var occupied = occupied(pool, capacity, bucketBudget);
        List<Slot> slots = new ArrayList<>();
        Set<AppointmentAssignmentReq> consumed = new HashSet<>();
        Instant cursor = startAt.toInstant();
        Instant lastServiceEnd = cursor;
        long price = 0;
        int count = 0;
        for (int itemIndex = 0; itemIndex < requested.size(); itemIndex++) {
            var item = requested.get(itemIndex);
            if (item == null || item.qty() < 1 || item.qty() > 10 || (count += item.qty()) > 10) { bad(); }
            var product = catalog.getPublicService(item.skuNo(), new ServiceDetailQuery(scope.storeNo()));
            var observed = stores.getObject().service(scope.storeNo(), item.skuNo());
            AppointmentPersistence.require(observed.goodsRevision() != null && observed.skuRevision() != null
                    && observed.goodsRevision() >= 0 && observed.skuRevision() >= 0
                    && scope.entityNo().equals(observed.store().entityNo())
                    && scope.storeNo().equals(observed.store().storeNo()) && item.skuNo().equals(observed.skuNo())
                    && product.goodsNo().equals(observed.goodsNo()) && product.priceMinor() == observed.priceMinor()
                    && product.duration().minutes() == observed.durationMin());
            if (!product.sales().saleableNow()) { throw BizException.of(ErrorCode.CONFLICT); }
            var rules = DataScopeContext.executeWithoutScope(() -> requirements.selectList(
                    Wrappers.<BtyServiceRequirement>lambdaQuery().eq(BtyServiceRequirement::getTenantNo, "MAIN")
                            .eq(BtyServiceRequirement::getEntityNo, scope.entityNo())
                            .eq(BtyServiceRequirement::getDefinitionVersionNo, product.definitionVersionNo())
                            .orderByAsc(BtyServiceRequirement::getSequenceNo,
                                    BtyServiceRequirement::getRequirementNo)));
            if (rules.isEmpty() || rules.size() > 30) { bad(); }
            for (int repetition = 0; repetition < item.qty(); repetition++) {
                String line = BizKey.next("BTAI");
                Instant serviceStart = items.isEmpty() ? cursor
                        : ceil(cursor.plus(product.duration().bufferBeforeMin(), ChronoUnit.MINUTES));
                lastServiceEnd = serviceStart.plus(product.duration().minutes(), ChronoUnit.MINUTES);
                items.add(new AppointmentPlanItemVO(line, item.skuNo(), product.goodsNo(),
                        product.definitionVersionNo(), 1, product.priceMinor(), product.duration().minutes(),
                        serviceStart.atOffset(ZoneOffset.UTC), lastServiceEnd.atOffset(ZoneOffset.UTC),
                        observed.goodsRevision(), observed.skuRevision()));
                price = Math.addExact(price, product.priceMinor());
                for (var rule : rules) {
                    Instant a = serviceStart.plus(rule.getOffsetMin(), ChronoUnit.MINUTES)
                            .minus(product.duration().bufferBeforeMin(), ChronoUnit.MINUTES);
                    Instant b = serviceStart.plus(rule.getOffsetMin() + rule.getDurationMin(), ChronoUnit.MINUTES)
                            .plus(product.duration().bufferAfterMin(), ChronoUnit.MINUTES);
                    int index = itemIndex;
                    var wanted = specified.stream().filter(v -> v.itemIndex() == index
                            && rule.getRequirementNo().equals(v.requirementNo())).toList();
                    if (wanted.size() > rule.getQty()) { bad(); }
                    for (int n = 0; n < rule.getQty(); n++) {
                        if (slots.size() >= SEARCH_BUDGET) { throw new IncompleteSearch(); }
                        var explicit = n < wanted.size() ? wanted.get(n) : null;
                        if (explicit != null) { consumed.add(explicit); }
                        String person = explicit == null ? requestedPerson : explicit.personnelNo();
                        List<Choice> choices = new ArrayList<>();
                        for (var resource : pool) {
                            boolean isStaff = resource.resourceType() == ResourceFactsPort.Type.STAFF;
                            if (!resource.resourceType().name().equals(rule.getResourceType())
                                    || resource.openOccupied() || isStaff && resource.personnelNo() == null
                                    || explicit != null && explicit.resourceNo() != null
                                        && !explicit.resourceNo().equals(resource.resourceNo())
                                    || isStaff && person != null && !person.equals(resource.personnelNo())) {
                                continue;
                            }
                            if (isStaff && staff.stream().noneMatch(p ->
                                    p.personnelNo().equals(resource.personnelNo()) && (!publicOnly || p.publicVisible())
                                    && !p.validFrom().isAfter(a) && (p.validTo() == null || !p.validTo().isBefore(b))
                                    && p.qualifications().stream().anyMatch(q ->
                                        rule.getSkillGoodsNo().equals(q.goodsNo())
                                        && (q.skuNo() == null || item.skuNo().equals(q.skuNo()))
                                        && (q.storeNo() == null || scope.storeNo().equals(q.storeNo()))
                                        && !q.validFrom().isAfter(a) && (q.validTo() == null ||
                                                !q.validTo().isBefore(b))))) { continue; }
                            if (windows.stream().noneMatch(w -> resource.resourceNo().equals(w.resourceNo())
                                    && !w.startAt().isAfter(a) && !w.endAt().isBefore(b))) { continue; }
                            List<Bucket> buckets = new ArrayList<>();
                            for (long bucket = floor(a); bucket < b.getEpochSecond(); bucket += 300) {
                                bucketBudget.take();
                                String key = canonical(resource) + ":" + bucket;
                                buckets.add(new Bucket(key, (isStaff ? 1 : resource.capacity())
                                        - occupied.getOrDefault(key, 0)));
                            }
                            if (buckets.stream().allMatch(bucket -> bucket.remaining() > 0)) {
                                choices.add(new Choice(resource, buckets));
                            }
                        }
                        slots.add(new Slot(line, rule.getRequirementNo(), rule.getSkillGoodsNo(), item.skuNo(), n,
                                a, b, choices));
                    }
                }
                cursor = ceil(lastServiceEnd.plus(product.duration().bufferAfterMin(), ChronoUnit.MINUTES));
            }
        }
        if (consumed.size() != specified.size()) { bad(); }
        var selected = allocate(slots, pool.size() == 50);
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            var chosen = selected[i].resource();
            String group = slot.line() + ":" + slot.requirement() + ":" + slot.unit();
            demands.add(new ResourcePlanContract.Demand(chosen.resourceNo(), group, 1,
                    List.of(new ResourcePlanContract.Interval(slot.from(), slot.to()))));
            qualifications.add(new ResourcePlanContract.Qualification(chosen.resourceNo(), group,
                    chosen.personnelNo() == null ? null : slot.goods(),
                    chosen.personnelNo() == null ? null : slot.sku(), chosen.personnelNo() != null));
            assignments.add(new AppointmentAssignmentVO(slot.line(), slot.requirement(), chosen.personnelNo(),
                    chosen.resourceNo(), slot.from().atOffset(ZoneOffset.UTC), slot.to().atOffset(ZoneOffset.UTC)));
        }
        String plan = ResourcePlanContract.encode(demands, qualifications);
        return new Matched(scope, store.policyVersionNo(), startAt.withOffsetSameInstant(ZoneOffset.UTC),
                lastServiceEnd.atOffset(ZoneOffset.UTC), plan,
                new Snapshot(items, assignments, price, store.policy().cancelRule()), pool.size() < 50);
    }
    /** No IO here: undo every allocation on backtrack and distinguish exhausted work from proven infeasibility. */
    private Choice[] allocate(List<Slot> slots, boolean truncated) {
        Choice[] selected = new Choice[slots.size()];
        int[] next = new int[slots.size()];
        Map<String, Integer> used = new HashMap<>();
        int depth = 0;
        int attempts = 0;
        while (depth >= 0 && depth < slots.size()) {
            var choices = slots.get(depth).choices();
            if (next[depth] == choices.size()) {
                next[depth] = 0;
                if (--depth >= 0) {
                    for (var bucket : selected[depth].buckets()) {
                        used.compute(bucket.key(), (key, value) -> value == 1 ? null : value - 1);
                    }
                    selected[depth] = null;
                }
                continue;
            }
            if (++attempts > SEARCH_BUDGET) { throw new IncompleteSearch(); }
            var choice = choices.get(next[depth]++);
            if (choice.buckets().stream().anyMatch(bucket ->
                    used.getOrDefault(bucket.key(), 0) >= bucket.remaining())) { continue; }
            for (var bucket : choice.buckets()) { used.merge(bucket.key(), 1, Integer::sum); }
            selected[depth++] = choice;
        }
        if (depth < 0) {
            if (truncated) { throw new IncompleteSearch(); }
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return selected;
    }

    private Map<String, Integer> occupied(List<ResourceFactsPort.ResourceFact> pool,
            List<ResourceCandidatesPort.Capacity> capacities, BucketBudget budget) {
        Map<String, String> identities = pool.stream().collect(Collectors.toMap(
                ResourceFactsPort.ResourceFact::resourceNo, AppointmentMatching::canonical));
        Map<String, Integer> occupied = new HashMap<>();
        for (var capacity : capacities) {
            var identity = identities.get(capacity.resourceNo());
            if (identity == null) { continue; }
            for (long bucket = floor(capacity.from()); bucket < capacity.to().getEpochSecond(); bucket += 300) {
                budget.take();
                // Aliases report the same canonical STAFF usage, so merge conservatively without double counting.
                occupied.merge(identity + ":" + bucket, capacity.used(), Math::max);
            }
        }
        return occupied;
    }

    private static String canonical(ResourceFactsPort.ResourceFact resource) {
        return resource.resourceType() == ResourceFactsPort.Type.STAFF
                ? "STAFF:" + resource.personnelNo() : "RESOURCE:" + resource.resourceNo();
    }

    /** Bound materialization as well as search; large valid requests remain explicitly undecided. */
    private static final class BucketBudget {
        private int used;
        void take() {
            if (++used > PREPARATION_BUCKET_BUDGET) { throw new IncompleteSearch(); }
        }
    }

    private record Bucket(String key, int remaining) { }
    private record Choice(ResourceFactsPort.ResourceFact resource, List<Bucket> buckets) { }
    private record Slot(String line, String requirement, String goods, String sku, int unit, Instant from, Instant to,
            List<Choice> choices) { }
    private static long floor(Instant value) { return Math.floorDiv(value.getEpochSecond(), 300) * 300; }
    private static Instant ceil(Instant value) {
        return Instant.ofEpochSecond(Math.floorDiv(value.getEpochSecond() + 299, 300) * 300);
    }
    private void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    /** A bounded search cannot claim that unexamined candidates do not exist. */
    public static final class IncompleteSearch extends RuntimeException { }
    public record Snapshot(List<AppointmentPlanItemVO> items, List<AppointmentAssignmentVO> assignments,
            long priceMinor, PublishStorePolicyVersionReq.CancellationRule cancelRule) { }
    public record Matched(Scope scope, String policyVersionNo, OffsetDateTime startAt, OffsetDateTime endAt,
            String plan, Snapshot snapshot, boolean complete) { }
}
