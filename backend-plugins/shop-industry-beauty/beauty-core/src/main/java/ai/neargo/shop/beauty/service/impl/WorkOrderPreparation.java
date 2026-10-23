package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.AppointmentPlanItemVO;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.WorkOrderRequirementVO;
import ai.neargo.shop.beauty.dto.WorkOrderStepTemplateVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentArrangement;
import ai.neargo.shop.beauty.entity.BtyAppointmentItem;
import ai.neargo.shop.beauty.entity.BtyServiceDefinition;
import ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion;
import ai.neargo.shop.beauty.entity.BtyServiceRequirement;
import ai.neargo.shop.beauty.entity.BtyStorePolicy;
import ai.neargo.shop.beauty.entity.BtyStorePolicyVersion;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ReceptionIdentity.VerifiedIdentity;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reconstructs original scope from immutable local definitions and the successful owner-bound arrangement. */
public final class WorkOrderPreparation {
    private final ObjectProvider<ServiceCatalogFactsPort> products;
    private final AppointmentPersistence db;
    private final AppointmentCoordinator appointments;
    private final BeautyMappers.ServiceDefinitionMapper definitions;
    private final BeautyMappers.ServiceDefinitionVersionMapper versions;
    private final BeautyMappers.ServiceRequirementMapper requirements;
    private final BeautyMappers.StorePolicyMapper policies;
    private final BeautyMappers.StorePolicyVersionMapper policyVersions;
    public WorkOrderPreparation(AppointmentPersistence db, AppointmentCoordinator appointments,
            BeautyMappers.ServiceDefinitionMapper definitions, BeautyMappers.ServiceDefinitionVersionMapper versions,
            BeautyMappers.ServiceRequirementMapper requirements, BeautyMappers.StorePolicyMapper policies,
            BeautyMappers.StorePolicyVersionMapper policyVersions, ObjectProvider<ServiceCatalogFactsPort> products) {
        this.db = db; this.appointments = appointments; this.definitions = definitions; this.versions = versions;
        this.requirements = requirements; this.policies = policies; this.policyVersions = policyVersions;
        this.products = products;
    }
    public PreparedWorkOrderScope fromAppointment(BtyAppointment root) {
        var evidence = appointments.currentArrangementEvidence(root);
        return db.read(() -> load(root, evidence));
    }
    private PreparedWorkOrderScope load(BtyAppointment root, HoldEvidence evidence) {
        var arrangement = db.arrangements.selectOne(Wrappers.<BtyAppointmentArrangement>lambdaQuery()
                .eq(BtyAppointmentArrangement::getTenantNo, root.getTenantNo())
                .eq(BtyAppointmentArrangement::getEntityNo, root.getEntityNo())
                .eq(BtyAppointmentArrangement::getAppointmentNo, root.getAppointmentNo())
                .eq(BtyAppointmentArrangement::getArrangementRevision, evidence.arrangementRevision()));
        AppointmentPersistence.require(arrangement != null && evidence.arrangementHash().equals(
                IdempotencyScope.requestHash(arrangement.getPreviewNo(), arrangement.getHoldNo(),
                        arrangement.getSnapshot())));
        var preview = db.preview(arrangement.getPreviewNo());
        var snapshot = db.json.readValue(arrangement.getSnapshot(), AppointmentMatching.Snapshot.class);
        AppointmentPersistence.require(!snapshot.items().isEmpty() && snapshot.items().size() <= 10);
        var policy = policyVersions.selectOne(Wrappers.<BtyStorePolicyVersion>lambdaQuery()
                .eq(BtyStorePolicyVersion::getTenantNo, root.getTenantNo())
                .eq(BtyStorePolicyVersion::getEntityNo, root.getEntityNo())
                .eq(BtyStorePolicyVersion::getPolicyVersionNo, root.getPolicyVersionNo()));
        AppointmentPersistence.require(policy != null && policy.getSchemaVersion() == 1);
        var head = policies.selectOne(Wrappers.<BtyStorePolicy>lambdaQuery()
                .eq(BtyStorePolicy::getTenantNo, root.getTenantNo()).eq(BtyStorePolicy::getEntityNo, root.getEntityNo())
                .eq(BtyStorePolicy::getStoreNo, root.getStoreNo()).eq(BtyStorePolicy::getPolicyNo,
                        policy.getPolicyNo()));
        AppointmentPersistence.require(head != null);
        var cancel = verifiedPolicy(policy);
        AppointmentPersistence.require(cancel.equals(snapshot.cancelRule())
                && root.getPolicyVersionNo().equals(preview.getPolicyVersionNo()));
        List<ObservedHead> heads = new ArrayList<>();
        heads.add(new ObservedHead("POLICY", head.getPolicyNo(), head.getVersion(), head.getCurrentPolicyVersionNo()));
        List<WorkOrderTaskSnapshotVO> frozen = new ArrayList<>();
        var scope = new ResourceBookingPort.Scope(root.getTenantNo(), root.getEntityNo(), root.getStoreNo());
        for (var item : snapshot.items()) {
            var line = db.items.selectOne(Wrappers.<BtyAppointmentItem>lambdaQuery()
                    .eq(BtyAppointmentItem::getTenantNo, root.getTenantNo())
                    .eq(BtyAppointmentItem::getEntityNo, root.getEntityNo())
                    .eq(BtyAppointmentItem::getAppointmentNo, root.getAppointmentNo())
                    .eq(BtyAppointmentItem::getAppointmentItemNo, item.appointmentItemNo()));
            AppointmentPersistence.require(line != null && line.getSkuNo().equals(item.skuNo())
                    && line.getDefinitionVersionNo().equals(item.definitionVersionNo())
                    && line.getQty().compareTo(BigDecimal.valueOf(item.qty())) == 0
                    && "COUNT".equals(line.getUom()));
            frozen.add(loadDefinition(scope, item, heads));
        }
        var plan = ResourcePlanContract.decode(preview.getFrozenPlan());
        List<ResourcePlanContract.Demand> demands = new ArrayList<>();
        List<ResourcePlanContract.Qualification> qualifications = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < snapshot.items().size(); i++) {
            var item = snapshot.items().get(i); var def = frozen.get(i);
            for (var rule : def.requirements()) {
                var matches = snapshot.assignments().stream().filter(a ->
                        a.appointmentItemNo().equals(item.appointmentItemNo())
                        && a.requirementNo().equals(rule.requirementNo())).toList();
                AppointmentPersistence.require(matches.size() == rule.qty());
                for (int n = 0; n < matches.size(); n++) {
                    var a = matches.get(n); total++;
                    AppointmentPersistence.require(a.startAt().equals(item.serviceStartAt().plusMinutes(rule.offsetMin())
                            .minusMinutes(def.bufferBeforeMin())) && a.endAt().equals(item.serviceStartAt()
                            .plusMinutes(rule.offsetMin() + rule.durationMin() + def.bufferAfterMin()))
                            && ("STAFF".equals(rule.resourceType()) == (a.personnelNo() != null)));
                    String group = item.appointmentItemNo() + ":" + rule.requirementNo() + ":" + n;
                    demands.add(new ResourcePlanContract.Demand(a.resourceNo(), group, 1,
                            List.of(new ResourcePlanContract.Interval(a.startAt().toInstant(),
                                    a.endAt().toInstant()))));
                    qualifications.add(new ResourcePlanContract.Qualification(a.resourceNo(), group,
                            a.personnelNo() == null ? null : rule.skillGoodsNo(),
                            a.personnelNo() == null ? null : item.skuNo(), a.personnelNo() != null));
                }
            }
        }
        AppointmentPersistence.require(total == snapshot.assignments().size()
                && plan.equals(new ResourcePlanContract.FrozenPlan(demands, qualifications)));
        var identity = root.getCustomerRef() == null ? new VerifiedIdentity("GUEST", root.getGuestRef())
                : new VerifiedIdentity("USER", root.getCustomerRef());
        return new PreparedWorkOrderScope(identity, new FrozenAppointmentScope(snapshot.items(), frozen,
                root.getPolicyVersionNo(), cancel, heads, evidence), snapshot.assignments());
    }
    private WorkOrderTaskSnapshotVO loadDefinition(ResourceBookingPort.Scope scope, AppointmentPlanItemVO item,
            List<ObservedHead> heads) {
            var version = versions.selectOne(Wrappers.<BtyServiceDefinitionVersion>lambdaQuery()
                    .eq(BtyServiceDefinitionVersion::getTenantNo, scope.tenantNo())
                    .eq(BtyServiceDefinitionVersion::getEntityNo, scope.entityNo())
                    .eq(BtyServiceDefinitionVersion::getDefinitionVersionNo, item.definitionVersionNo()));
            AppointmentPersistence.require(version != null && version.getSchemaVersion() == 1 && item.qty() == 1
                    && version.getDurationMinSnapshot() == item.durationMin()
                    && (item.serviceStartAt() == null || item.serviceEndAt().equals(
                            item.serviceStartAt().plusMinutes(item.durationMin()))));
            var definition = definitions.selectOne(Wrappers.<BtyServiceDefinition>lambdaQuery()
                    .eq(BtyServiceDefinition::getTenantNo, scope.tenantNo())
                    .eq(BtyServiceDefinition::getEntityNo, scope.entityNo())
                    .eq(BtyServiceDefinition::getStoreNo, scope.storeNo())
                    .eq(BtyServiceDefinition::getDefinitionNo, version.getDefinitionNo())
                    .eq(BtyServiceDefinition::getSkuNo, item.skuNo()));
            AppointmentPersistence.require(definition != null && "ACTIVE".equals(definition.getStatus()));
            heads.add(new ObservedHead("DEFINITION", definition.getDefinitionNo(), definition.getVersion(),
                    definition.getCurrentDefinitionVersionNo()));
            var rules = requirements.selectList(Wrappers.<BtyServiceRequirement>lambdaQuery()
                    .eq(BtyServiceRequirement::getTenantNo, scope.tenantNo())
                    .eq(BtyServiceRequirement::getEntityNo, scope.entityNo())
                    .eq(BtyServiceRequirement::getDefinitionVersionNo, item.definitionVersionNo())
                    .orderByAsc(BtyServiceRequirement::getSequenceNo, BtyServiceRequirement::getRequirementNo));
            AppointmentPersistence.require(!rules.isEmpty() && rules.size() <= 30);
            var duration = new PublishServiceDefinitionVersionReq.Duration(version.getDurationMinSnapshot(),
                    version.getBufferBeforeMin(), version.getBufferAfterMin());
            var inputs = rules.stream().map(r -> new PublishServiceDefinitionVersionReq.Requirement(r.getRoleCode(),
                    r.getResourceType(), r.getSkillGoodsNo(), r.getQty(), r.getSequenceNo(), r.getOffsetMin(),
                    r.getDurationMin(), r.getSelectionRule())).sorted(Comparator
                    .comparing(PublishServiceDefinitionVersionReq.Requirement::sequenceNo)
                    .thenComparing(PublishServiceDefinitionVersionReq.Requirement::roleCode)
                    .thenComparing(PublishServiceDefinitionVersionReq.Requirement::resourceType)).toList();
            var notes = List.of(db.json.readValue(version.getNotes(), String[].class));
            var steps = List.of(db.json.readValue(version.getStepsTemplate(), WorkOrderStepTemplateVO[].class));
            String hash = IdempotencyScope.requestHash(item.skuNo(), db.json.writeValueAsString(duration),
                    db.json.writeValueAsString(inputs));
            if (!notes.isEmpty()) { hash = IdempotencyScope.requestHash("BEAUTY_DEFINITION_NOTES_V1", hash,
                    db.json.writeValueAsString(notes)); }
            AppointmentPersistence.require(hash.equals(version.getSnapshotHash())
                    && steps.equals(List.of(new WorkOrderStepTemplateVO("SERVICE", 0))));
            return new WorkOrderTaskSnapshotVO(1, item.goodsNo(), item.skuNo(), item.definitionVersionNo(), hash,
                    item.durationMin(), version.getBufferBeforeMin(), version.getBufferAfterMin(), rules.stream()
                            .map(WorkOrderPreparation::requirement).toList(), steps, notes);
    }
    /** No resource is selected: real directory facts and published local definitions freeze an unassigned scope. */
    public PreparedWorkOrderScope unassigned(String entity, String store, VerifiedIdentity identity,
            List<AppointmentItemReq> requested) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("work-order facts require no caller transaction");
        }
        var owner = products.getObject();
        var fact = owner.store(store);
        AppointmentPersistence.require(fact != null && entity.equals(fact.entityNo()) && store.equals(fact.storeNo())
                && "ACTIVE".equals(fact.status()) && fact.catalog() != null
                && entity.equals(fact.catalog().entityNo()) && store.equals(fact.catalog().storeNo()));
        List<ServiceCatalogFactsPort.Service> facts = new ArrayList<>();
        for (var input : requested) {
            var product = owner.service(store, input.skuNo());
            AppointmentPersistence.require(product != null && product.store() != null
                    && entity.equals(product.store().entityNo()) && store.equals(product.store().storeNo())
                    && "ACTIVE".equals(product.store().status()) && input.skuNo().equals(product.skuNo())
                    && product.goodsNo() != null && product.durationMin() > 0 && product.priceMinor() >= 0
                    && product.goodsRevision() != null && product.goodsRevision() >= 0
                    && product.skuRevision() != null && product.skuRevision() >= 0);
            var catalog = product.store().catalog();
            AppointmentPersistence.require(catalog != null && entity.equals(catalog.entityNo())
                    && store.equals(catalog.storeNo()));
            if (catalog.configured()) {
                var entry = catalog.definition().visibleEntry(input.skuNo());
                AppointmentPersistence.require(entry != null && entry.saleableAt(Instant.now(),
                        catalog.definition().timezone()));
            }
            facts.add(product);
        }
        var scope = new ResourceBookingPort.Scope("MAIN", entity, store);
        return db.read(() -> {
            List<AppointmentPlanItemVO> items = new ArrayList<>();
            List<WorkOrderTaskSnapshotVO> frozen = new ArrayList<>();
            List<ObservedHead> heads = new ArrayList<>();
            for (int i = 0; i < requested.size(); i++) {
                var product = facts.get(i);
                var head = definitions.selectOne(Wrappers.<BtyServiceDefinition>lambdaQuery()
                        .eq(BtyServiceDefinition::getTenantNo, scope.tenantNo())
                        .eq(BtyServiceDefinition::getEntityNo, entity).eq(BtyServiceDefinition::getStoreNo, store)
                        .eq(BtyServiceDefinition::getSkuNo, product.skuNo())
                        .eq(BtyServiceDefinition::getStatus, "ACTIVE"));
                AppointmentPersistence.require(head != null && head.getCurrentDefinitionVersionNo() != null);
                for (int n = 0; n < requested.get(i).qty(); n++) {
                    var item = new AppointmentPlanItemVO(null, product.skuNo(), product.goodsNo(),
                            head.getCurrentDefinitionVersionNo(), 1, product.priceMinor(), product.durationMin(),
                            null, null, product.goodsRevision(), product.skuRevision());
                    items.add(item); frozen.add(loadDefinition(scope, item, heads));
                }
            }
            AppointmentPersistence.require(!items.isEmpty() && items.size() <= 10);
            var policy = policies.selectOne(Wrappers.<BtyStorePolicy>lambdaQuery()
                    .eq(BtyStorePolicy::getTenantNo, scope.tenantNo()).eq(BtyStorePolicy::getEntityNo, entity)
                    .eq(BtyStorePolicy::getStoreNo, store));
            String policyNo = null;
            PublishStorePolicyVersionReq.CancellationRule cancel = null;
            if (policy != null) {
                AppointmentPersistence.require("ACTIVE".equals(policy.getStatus()));
                var version = policyVersions.selectOne(Wrappers.<BtyStorePolicyVersion>lambdaQuery()
                        .eq(BtyStorePolicyVersion::getTenantNo, scope.tenantNo())
                        .eq(BtyStorePolicyVersion::getEntityNo, entity)
                        .eq(BtyStorePolicyVersion::getPolicyNo, policy.getPolicyNo())
                        .eq(BtyStorePolicyVersion::getPolicyVersionNo, policy.getCurrentPolicyVersionNo()));
                AppointmentPersistence.require(version != null && version.getSchemaVersion() == 1
                        && !version.getEffectiveAt().isAfter(AppointmentPersistence.now()));
                policyNo = version.getPolicyVersionNo();
                cancel = verifiedPolicy(version);
                heads.add(new ObservedHead("POLICY", policy.getPolicyNo(), policy.getVersion(),
                        policy.getCurrentPolicyVersionNo()));
            }
            return new PreparedWorkOrderScope(identity, new FrozenAppointmentScope(items, frozen, policyNo, cancel,
                    heads, null), List.of(), scope);
        });
    }
    private PublishStorePolicyVersionReq.CancellationRule verifiedPolicy(BtyStorePolicyVersion policy) {
        AppointmentPersistence.require(policy.getSchemaVersion() == 1);
        var cancel = db.json.readValue(policy.getCancelRule(), PublishStorePolicyVersionReq.CancellationRule.class);
        var modes = new TreeSet<>(Arrays.asList(db.json.readValue(policy.getAllowedModes(), String[].class)));
        String hash = IdempotencyScope.requestHash(policy.getEffectiveAt().atOffset(ZoneOffset.UTC).toString(),
                policy.getBookingAheadDays().toString(), cancel.freeCancelBeforeMinutes().toString(),
                cancel.allowCustomerCancel().toString(), String.join(",", modes));
        AppointmentPersistence.require(hash.equals(policy.getSnapshotHash()));
        return cancel;
    }
    /** A new CREATE cannot acquire resources under a definition or policy that is no longer selected locally. */
    public void verifyCreateLocked(AppointmentMatching.Matched match) {
        var scope = match.scope();
        var selected = new TreeMap<String, AppointmentPlanItemVO>();
        for (var item : match.snapshot().items()) {
            var version = versions.selectOne(Wrappers.<BtyServiceDefinitionVersion>lambdaQuery()
                    .eq(BtyServiceDefinitionVersion::getTenantNo, scope.tenantNo())
                    .eq(BtyServiceDefinitionVersion::getEntityNo, scope.entityNo())
                    .eq(BtyServiceDefinitionVersion::getDefinitionVersionNo, item.definitionVersionNo()));
            AppointmentPersistence.require(version != null);
            var previous = selected.putIfAbsent(version.getDefinitionNo(), item);
            AppointmentPersistence.require(previous == null
                    || previous.definitionVersionNo().equals(item.definitionVersionNo()));
        }
        for (var entry : selected.entrySet()) {
            var head = definitions.selectScopedForUpdate(scope.entityNo(), scope.storeNo(), entry.getKey());
            var item = entry.getValue();
            AppointmentPersistence.require(head != null && "ACTIVE".equals(head.getStatus())
                    && item.skuNo().equals(head.getSkuNo())
                    && item.definitionVersionNo().equals(head.getCurrentDefinitionVersionNo()));
        }
        var version = policyVersions.selectOne(Wrappers.<BtyStorePolicyVersion>lambdaQuery()
                .eq(BtyStorePolicyVersion::getTenantNo, scope.tenantNo())
                .eq(BtyStorePolicyVersion::getEntityNo, scope.entityNo())
                .eq(BtyStorePolicyVersion::getPolicyVersionNo, match.policyVersionNo()));
        AppointmentPersistence.require(version != null);
        var head = policies.selectScopedForUpdate(scope.entityNo(), scope.storeNo(), version.getPolicyNo());
        AppointmentPersistence.require(head != null && "ACTIVE".equals(head.getStatus())
                && match.policyVersionNo().equals(head.getCurrentPolicyVersionNo()));
    }
    public void verifyLocked(PreparedWorkOrderScope scope) {
        var binding = scope.scope();
        for (var observed : scope.frozen().observedHeads().stream().distinct()
                .sorted(Comparator.comparing(ObservedHead::kind).thenComparing(ObservedHead::businessNo)).toList()) {
            if ("DEFINITION".equals(observed.kind())) {
                var row = definitions.selectScopedForUpdate(binding.entityNo(), binding.storeNo(),
                        observed.businessNo());
                AppointmentPersistence.require(row != null && row.getVersion() == observed.version()
                        && "ACTIVE".equals(row.getStatus())
                        && Objects.equals(row.getCurrentDefinitionVersionNo(), observed.selectedVersionNo()));
            } else {
                var row = policies.selectScopedForUpdate(binding.entityNo(), binding.storeNo(), observed.businessNo());
                AppointmentPersistence.require(row != null && row.getVersion() == observed.version()
                        && "ACTIVE".equals(row.getStatus())
                        && Objects.equals(row.getCurrentPolicyVersionNo(), observed.selectedVersionNo()));
            }
        }
    }
    static WorkOrderRequirementVO requirement(BtyServiceRequirement r) {
        return new WorkOrderRequirementVO(r.getRequirementNo(), r.getRoleCode(), r.getResourceType(),
                r.getSkillGoodsNo(), r.getQty(), r.getSequenceNo(), r.getOffsetMin(), r.getDurationMin(),
                        r.getSelectionRule());
    }
}
