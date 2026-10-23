package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.AppointmentPlanItemVO;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.WorkOrderRequirementVO;
import ai.neargo.shop.beauty.dto.WorkOrderStepTemplateVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentArrangement;
import ai.neargo.shop.beauty.entity.BtyServiceDefinition;
import ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion;
import ai.neargo.shop.beauty.entity.BtyServiceRequirement;
import ai.neargo.shop.beauty.entity.BtyStorePolicy;
import ai.neargo.shop.beauty.entity.BtyStorePolicyVersion;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ReceptionIdentity.VerifiedIdentity;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Reconstructs original scope from immutable local definitions and the successful owner-bound arrangement. */
public final class WorkOrderPreparation {
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
            BeautyMappers.StorePolicyVersionMapper policyVersions) {
        this.db = db; this.appointments = appointments; this.definitions = definitions; this.versions = versions;
        this.requirements = requirements; this.policies = policies; this.policyVersions = policyVersions;
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
        var cancel = db.json.readValue(policy.getCancelRule(), PublishStorePolicyVersionReq.CancellationRule.class);
        var modes = new TreeSet<>(Arrays.asList(db.json.readValue(policy.getAllowedModes(), String[].class)));
        String policyHash = IdempotencyScope.requestHash(policy.getEffectiveAt().atOffset(ZoneOffset.UTC).toString(),
                policy.getBookingAheadDays().toString(), cancel.freeCancelBeforeMinutes().toString(),
                cancel.allowCustomerCancel().toString(), String.join(",", modes));
        AppointmentPersistence.require(policyHash.equals(policy.getSnapshotHash())
                && cancel.equals(snapshot.cancelRule())
                && root.getPolicyVersionNo().equals(preview.getPolicyVersionNo()));
        List<ObservedHead> heads = new ArrayList<>();
        heads.add(new ObservedHead("POLICY", head.getPolicyNo(), head.getVersion(), head.getCurrentPolicyVersionNo()));
        List<WorkOrderTaskSnapshotVO> frozen = new ArrayList<>();
        for (var item : snapshot.items()) {
            var version = versions.selectOne(Wrappers.<BtyServiceDefinitionVersion>lambdaQuery()
                    .eq(BtyServiceDefinitionVersion::getTenantNo, root.getTenantNo())
                    .eq(BtyServiceDefinitionVersion::getEntityNo, root.getEntityNo())
                    .eq(BtyServiceDefinitionVersion::getDefinitionVersionNo, item.definitionVersionNo()));
            AppointmentPersistence.require(version != null && version.getSchemaVersion() == 1 && item.qty() == 1
                    && version.getDurationMinSnapshot() == item.durationMin()
                    && item.serviceEndAt().equals(item.serviceStartAt().plusMinutes(item.durationMin())));
            var definition = definitions.selectOne(Wrappers.<BtyServiceDefinition>lambdaQuery()
                    .eq(BtyServiceDefinition::getTenantNo, root.getTenantNo())
                    .eq(BtyServiceDefinition::getEntityNo, root.getEntityNo())
                    .eq(BtyServiceDefinition::getStoreNo, root.getStoreNo())
                    .eq(BtyServiceDefinition::getDefinitionNo, version.getDefinitionNo())
                    .eq(BtyServiceDefinition::getSkuNo, item.skuNo()));
            AppointmentPersistence.require(definition != null && "ACTIVE".equals(definition.getStatus()));
            heads.add(new ObservedHead("DEFINITION", definition.getDefinitionNo(), definition.getVersion(),
                    definition.getCurrentDefinitionVersionNo()));
            var rules = requirements.selectList(Wrappers.<BtyServiceRequirement>lambdaQuery()
                    .eq(BtyServiceRequirement::getTenantNo, root.getTenantNo())
                    .eq(BtyServiceRequirement::getEntityNo, root.getEntityNo())
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
            frozen.add(new WorkOrderTaskSnapshotVO(1, item.goodsNo(), item.skuNo(), item.definitionVersionNo(), hash,
                    item.durationMin(), version.getBufferBeforeMin(), version.getBufferAfterMin(), rules.stream()
                            .map(WorkOrderPreparation::requirement).toList(), steps, notes));
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
    public void verifyLocked(PreparedWorkOrderScope scope) {
        var binding = scope.frozen().holdEvidence().binding();
        for (var observed : scope.frozen().observedHeads()) {
            if ("DEFINITION".equals(observed.kind())) {
                var row = definitions.selectScopedForUpdate(binding.entityNo(), binding.storeNo(),
                        observed.businessNo());
                AppointmentPersistence.require(row != null && row.getVersion() == observed.version()
                        && "ACTIVE".equals(row.getStatus())
                        && Objects.equals(row.getCurrentDefinitionVersionNo(), observed.selectedVersionNo()));
            } else {
                var row = policies.selectScopedForUpdate(binding.entityNo(), binding.storeNo(), observed.businessNo());
                AppointmentPersistence.require(row != null && row.getVersion() == observed.version()
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
