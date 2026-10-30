package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchResource;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelSkillMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ScheduleRuleMapper;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Demand;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Plan;
import ai.neargo.shop.merchant.policy.ScheduleWindowAssembler;
import ai.neargo.shop.merchant.service.ResourceAdmissionService;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.merchant.service.ResourceCommandService.Execution;
import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceFactsPort.Type;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort.Query;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local admission shares the command transaction and locks; read-only industry Ports remain unchanged. */
@Service
public class ResourceAdmissionServiceImpl implements ResourceAdmissionService {
    private final ResourceCommandService commands;
    private final PersonnelMapper personnel;
    private final ResourceMapper resources;
    private final PersonnelSkillMapper skills;
    private final ScheduleRuleMapper schedules;
    private final DataSource dataSource;

    public ResourceAdmissionServiceImpl(ResourceCommandService commands, PersonnelMapper personnel,
            ResourceMapper resources, PersonnelSkillMapper skills, ScheduleRuleMapper schedules,
            @Qualifier("dataSource") DataSource dataSource) {
        this.commands = commands;
        this.personnel = personnel;
        this.resources = resources;
        this.skills = skills;
        this.schedules = schedules;
        this.dataSource = dataSource;
    }

    @Override
    public Plan lockAndValidate(Execution execution, List<Demand> demands, List<Qualification> qualifications) {
        try {
            var trusted = commands.requireExecution(execution);
            var scope = new ResourceCommandScopeRow(trusted.tenantNo(), trusted.entityNo(), trusted.storeNo());
            return DataScopeContext.executeWithoutScope(() -> validate(scope, demands, qualifications));
        } catch (RuntimeException failure) {
            if (TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder) {
                holder.setRollbackOnly();
            }
            throw failure;
        }
    }

    private Plan validate(ResourceCommandScopeRow scope, List<Demand> demands, List<Qualification> qualifications) {
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
                    throw new IllegalStateException("scoped resource unavailable");
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
                    throw new IllegalStateException("resource personnel unavailable");
                }
                staff.put(resource.getResourceNo(), person.getPersonnelNo());
                people.add(person.getPersonnelNo());
            }
        }
        for (String no : people.stream().sorted().toList()) {
            var person = personnel.lockScopedPersonnel(scope, no);
            if (person == null || !"ACTIVE".equals(person.getStatus())) {
                throw new IllegalStateException("resource personnel inactive");
            }
        }
        List<ResourceFact> facts = new ArrayList<>();
        for (var original : discovered.values()) {
            var row = resources.lockScopedResource(scope, original.getResourceNo());
            if (row == null || !Objects.equals(row.getPersonnelNo(), original.getPersonnelNo())
                    || !Objects.equals(row.getResourceType(), original.getResourceType())) {
                throw new IllegalStateException("resource identity changed; retry discovery");
            }
            if (!"ACTIVE".equals(row.getStatus()) || row.getOpenOccupationNo() != null) {
                throw new IllegalStateException("resource inactive or open occupied");
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
        var windows = ScheduleWindowAssembler.expand(rows, query);
        for (var unit : plan.units()) {
            if (windows.stream().noneMatch(window -> window.resourceNo().equals(unit.resourceNo())
                    && !window.startAt().isAfter(unit.startAt()) && !window.endAt().isBefore(unit.endAt()))) {
                throw new IllegalStateException("whole bucket lacks unambiguous employment/schedule coverage");
            }
            var groupRequirements = required.getOrDefault(
                    new Group(unit.resourceNo(), unit.sourceLineRef()), List.of());
            for (var requirement : groupRequirements) {
                if (!requirement.required()) {
                    continue;
                }
                if (skills.countCoveringQualification(scope, staff.get(unit.resourceNo()), requirement.goodsNo(),
                        requirement.skuNo(), LocalDateTime.ofInstant(unit.startAt(), ZoneOffset.UTC),
                        LocalDateTime.ofInstant(unit.endAt(), ZoneOffset.UTC)) == 0) {
                    throw new IllegalStateException("whole bucket lacks required qualification");
                }
            }
        }
        return plan;
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
