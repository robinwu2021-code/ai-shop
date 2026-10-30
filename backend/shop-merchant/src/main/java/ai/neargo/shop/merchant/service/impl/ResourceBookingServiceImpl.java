package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.event.outbox.runtime.PlatformPublicationPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.merchant.dto.ResourceBookingAllocationRow;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchBookingHold;
import ai.neargo.shop.merchant.entity.MchBookingHoldUnit;
import ai.neargo.shop.merchant.entity.MchResource;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldUnitMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceMapper;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Plan;
import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceFactsPort.Type;
import ai.neargo.shop.merchant.policy.ResourcePlanCodec;
import ai.neargo.shop.merchant.policy.ResourcePlanCodec.FrozenPlan;
import ai.neargo.shop.merchant.service.ResourceAdmissionService;
import ai.neargo.shop.merchant.service.ResourceBookingService;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.merchant.service.ResourceCommandService.Action;
import ai.neargo.shop.merchant.service.ResourceCommandService.Execution;
import ai.neargo.shop.merchant.service.ResourceCommandService.Receipt;
import ai.neargo.shop.merchant.service.ResourceCommandService.Scope;
import ai.neargo.shop.merchant.service.ResourceCommandService.Status;
import ai.neargo.shop.merchant.service.ResourceSourceAuthorizationService;
import ai.neargo.shop.merchant.service.ResourceSourceAuthorizationService.Authorization;
import ai.neargo.shop.spi.resource.ResourceBookingChanged;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.State;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Serializes the complete old/new resource union and commits capacity, history, head, result and outbox together. */
@Service
public class ResourceBookingServiceImpl implements ResourceBookingService {
    private final ResourceOccupationGuard occupations;
    private final ResourceCommandService commands;
    private final ResourceSourceAuthorizationService sources;
    private final ResourceAdmissionService admission;
    private final ResourceMapper resources;
    private final PersonnelMapper personnel;
    private final ResourceBucketMapper buckets;
    private final ResourceCommandMapper journal;
    private final BookingHoldMapper holds;
    private final BookingHoldUnitMapper units;
    private final TransactionTemplate writes;
    private final Clock clock;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;

    public ResourceBookingServiceImpl(ResourceOccupationGuard occupations, ResourceCommandService commands, ResourceSourceAuthorizationService sources,
            ResourceAdmissionService admission, ResourceMapper resources, PersonnelMapper personnel,
            ResourceBucketMapper buckets, ResourceCommandMapper journal, BookingHoldMapper holds,
            BookingHoldUnitMapper units,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Qualifier("dataSource") DataSource dataSource, ObjectProvider<Clock> clocks, ObjectMapper json,
            ObjectProvider<UnifiedOutboxFacade> facades, ObjectProvider<OutboxConsumer> consumers,
            @Value("${shop.resource.publication.archive-only:false}") boolean archiveOnly,
            @Value("${shop.outbox.maintenance:false}") boolean maintenance) {
        this.occupations = occupations;
        this.commands = commands;
        this.sources = sources;
        this.admission = admission;
        this.resources = resources;
        this.personnel = personnel;
        this.buckets = buckets;
        this.journal = journal;
        this.holds = holds;
        this.units = units;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
        this.json = json;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        publisher = PlatformPublicationPublisher.create(facades::getIfAvailable, manager, () -> dataSource, json,
                consumers::orderedStream, Set.of("RESOURCE_BOOKING_CHANGED"), archiveOnly, maintenance);
    }

