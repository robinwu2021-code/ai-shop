package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchPersonnel;
import ai.neargo.shop.merchant.entity.MchResource;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelAccountMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelSkillMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelStoreMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ScheduleRuleMapper;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Demand;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Plan;
import ai.neargo.shop.merchant.policy.ResourcePhysicalProtectionPlan.PhysicalPlan;
import ai.neargo.shop.merchant.policy.ResourcePhysicalProtectionPlan;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.merchant.policy.ResourcePlanCodec;
import ai.neargo.shop.merchant.policy.ScheduleWindowAssembler;
import ai.neargo.shop.merchant.service.ResourceAdmissionService;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.merchant.service.ResourceCommandService.Execution;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceFactsPort.Type;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.QualificationWitness;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.CoveredBucket;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.EmploymentWitness;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.QualifiedGroup;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.ScheduleWitness;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.SkillWitness;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthority;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort.Query;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort.Window;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local admission shares the command transaction and locks; read-only industry Ports remain unchanged. */
@Service
public class ResourceAdmissionServiceImpl implements ResourceAdmissionService {
    private final ResourceCommandService commands;
    private final PersonnelMapper personnel;
    private final ResourceMapper resources;
    private final PersonnelSkillMapper skills;
    private final ScheduleRuleMapper schedules;
    private final DataSource dataSource;
    private final PersonnelOperatorFactsPort currentPersonnel;
    private final PersonnelAccountMapper links;
    private final PersonnelStoreMapper employments;
    private final Clock clock;
    private final TransactionTemplate observationTransaction;

