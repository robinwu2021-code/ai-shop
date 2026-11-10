package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Demand;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Plan;
import ai.neargo.shop.merchant.policy.ResourcePhysicalProtectionPlan.PhysicalPlan;
import ai.neargo.shop.merchant.service.ResourceCommandService.Execution;
import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.QualificationWitness;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthority;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.QualifiedGroup;

/** Locked local facts only; the later processor must bind complete demands/qualifications to frozen source intent. */
public interface ResourceAdmissionService {
    OccupationAdmission lockAndValidateOccupation(ResourceCommandService.OccupationExecution execution,
            StartAuthority source,
            PhysicalPlan physical);

    record OccupationAdmission(Plan originalPlan, List<ResourceFact> resources,
            List<QualificationWitness> qualifications) {
        public OccupationAdmission { resources = List.copyOf(resources); qualifications = List.copyOf(qualifications); }
    }
    /**
     * Acquires personnel then canonical resource roots within the original command transaction.
     * Returns a normalized plan after whole-bucket schedule/qualification checks, without reserving capacity.
     * Maintenance writers must use the same roots; this is neither source authorization nor availability evidence.
     */
    Plan lockAndValidate(Execution execution, List<Demand> demands, List<Qualification> qualifications);

    record WorkflowAdmission(List<ResourceFact> resources, List<QualifiedGroup> qualifications) { }

    /** Locks the union of old/new roots once before validating current replacement/resumption eligibility. */
    WorkflowAdmission lockWorkflow(ResourceCommandScopeRow scope, Set<String> resourceNos,
            String resourcePlanJson, List<AcceptedStaffRef> roster);

    /** Pure SELF qualification observation never acquires a command credential or reserves capacity. */
    ResourceQualificationPort.Observation observeQualifications(
            ResourceQualificationPort.Query query);

    /**
     * Complete industry policy per staff group: required projects, or a sole explicit exemption with null goods/SKU.
     * Only trusted industry policy may choose an exemption; the later processor must freeze and bind that choice.
     */
    record Qualification(String resourceNo, String sourceLineRef, String goodsNo, String skuNo, boolean required) {
        /** Project-based industries default to requiring the supplied qualification, never an implicit exemption. */
        public Qualification(String resourceNo, String sourceLineRef, String goodsNo, String skuNo) {
            this(resourceNo, sourceLineRef, goodsNo, skuNo, true);
        }
    }
}