    @Override
    public Receipt execute(Scope scope, String commandNo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("booking execution requires no caller transaction");
        }
        Receipt original = commands.find(scope, commandNo)
                .orElseThrow(() -> new IllegalStateException("original resource command unavailable"));
        if (original.status() != Status.ACCEPTED) {
            return original;
        }
        try {
            var resolution = sources.authorize(scope, original.commandNo());
            if (resolution.decision().state() == State.DENIED
                    || resolution.decision().state() == State.AUTHORIZED
                        && !clock.instant().isBefore(resolution.decision().validUntil())) {
                // A durable fence may outlive its permission. Query the original source truth, then let
                // the journal lock wait for every in-flight executor before proving no committed effects.
                var recovered = sources.recover(scope, original.commandNo()).decision();
                String code = recovered.state() == State.DENIED ? "SOURCE_DENIED"
                        : recovered.state() == State.AUTHORIZED
                            && !clock.instant().isBefore(recovered.validUntil()) ? "SOURCE_PERMISSION_EXPIRED" : null;
                if (code != null) {
                    return commands.rejectAfterRollback(scope, original.commandNo(), original.version(), code,
                            json.writeValueAsString(Map.of("code", code)));
                }
                return commands.find(scope, original.commandNo()).orElseThrow();
            }
            if (resolution.authorization() == null) {
                return commands.find(scope, original.commandNo()).orElseThrow();
            }
            return DataScopeContext.executeWithoutScope(() -> writes.execute(status ->
                    mutate(original, resolution.authorization())));
        } catch (BookingRejected rejected) {
            // Only our typed pre-commit rejection reaches here unchanged after TransactionTemplate rollback.
            // A rollback failure replaces it with the transaction exception and must stay recoverable.
            return commands.rejectAfterRollback(scope, original.commandNo(), original.version(), rejected.code,
                    json.writeValueAsString(Map.of("code", rejected.code)));
        } catch (RuntimeException uncertain) {
            // Concurrent original-number execution or a lost commit response may already have a final result.
            // Never invent REJECTED for storage/commit/authorization errors; the accepted number remains recoverable.
            var observed = commands.find(scope, original.commandNo()).orElseThrow();
            if (observed.status() != Status.ACCEPTED) {
                return observed;
            }
            throw uncertain;
        }
    }

    private Receipt mutate(Receipt original, Authorization authorization) {
        Execution execution = commands.lockForExecution(original.scope(), original.commandNo(), original.version());
        FrozenPlan frozen = sources.requireAuthorized(execution, authorization);
        var scope = new ResourceCommandScopeRow(original.scope().tenantNo(), original.scope().entityNo(),
                original.scope().storeNo());
        MchBookingHold old = previous(scope, execution);
        List<ResourceBookingAllocationRow> allocations = old == null ? List.of()
                : units.selectBookingAllocations(scope, old.getHoldNo());
        if (old != null && (allocations.isEmpty() || allocations.size() > 10000)) {
            throw new IllegalStateException("active hold allocation evidence unavailable");
        }
        for (var row : allocations) {
            if (row.resourceNo() == null || row.deleted() != 0) {
                throw new IllegalStateException("active unit parent evidence unavailable");
            }
        }
        FrozenPlan oldPlan = null;
        if (old != null) {
            var creation = journal.selectCommand(scope, old.getCommandNo());
            if (creation == null || !old.getCurrentPlanHash().equals(
                    ResourcePlanCodec.hash(creation.getFrozenPayload()))) {
                throw new IllegalStateException("current hold creation plan unavailable");
            }
            oldPlan = ResourcePlanCodec.decode(creation.getFrozenPayload());
        }
        Map<String, MchResource> roots = lockRoots(scope, frozen, oldPlan, allocations);
        // An orphan has no trustworthy resource/time identity. Under READ_COMMITTED, inspect committed
        // entity evidence without taking unrelated roots or mistaking another transaction's partial writes for truth.
        if (units.hasBookingOrphans(scope)) {
            throw new IllegalStateException("entity has unreleased orphan allocation evidence");
        }
        if (oldPlan != null) { verifyAllocations(scope, oldPlan, allocations, roots); }
        if (old != null) {
            old = holds.lockBookingHold(scope, original.ownerNo(), old.getHoldNo());
            target(execution, old);
        }
        boolean nativeAvailable = occupations.inspect(scope);
        if (allocations.stream().anyMatch(a -> a.consumeOccupationUnitNo() != null
                || a.consumedAt() != null || a.consumeCommandNo() != null)) {
            reject("HOLD_CONSUMED");
        }
        LocalDateTime now = now();
        validateAction(original, old, frozen, now);
        Action action = original.action();
        boolean creating = action == Action.RESERVE || action == Action.REPLACE;
        boolean retiring = action == Action.RELEASE || action == Action.EXPIRE || action == Action.REPLACE;
        boolean confirming = action == Action.CONFIRM || action == Action.RECONCILE;
        Plan plan = null;
        if (creating || confirming || action == Action.RENEW || action == Action.PROTECT) {
            plan = admission.lockAndValidate(execution, frozen.demands(), frozen.qualifications());
        }
        MchBookingHold next = creating ? newHold(scope, original, old, now) : old;
        var changes = new TreeMap<BucketKey, Delta>(Comparator.comparing(BucketKey::resource)
                .thenComparing(BucketKey::start));
        for (var allocation : allocations) {
            // Lease-only actions also validate every old bucket and all its active parent/count evidence.
            Delta delta = changes.computeIfAbsent(new BucketKey(allocation.resourceNo(), allocation.startAt()),
                    ignored -> new Delta());
            if (retiring || confirming) {
                if ("HELD".equals(old.getStatus())) { delta.held -= allocation.qty(); }
                else { delta.confirmed -= allocation.qty(); }
                if (confirming) { delta.confirmed += allocation.qty(); }
            }
        }
        if (creating) {
            for (var demand : plan.buckets()) {
                Delta delta = changes.computeIfAbsent(new BucketKey(demand.resourceNo(), local(demand.startAt())),
                        ignored -> new Delta());
                if ("HELD".equals(next.getStatus())) { delta.held += demand.quantity(); }
                else { delta.confirmed += demand.quantity(); }
            }
        }
        Map<BucketKey, MchResourceBucket> locked = new HashMap<>();
        // Validate all touched buckets, including zero deltas, before writing the first count.
        // Root locks also serialize missing buckets; the entity orphan guard prevents identity reconstruction.
        for (var entry : changes.entrySet()) {
            var key = entry.getKey();
            var bucket = buckets.lockBookingBucket(scope, key.resource(), key.start());
            if (bucket == null) {
                if (!creating || allocations.stream().anyMatch(a -> a.resourceNo().equals(key.resource())
                        && a.startAt().equals(key.start()))) {
                    throw new IllegalStateException("active allocation bucket missing");
                }
                bucket = initialize(scope, roots.get(key.resource()), key.start());
            }
            var evidence = buckets.selectBookingCounters(scope, bucket.getBucketNo());
            if (evidence.invalid() != 0 || evidence.held() != bucket.getHeldQty()
                    || evidence.confirmed() != bucket.getConfirmedQty()) {
                throw new IllegalStateException("bucket counters disagree with active allocation evidence");
            }
            var delta = entry.getValue();
            long held = bucket.getHeldQty() + delta.held;
            long confirmed = bucket.getConfirmedQty() + delta.confirmed;
            if (held < 0 || confirmed < 0) {
                throw new IllegalStateException("capacity counter evidence inconsistent");
            }
            if (occupations.used(scope, key.resource(), key.start(), bucket, held, confirmed, nativeAvailable)
                    > bucket.getCapacity()) { reject("CAPACITY_UNAVAILABLE"); }
            if (!Objects.equals(bucket.getCapacity(), roots.get(key.resource()).getCapacity())) {
                throw new IllegalStateException("bucket capacity differs from authoritative resource");
            }
            locked.put(key, bucket);
        }
        for (var entry : changes.entrySet()) {
            if (entry.getValue().held == 0 && entry.getValue().confirmed == 0) { continue; }
            var bucket = locked.get(entry.getKey());
            one(buckets.adjustBookingCapacity(scope, bucket.getBucketNo(), bucket.getVersion(),
                    entry.getValue().held, entry.getValue().confirmed, actor(), now));
        }
        if (retiring) {
            for (var allocation : allocations) {
                one(units.releaseBookingUnit(scope, old.getHoldNo(), allocation.unitNo(), allocation.version(),
                        original.commandNo(), actor(), now));
            }
            old.setStatus(action == Action.EXPIRE ? "EXPIRED" : "RELEASED");
            old.setTerminationReason(action.name());
            old.setTerminatedAt(now);
            if (creating) { old.setReplacedByHoldNo(next.getHoldNo()); }
            one(holds.saveBookingHold(scope, old, actor(), now));
        }
        if (creating) {
            one(holds.insert(next));
            for (var unit : plan.units()) {
                var row = new MchBookingHoldUnit();
                row.setTenantNo(scope.tenantNo());
                row.setEntityNo(scope.entityNo());
                row.setUnitNo(BizKey.next("RBU"));
                row.setHoldNo(next.getHoldNo());
                row.setBucketNo(locked.get(new BucketKey(unit.resourceNo(), local(unit.startAt()))).getBucketNo());
                row.setAllocationRevision(next.getPlanRevision());
                row.setQty(unit.quantity());
                row.setSourceLineRef(unit.sourceLineRef());
                one(units.insert(row));
            }
        } else if (!retiring) {
            next.setSourceRevision(original.sourceRevision());
            if (confirming) { next.setStatus("CONFIRMED"); }
            if (action == Action.RENEW || action == Action.PROTECT) {
                if (next.getLeaseRevision() == Long.MAX_VALUE) { reject("LEASE_REVISION_EXHAUSTED"); }
                next.setLeaseRevision(next.getLeaseRevision() + 1);
                next.setExpiresAt(now.plusMinutes(15));
                next.setExpirationPolicy("SOURCE_COORDINATED");
            }
            one(holds.saveBookingHold(scope, next, actor(), now));
        }
        String head = action == Action.RELEASE || action == Action.EXPIRE ? null : next.getHoldNo();
        String result = json.writeValueAsString(Map.of("holdNo", next.getHoldNo(), "status", next.getStatus(),
                "ownerRevision", execution.owner().ownerVersion() + 1));
        publisher.publish(new ResourceBookingChanged(original.ownerNo(), scope.entityNo(), scope.storeNo(),
                original.commandNo(), next.getHoldNo(), action.name(), execution.owner().ownerVersion() + 1),
                new PublishOptions("RESOURCE_BOOKING:" + original.ownerNo(), 1));
        return commands.completeSuccess(execution, head, result);
    }

    private MchBookingHold previous(ResourceCommandScopeRow scope, Execution execution) {
        String head = execution.owner().currentHoldNo();
        if (head == null) {
            if (execution.command().action() != Action.RESERVE || execution.command().target() != null) {
                reject("CURRENT_HOLD_REQUIRED");
            }
            return null;
        }
        if (execution.command().action() == Action.RESERVE) { reject("CURRENT_HOLD_EXISTS"); }
        var old = holds.selectOwnedHold(scope, execution.command().ownerNo(), head);
        target(execution, old);
        return old;
    }

    private static void target(Execution execution, MchBookingHold hold) {
        var target = execution.command().target();
        if (hold == null || target == null || !hold.getHoldNo().equals(target.holdNo())
                || hold.getVersion() != target.holdVersion() || hold.getLeaseRevision() != target.leaseRevision()
                || !Objects.equals(hold.getSourceRevision(), execution.owner().sourceRevision())
                || execution.command().sourceRevision() < hold.getSourceRevision()) {
            reject("TARGET_REVISION_CONFLICT");
        }
    }

    private void validateAction(Receipt command, MchBookingHold old, FrozenPlan frozen, LocalDateTime now) {
        Action action = command.action();
        if (old != null) {
            if (!("HELD".equals(old.getStatus()) || "CONFIRMED".equals(old.getStatus()))
                    || old.getExecutionStartedAt() != null) { reject("HOLD_NOT_MUTABLE"); }
            if (action != Action.REPLACE && !old.getCurrentPlanHash().equals(
                    ResourcePlanCodec.hash(command.frozenPayload()))) { reject("CURRENT_PLAN_MISMATCH"); }
            boolean held = "HELD".equals(old.getStatus());
            if (action == Action.EXPIRE) {
                if (!held || now.isBefore(old.getExpiresAt())) { reject("HOLD_NOT_EXPIRED"); }
            } else if (action == Action.RECONCILE) {
                if (!held || !"SOURCE_COORDINATED".equals(old.getExpirationPolicy())) {
                    reject("HOLD_NOT_RECONCILABLE");
                }
            } else if (action != Action.RELEASE && (!held && action != Action.REPLACE
                    || held && !now.isBefore(old.getExpiresAt()))) { reject("HOLD_NOT_LIVE"); }
        }
        if (action == Action.RESERVE || action == Action.REPLACE) {
            Instant time = now.toInstant(ZoneOffset.UTC);
            if (frozen.demands().stream().flatMap(d -> d.intervals().stream())
                    .anyMatch(i -> i.startAt().isBefore(time))) { reject("PLAN_START_IN_PAST"); }
        }
    }

    private Map<String, MchResource> lockRoots(ResourceCommandScopeRow scope, FrozenPlan frozen, FrozenPlan oldPlan,
            List<ResourceBookingAllocationRow> allocations) {
        Map<String, MchResource> discovered = new TreeMap<>();
        Set<String> references = new TreeSet<>();
        frozen.demands().forEach(d -> references.add(d.resourceNo()));
        if (oldPlan != null) { oldPlan.demands().forEach(d -> references.add(d.resourceNo())); }
        allocations.forEach(a -> references.add(a.resourceNo()));
        for (String reference : references) {
            var row = resources.selectScopedResource(scope, reference);
            if (row == null) { throw new IllegalStateException("allocation resource unavailable"); }
            discovered.put(row.getResourceNo(), row);
        }
        // A REPLACE may use ten old plus ten new resources, but each plan is independently bounded by admission.
        if (discovered.size() > 20) { throw new IllegalStateException("resource union exceeds limit"); }
        Set<String> people = new TreeSet<>();
        for (var resource : discovered.values()) {
            if ("STAFF".equals(resource.getResourceType())) {
                var person = personnel.selectScopedPersonnel(scope, resource.getPersonnelNo());
                if (person == null) { throw new IllegalStateException("allocation personnel unavailable"); }
                people.add(person.getPersonnelNo());
            }
        }
        for (String person : people) {
            if (personnel.lockScopedPersonnel(scope, person) == null) {
                throw new IllegalStateException("allocation personnel disappeared");
            }
        }
        Map<String, MchResource> locked = new TreeMap<>();
        for (var observed : discovered.values()) {
            var row = resources.lockScopedResource(scope, observed.getResourceNo());
            if (row == null || !Objects.equals(row.getPersonnelNo(), observed.getPersonnelNo())
                    || !Objects.equals(row.getResourceType(), observed.getResourceType())) {
                throw new IllegalStateException("resource identity changed; retry discovery");
            }
            locked.put(row.getResourceNo(), row);
        }
        return locked;
    }

    private void verifyAllocations(ResourceCommandScopeRow scope, FrozenPlan oldPlan,
            List<ResourceBookingAllocationRow> allocations, Map<String, MchResource> roots) {
        var demands = oldPlan.demands().stream().map(d -> {
            var canonical = resources.selectScopedResource(scope, d.resourceNo());
            if (canonical == null || !roots.containsKey(canonical.getResourceNo())) {
                throw new IllegalStateException("old plan identity changed");
            }
            return new ResourceDemandNormalizer.Demand(canonical.getResourceNo(), d.sourceLineRef(), d.quantity(),
                    d.intervals());
        }).toList();
        var planRoots = demands.stream().map(ResourceDemandNormalizer.Demand::resourceNo).distinct()
                .map(roots::get).map(r -> new ResourceFact(r.getResourceNo(), Type.valueOf(r.getResourceType()),
                        r.getStoreNo(), r.getPersonnelNo(), r.getName(), r.getCapacity(),
                        r.getOpenOccupationNo() != null)).toList();
        Plan expected = ResourceDemandNormalizer.normalize(demands, planRoots);
        var actual = allocations.stream().map(a -> new ResourceDemandNormalizer.Unit(a.resourceNo(),
                a.sourceLineRef(), a.startAt().toInstant(ZoneOffset.UTC), a.endAt().toInstant(ZoneOffset.UTC), a.qty()))
                .sorted(Comparator.comparing(ResourceDemandNormalizer.Unit::resourceNo)
                        .thenComparing(ResourceDemandNormalizer.Unit::startAt)
                        .thenComparing(ResourceDemandNormalizer.Unit::sourceLineRef)).toList();
        if (!expected.units().equals(actual)) {
            throw new IllegalStateException("active units do not match the complete current plan");
        }
    }

    private MchResourceBucket initialize(ResourceCommandScopeRow scope, MchResource root, LocalDateTime start) {
        var bucket = new MchResourceBucket();
        bucket.setTenantNo(scope.tenantNo());
        bucket.setEntityNo(scope.entityNo());
        bucket.setBucketNo(BizKey.next("RBB"));
        bucket.setResourceNo(root.getResourceNo());
        bucket.setStartAt(start);
        bucket.setEndAt(start.plusMinutes(5));
        bucket.setCapacity(root.getCapacity());
        bucket.setCapacityRevision(root.getVersion());
        bucket.setHeldQty(0);
        bucket.setConfirmedQty(0);
        bucket.setOccupiedQty(0);
        bucket.setCleaningQty(0);
        one(buckets.insert(bucket));
        return buckets.lockBookingBucket(scope, root.getResourceNo(), start);
    }

    private MchBookingHold newHold(ResourceCommandScopeRow scope, Receipt command, MchBookingHold old,
            LocalDateTime now) {
        var hold = new MchBookingHold();
        hold.setTenantNo(scope.tenantNo());
        hold.setEntityNo(scope.entityNo());
        hold.setStoreNo(scope.storeNo());
        hold.setHoldNo(BizKey.next("RBH"));
        hold.setOwnerNo(command.ownerNo());
        hold.setCommandNo(command.commandNo());
        hold.setSourceRevision(command.sourceRevision());
        hold.setLeaseRevision(1L);
        hold.setStatus(old == null ? "HELD" : old.getStatus());
        hold.setExpirationPolicy("SOURCE_COORDINATED");
        hold.setExpiresAt(now.plusMinutes(15));
        hold.setCreationPlanHash(ResourcePlanCodec.hash(command.frozenPayload()));
        hold.setCurrentPlanHash(ResourcePlanCodec.hash(command.frozenPayload()));
        if (old != null && old.getPlanRevision() == Long.MAX_VALUE) { reject("PLAN_REVISION_EXHAUSTED"); }
        hold.setPlanRevision(old == null ? 1L : old.getPlanRevision() + 1);
        hold.setReplacesHoldNo(old == null ? null : old.getHoldNo());
        return hold;
    }

    private LocalDateTime now() {
        return local(clock.instant()).truncatedTo(ChronoUnit.MILLIS);
    }
    private static LocalDateTime local(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    private static String actor() { return SecurityUtils.requireUser().userNo(); }
    private static void one(int changed) {
        if (changed != 1) { throw new IllegalStateException("booking mutation CAS failed"); }
    }
    private static void reject(String code) { throw new BookingRejected(code); }
    private record BucketKey(String resource, LocalDateTime start) {
    }
    private static final class Delta {
        private long held;
        private long confirmed;
    }
    private static final class BookingRejected extends RuntimeException {
        private final String code;
        private BookingRejected(String code) { super(code); this.code = code; }
    }
}