    public ResourceAdmissionServiceImpl(ResourceCommandService commands, PersonnelMapper personnel,
            ResourceMapper resources, PersonnelSkillMapper skills, ScheduleRuleMapper schedules,
            @Qualifier("dataSource") DataSource dataSource, PersonnelOperatorFactsPort currentPersonnel,
            PersonnelAccountMapper links, PersonnelStoreMapper employments,
            @Qualifier("transactionManager") PlatformTransactionManager manager, ObjectProvider<Clock> clocks) {
        this.commands = commands;
        this.personnel = personnel;
        this.resources = resources;
        this.skills = skills;
        this.schedules = schedules;
        this.dataSource = dataSource;
        this.currentPersonnel = currentPersonnel;
        this.links = links;
        this.employments = employments;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
        observationTransaction = new TransactionTemplate(manager);
        observationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        observationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Plan lockAndValidate(Execution execution, List<Demand> demands, List<Qualification> qualifications) {
        try {
            var trusted = commands.requireExecution(execution);
            var scope = new ResourceCommandScopeRow(trusted.tenantNo(), trusted.entityNo(), trusted.storeNo());
            return DataScopeContext.executeWithoutScope(() -> validate(scope, demands, qualifications, null).plan());
        } catch (RuntimeException failure) {
            if (TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder) {
                holder.setRollbackOnly();
            }
            throw failure;
        }
    }

    @Override
    public OccupationAdmission lockAndValidateOccupation(ResourceCommandService.OccupationExecution execution,
            StartAuthority source,
            PhysicalPlan physical) {
        try {
        var trusted = commands.requireOccupationExecution(execution);
        var scope = new ResourceCommandScopeRow(trusted.tenantNo(), trusted.entityNo(), trusted.storeNo());
        return DataScopeContext.executeWithoutScope(() -> {
            var frozen = ResourcePlanCodec.decode(source.resourcePlanJson());
            var original = validate(scope, frozen.demands(), frozen.qualifications(), null);
            var sourceKeys = source.sourceGroups().stream().map(g -> g.resourceNo() + "\n" + g.sourceLineRef()).sorted().toList();
            var demandKeys = frozen.demands().stream().map(d -> d.resourceNo() + "\n" + d.sourceLineRef()).sorted().toList();
            if (!sourceKeys.equals(demandKeys)) throw new EligibilityDenied("complete source group multiset differs from frozen plan");
            if (source.acceptedRoster().stream().anyMatch(r -> source.sourceGroups().stream().noneMatch(g ->
                    g.resourceNo().equals(r.resourceNo()) && g.requirementNo().equals(r.requirementNo())
                            && g.resourceOrdinal() == r.ordinal() && g.roleCode().equals(r.roleCode())
                            && Objects.equals(g.personnelNo(), r.personnelNo()))))
                throw new EligibilityDenied("accepted STAFF outside frozen task groups");
            var facts = new ArrayList<ResourceFact>();
            var witnesses = new ArrayList<QualificationWitness>();
            for (var group : physical.groups()) {
                var resource = resources.selectScopedResource(scope, group.resourceNo());
                if (resource == null || !"ACTIVE".equals(resource.getStatus())) throw new EligibilityDenied("physical resource unavailable");
                var roster = source.acceptedRoster().stream().filter(r -> r.resourceNo().equals(group.resourceNo())).toList();
                var qualified = new ArrayList<QualifiedGroup>();
                EmploymentWitness employmentProof = null;
                String personnelNo = resource.getPersonnelNo();
                if ("STAFF".equals(resource.getResourceType())) {
                    if (roster.isEmpty() || roster.stream().anyMatch(r -> !personnelNo.equals(r.personnelNo())
                            || !r.personnel().equals(roster.getFirst().personnel())
                            || !r.canonicalAccountNo().equals(roster.getFirst().canonicalAccountNo())))
                        throw new EligibilityDenied("physical STAFF lacks exact accepted roster");
                    var required = source.sourceGroups().stream().filter(g -> g.resourceNo().equals(group.resourceNo()))
                            .map(g -> List.of(g.requirementNo(), Integer.toString(g.resourceOrdinal()), g.roleCode(), g.resourceNo(), g.personnelNo()))
                            .map(Object::toString).sorted().toList();
                    var actual = roster.stream().map(r -> List.of(r.requirementNo(), Integer.toString(r.ordinal()), r.roleCode(), r.resourceNo(),
                            r.personnelNo())).map(Object::toString).sorted().toList();
                    if (!required.equals(actual)) throw new EligibilityDenied("required STAFF roster is not complete");
                    var member = roster.getFirst();
                    var current = validate(scope, frozen.demands().stream().filter(d -> d.resourceNo().equals(group.resourceNo())).toList(),
                            frozen.qualifications().stream().filter(q -> q.resourceNo().equals(group.resourceNo())).toList(), member.personnel());
                    qualified.addAll(current.groups());
                    var employment = employments.selectQualificationEvidence(scope, personnelNo, member.personnel().assignmentNo());
                    if (employment == null || employment.getVersion() != member.personnel().assignmentRevision()
                            || employment.getValidFrom().isAfter(local(group.buckets().getFirst().startAt()))
                            || employment.getValidTo() != null && employment.getValidTo().isBefore(local(physical.protectionEnd())))
                        throw new EligibilityDenied("continuous physical interval lacks employment");
                    employmentProof = new EmploymentWitness(employment.getAssignmentNo(), employment.getVersion(),
                            utc(employment.getValidFrom()), utc(employment.getValidTo()));
                } else if (!roster.isEmpty()) throw new EligibilityDenied("non-STAFF resource in accepted STAFF roster");
                var from = group.buckets().getFirst().startAt();
                var until = physical.protectionEnd();
                var query = new Query(scope.tenantNo(), scope.entityNo(), scope.storeNo(), Set.of(resource.getResourceNo()), from, until);
                var rows = schedules.selectWindowRules(scope.tenantNo(), scope.entityNo(), scope.storeNo(), query.resourceNos(),
                        local(from), local(until), local(from).toLocalDate().minusDays(2), local(until).toLocalDate().plusDays(2));
                var windows = ScheduleWindowAssembler.expand(rows, query);
                var continuous = new ArrayList<ScheduleWitness>();
                for (var bucket : group.buckets()) {
                    var covered = windows.stream().filter(w -> w.resourceNo().equals(group.resourceNo())
                            && !w.startAt().isAfter(bucket.startAt()) && !w.endAt().isBefore(bucket.endAt())).toList();
                    if (covered.size() != 1) throw new EligibilityDenied("continuous physical gap lacks unambiguous schedule");
                    var window = covered.getFirst();
                    if (employmentProof != null && !employmentProof.assignmentNo().equals(window.assignmentNo()))
                        throw new EligibilityDenied("continuous physical employment changed");
                    var rules = schedules.selectQualificationEvidence(scope, group.resourceNo(), window.ruleNos());
                    if (rules.size() != window.ruleNos().size()) throw new EligibilityDenied("physical schedule original changed");
                    for (var rule : rules) {
                        var witness = new ScheduleWitness(rule.getRuleNo(), rule.getVersion(), window.assignmentNo(), window.startAt(), window.endAt());
                        if (!continuous.contains(witness)) continuous.add(witness);
                    }
                }
                facts.add(new ResourceFact(resource.getResourceNo(), Type.valueOf(resource.getResourceType()),
                        personnelNo == null ? scope.storeNo() : null, personnelNo, resource.getName(), resource.getCapacity(), false));
                witnesses.add(new QualificationWitness(resource.getResourceNo(),
                        personnelNo, resource.getVersion(), qualified, continuous, employmentProof));
            }
            return new OccupationAdmission(original.plan(), facts, witnesses);
        });
        } catch (RuntimeException failure) {
            if (TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder) holder.setRollbackOnly();
            throw failure;
        }
    }

    @Override
    public ResourceQualificationPort.Observation observeQualifications(ResourceQualificationPort.Query query) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current();
        if (!"MAIN".equals(identity.tenantNo()) || !"MERCHANT".equals(identity.realm())
                || !identity.tenantNo().equals(query.tenantNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var access = new MerchantOperatorAccessPort.Query(query.tenantNo(), query.entityNo(), query.storeNo(),
                identity.actorNo(), identity.realm(), identity.subjectKind(), "biz:service:task:self");
        var operatorQuery = new PersonnelOperatorFactsPort.Query(access);
        var before = currentPersonnel.current(operatorQuery);
        if (before.state() != PersonnelOperatorFactsPort.LinkState.LINKED) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var scope = new ResourceCommandScopeRow(query.tenantNo(), query.entityNo(), query.storeNo());
        var demands = query.demands().stream().map(d -> new Demand(d.resourceNo(), d.sourceLineRef(), d.quantity(),
                d.intervals().stream().map(i -> new ResourceDemandNormalizer.Interval(i.startAt(), i.endAt()))
                        .toList())).toList();
        var requirements = query.qualifications().stream().map(q -> new Qualification(q.resourceNo(),
                q.sourceLineRef(), q.goodsNo(), q.skuNo(), q.required())).toList();
        Checked checked;
        try {
            checked = DataScopeContext.executeWithoutScope(() -> observationTransaction.execute(
                    status -> validate(scope, demands, requirements, before.personnel())));
        } catch (EligibilityDenied denied) {
            return ResourceQualificationPort.observation(ResourceQualificationPort.Outcome.NOT_ELIGIBLE, query,
                    before.personnel(), List.of(), at, at, denied.getMessage());
        }
        // Access resolution never runs under Merchant personnel/resource locks.
        var after = currentPersonnel.current(operatorQuery);
        if (!identity.equals(DelegatedIdentityContext.current()) || !before.personnel().equals(after.personnel())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var until = checked.groups().stream().map(ResourceAdmissionServiceImpl::validUntil)
                .min(Comparator.naturalOrder()).orElseThrow();
        if (!at.isBefore(until)) {
            return ResourceQualificationPort.observation(ResourceQualificationPort.Outcome.NOT_ELIGIBLE, query,
                    before.personnel(), List.of(), at, at, "QUALIFICATION_INTERVAL_EXPIRED");
        }
        return ResourceQualificationPort.observation(ResourceQualificationPort.Outcome.PROVEN, query,
                before.personnel(), checked.groups(), at, until, null);
    }

    @Override
    public WorkflowAdmission lockWorkflow(ResourceCommandScopeRow scope, Set<String> resourceNos,
            String resourcePlanJson, List<AcceptedStaffRef> roster) {
        Map<String, MchResource> discovered = new TreeMap<>();
        for (var no : resourceNos.stream().sorted().toList()) {
            var resource = resources.selectScopedResource(scope, no);
            if (resource == null) throw new EligibilityDenied("workflow resource unavailable");
            discovered.put(no, resource);
        }
        Map<String, MchPersonnel> people = new TreeMap<>();
        for (var no : discovered.values().stream().map(MchResource::getPersonnelNo)
                .filter(Objects::nonNull).distinct().sorted().toList()) {
            var person = personnel.lockScopedPersonnel(scope, no);
            if (person == null) throw new EligibilityDenied("workflow personnel unavailable");
            people.put(no, person);
        }
        Map<String, MchResource> locked = new TreeMap<>();
        for (var resource : discovered.values()) {
            var current = resources.lockScopedResource(scope, resource.getResourceNo());
            if (current == null || !Objects.equals(current.getPersonnelNo(), resource.getPersonnelNo())
                    || !Objects.equals(current.getResourceType(), resource.getResourceType()))
                throw new EligibilityDenied("workflow resource identity changed");
            locked.put(current.getResourceNo(), current);
        }
        var proof = new ArrayList<QualifiedGroup>();
        if (resourcePlanJson != null) {
            var plan = ResourcePlanCodec.decode(resourcePlanJson);
            var roots = new WorkflowRoots(people, locked);
            var physical=ResourcePhysicalProtectionPlan.from(ResourcePlanContract.decode(resourcePlanJson),clock.instant());
            for (var demand:plan.demands()) {
                var resource=locked.get(demand.resourceNo());
                if(resource==null) throw new EligibilityDenied("qualification outside locked resource union");
                var accepted=roster.stream().filter(r -> r.resourceNo().equals(demand.resourceNo())).toList();
                OperatorPersonnelFact self=null;
                if("STAFF".equals(resource.getResourceType())) {
                    if(accepted.isEmpty() || accepted.stream().anyMatch(r -> !resource.getPersonnelNo().equals(r.personnelNo())
                            || !r.personnel().equals(accepted.getFirst().personnel())))
                        throw new EligibilityDenied("workflow STAFF lacks accepted current roster");
                    self=accepted.getFirst().personnel();
                }
                var continuous=new Demand(demand.resourceNo(),demand.sourceLineRef(),demand.quantity(),
                        List.of(new ResourceDemandNormalizer.Interval(physical.actualStartAt(),physical.protectionEnd())));
                proof.addAll(validate(scope,List.of(continuous),plan.qualifications().stream().filter(q ->
                        q.resourceNo().equals(demand.resourceNo())&&q.sourceLineRef().equals(demand.sourceLineRef())).toList(),self,roots).groups());
            }
        }
        return new WorkflowAdmission(locked.values().stream().map(r -> new ResourceFact(r.getResourceNo(),
                Type.valueOf(r.getResourceType()), r.getPersonnelNo() == null ? scope.storeNo() : null,
                r.getPersonnelNo(), r.getName(), r.getCapacity(), false)).toList(), proof);
    }

    private record WorkflowRoots(Map<String, MchPersonnel> people, Map<String, MchResource> resources) { }

    private Checked validate(ResourceCommandScopeRow scope, List<Demand> demands,
            List<Qualification> qualifications, OperatorPersonnelFact self) {
        return validate(scope, demands, qualifications, self, null);
    }

    private Checked validate(ResourceCommandScopeRow scope, List<Demand> demands,
            List<Qualification> qualifications, OperatorPersonnelFact self, WorkflowRoots roots) {
        if (demands == null || demands.isEmpty() || demands.size() > 10000
                || qualifications == null || qualifications.size() > 10000) {
            throw new IllegalArgumentException("bounded demands and qualification requirements required");
        }
        Map<String, MchResource> aliases = new HashMap<>();
        Map<String, MchResource> discovered = new TreeMap<>();
        for (var demand : demands) {
            if (demand == null) {
                throw new IllegalArgumentException("null demand");
            }
            ResourceAdmissionServiceImpl.text(demand.resourceNo(), 32);
            if (!aliases.containsKey(demand.resourceNo())) {
                if (aliases.size() == 10) {
                    throw new IllegalArgumentException("resource reference limit exceeded");
                }
                var row = resources.selectScopedResource(scope, demand.resourceNo());
                if (row == null) {
                    throw new EligibilityDenied("scoped resource unavailable");
                }
                aliases.put(demand.resourceNo(), row);
                discovered.put(row.getResourceNo(), row);
            }
        }
        Map<String, String> staff = new HashMap<>();
        var people = new HashSet<String>();
        for (var resource : discovered.values()) {
            if ("STAFF".equals(resource.getResourceType())) {
                var person = personnel.selectScopedPersonnel(scope, resource.getPersonnelNo());
                if (person == null) {
                    throw new EligibilityDenied("resource personnel unavailable");
                }
                staff.put(resource.getResourceNo(), person.getPersonnelNo());
                people.add(person.getPersonnelNo());
            }
        }
        Map<String, MchPersonnel> lockedPeople = new HashMap<>();
        for (String no : people.stream().sorted().toList()) {
            var person = roots == null ? personnel.lockScopedPersonnel(scope, no) : roots.people().get(no);
            lockedPeople.put(no, person);
            if (person == null || !"ACTIVE".equals(person.getStatus())) {
                throw new EligibilityDenied("resource personnel inactive");
            }
        }
        if (self != null) {
            var person = lockedPeople.get(self.personnelNo());
            var link = links.selectQualificationEvidence(scope, self.personnelNo());
            var employment = employments.selectQualificationEvidence(scope, self.personnelNo(), self.assignmentNo());
            if (person == null || person.getVersion() != self.personnelRevision()
                    || link == null || link.getMchAccountNo() == null || link.getVersion() != self.linkRevision()
                    || employment == null || employment.getVersion() != self.assignmentRevision()
                    || !utc(employment.getValidFrom()).equals(self.validFrom())
                    || !Objects.equals(utc(employment.getValidTo()), self.validTo())
                    || people.size() != 1 || discovered.values().stream().anyMatch(
                            r -> !"STAFF".equals(r.getResourceType())
                                    || !self.personnelNo().equals(r.getPersonnelNo()))) {
                throw new EligibilityDenied("current personnel relationship changed");
            }
        }
        Map<String, MchResource> lockedResources = new HashMap<>();
        List<ResourceFact> facts = new ArrayList<>();
        for (var original : discovered.values()) {
            var row = roots == null ? resources.lockScopedResource(scope, original.getResourceNo())
                    : roots.resources().get(original.getResourceNo());
            if (row == null || !Objects.equals(row.getPersonnelNo(), original.getPersonnelNo())
                    || !Objects.equals(row.getResourceType(), original.getResourceType())) {
                throw new EligibilityDenied("resource identity changed; retry discovery");
            }
            lockedResources.put(row.getResourceNo(), row);
            if (!"ACTIVE".equals(row.getStatus()) || self == null && row.getOpenOccupationNo() != null) {
                throw new EligibilityDenied("resource inactive or open occupied");
            }
            facts.add(new ResourceFact(row.getResourceNo(), Type.valueOf(row.getResourceType()),
                    staff.containsKey(row.getResourceNo()) ? null : scope.storeNo(), staff.get(row.getResourceNo()),
                    row.getName(), row.getCapacity(), false));
        }
        var canonical = demands.stream().map(demand -> new Demand(aliases.get(demand.resourceNo()).getResourceNo(),
                demand.sourceLineRef(), demand.quantity(), demand.intervals())).toList();
        Plan plan = ResourceDemandNormalizer.normalize(canonical, facts);
        var required = requirements(scope, qualifications, aliases, staff, canonical);
        long checks = 0;
        for (var unit : plan.units()) {
            checks += required.getOrDefault(new Group(unit.resourceNo(), unit.sourceLineRef()), List.of()).size();
            if (checks > 10000) {
                throw new IllegalArgumentException("qualification check limit exceeded");
            }
        }
        var start = plan.buckets().stream().map(ResourceDemandNormalizer.BucketDemand::startAt)
                .min(Comparator.naturalOrder()).orElseThrow();
        var end = plan.buckets().stream().map(ResourceDemandNormalizer.BucketDemand::endAt)
                .max(Comparator.naturalOrder()).orElseThrow();
        var query = new Query(scope.tenantNo(), scope.entityNo(), scope.storeNo(), discovered.keySet(), start, end);
        var from = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
        var until = LocalDateTime.ofInstant(end, ZoneOffset.UTC);
        var rows = schedules.selectWindowRules(scope.tenantNo(), scope.entityNo(), scope.storeNo(),
                query.resourceNos(), from, until, from.toLocalDate().minusDays(2), until.toLocalDate().plusDays(2));
        if (rows.size() > 2000) {
            throw new IllegalStateException("schedule snapshot limit exceeded");
        }
        List<Window> windows;
        try {
            windows = ScheduleWindowAssembler.expand(rows, query);
        } catch (IllegalStateException invalidCoverage) {
            throw new EligibilityDenied(invalidCoverage.getMessage());
        }
        Map<Group, Evidence> evidence = new TreeMap<>(Comparator.comparing(Group::resourceNo)
                .thenComparing(Group::lineRef));
        for (var unit : plan.units()) {
            if (windows.stream().noneMatch(window -> window.resourceNo().equals(unit.resourceNo())
                    && !window.startAt().isAfter(unit.startAt()) && !window.endAt().isBefore(unit.endAt()))) {
                throw new EligibilityDenied("whole bucket lacks unambiguous employment/schedule coverage");
            }
            var groupRequirements = required.getOrDefault(
                    new Group(unit.resourceNo(), unit.sourceLineRef()), List.of());
            var groupKey = new Group(unit.resourceNo(), unit.sourceLineRef());
            var proof = evidence.computeIfAbsent(groupKey, ignored -> new Evidence());
            proof.buckets.add(new CoveredBucket(unit.startAt(), unit.endAt(), unit.quantity()));
            if (self != null) {
                var window = windows.stream().filter(w -> w.resourceNo().equals(unit.resourceNo())
                        && !w.startAt().isAfter(unit.startAt()) && !w.endAt().isBefore(unit.endAt()))
                        .findFirst().orElseThrow();
                if (!self.assignmentNo().equals(window.assignmentNo())) {
                    throw new EligibilityDenied("whole bucket belongs to a different employment");
                }
                var employment = employments.selectQualificationEvidence(scope, self.personnelNo(),
                        window.assignmentNo());
                if (employment == null || employment.getVersion() != self.assignmentRevision()
                        || employment.getValidFrom().isAfter(local(unit.startAt()))
                        || employment.getValidTo() != null && employment.getValidTo().isBefore(local(unit.endAt()))) {
                    throw new EligibilityDenied("whole bucket lacks current employment");
                }
                proof.employment = new EmploymentWitness(employment.getAssignmentNo(), employment.getVersion(),
                        utc(employment.getValidFrom()), utc(employment.getValidTo()));
                var rules = schedules.selectQualificationEvidence(scope, unit.resourceNo(), window.ruleNos());
                if (rules.size() != window.ruleNos().size()) {
                    throw new EligibilityDenied("schedule evidence changed");
                }
                for (var rule : rules) {
                    var witness = new ScheduleWitness(rule.getRuleNo(), rule.getVersion(), window.assignmentNo(),
                            window.startAt(), window.endAt());
                    if (!proof.schedules.contains(witness)) {
                        proof.schedules.add(witness);
                    }
                }
            }
            for (var requirement : groupRequirements) {
                if (!requirement.required()) {
                    continue;
                }
                var skill = skills.selectCoveringQualification(scope, staff.get(unit.resourceNo()),
                        requirement.goodsNo(), requirement.skuNo(), local(unit.startAt()), local(unit.endAt()));
                if (skill == null) {
                    throw new EligibilityDenied("whole bucket lacks required qualification");
                }
                var witness = new SkillWitness(skill.getSkillNo(), skill.getVersion(), skill.getStoreNo(),
                        skill.getGoodsNo(), skill.getSkuNo(), utc(skill.getValidFrom()), utc(skill.getValidTo()));
                if (!proof.skills.contains(witness)) {
                    proof.skills.add(witness);
                }
            }
        }
        var groups = self == null ? List.<QualifiedGroup>of() : evidence.entrySet().stream().map(entry -> {
            var key = entry.getKey();
            var proof = entry.getValue();
            return new QualifiedGroup(key.resourceNo(), key.lineRef(), self.personnelNo(), self.personnelRevision(),
                    lockedResources.get(key.resourceNo()).getVersion(), proof.buckets, proof.skills,
                    proof.schedules, proof.employment);
        }).toList();
        return new Checked(plan, groups);
    }

    private record Checked(Plan plan, List<QualifiedGroup> groups) {}

    private static final class Evidence {
        private final List<CoveredBucket> buckets = new ArrayList<>();
        private final List<SkillWitness> skills = new ArrayList<>();
        private final List<ScheduleWitness> schedules = new ArrayList<>();
        private EmploymentWitness employment;
    }

    public static final class EligibilityDenied extends IllegalStateException {
        private EligibilityDenied(String reason) { super(reason); }
    }

    private static Instant utc(LocalDateTime at) {
        return at == null ? null : at.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    /** Each group's own coverage defines its expiry; unrelated selected slots cannot shorten it. */
    public static Instant validUntil(QualifiedGroup group) {
        var until = group.buckets().stream().map(CoveredBucket::endAt).max(Comparator.naturalOrder()).orElseThrow();
        if (group.employment().validTo() != null && group.employment().validTo().isBefore(until)) {
            until = group.employment().validTo();
        }
        return until;
    }

    private Map<Group, List<Qualification>> requirements(ResourceCommandScopeRow scope,
            List<Qualification> qualifications,
            Map<String, MchResource> aliases, Map<String, String> staff, List<Demand> demands) {
        var groups = new HashSet<Group>();
        for (var demand : demands) {
            groups.add(new Group(demand.resourceNo(), demand.sourceLineRef()));
        }
        Map<Group, List<Qualification>> result = new HashMap<>();
        var seen = new HashSet<Qualification>();
        var references = new HashSet<String>();
        for (var value : qualifications) {
            if (value == null) {
                throw new IllegalArgumentException("null qualification requirement");
            }
            ResourceAdmissionServiceImpl.text(value.resourceNo(), 32);
            ResourceAdmissionServiceImpl.text(value.sourceLineRef(), 128);
            if (value.required()) {
                ResourceAdmissionServiceImpl.text(value.goodsNo(), 32);
                if (value.skuNo() != null) {
                    ResourceAdmissionServiceImpl.text(value.skuNo(), 32);
                }
            } else if (value.goodsNo() != null || value.skuNo() != null) {
                throw new IllegalArgumentException("qualification exemption cannot carry project references");
            }
            references.add(value.resourceNo());
            if (references.size() > 10) {
                throw new IllegalArgumentException("qualification resource reference limit exceeded");
            }
            var resource = aliases.get(value.resourceNo());
            if (resource == null) {
                resource = resources.selectScopedResource(scope, value.resourceNo());
                if (resource != null) {
                    aliases.put(value.resourceNo(), resource);
                }
            }
            var group = resource == null ? null : new Group(resource.getResourceNo(), value.sourceLineRef());
            if (group == null || !groups.contains(group) || !staff.containsKey(group.resourceNo())) {
                throw new IllegalArgumentException("qualification must identify an existing staff group");
            }
            var canonical = new Qualification(group.resourceNo(), group.lineRef(), value.goodsNo(), value.skuNo(),
                    value.required());
            if (!seen.add(canonical)) {
                throw new IllegalArgumentException("duplicate qualification requirement");
            }
            var groupRequirements = result.computeIfAbsent(group, ignored -> new ArrayList<>());
            if (!groupRequirements.isEmpty() && (!value.required() || !groupRequirements.getFirst().required())) {
                throw new IllegalArgumentException("qualification exemption cannot mix with project requirements");
            }
            groupRequirements.add(canonical);
        }
        for (var group : groups) {
            if (staff.containsKey(group.resourceNo()) && !result.containsKey(group)) {
                throw new IllegalArgumentException("every staff group requires explicit qualifications");
            }
        }
        return result;
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
            throw new IllegalArgumentException("bounded nonblank reference required");
        }
    }

    private record Group(String resourceNo, String lineRef) {
    }
}
