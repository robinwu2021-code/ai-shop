package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.MallException;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Portable catalog usecase invocation and wire translation; host supplies only authority/envelope.
 */
@RestController
public final class CommerceCatalogHttpController extends MallHttpSupport {
    private final CatalogFacade catalog;

    public CommerceCatalogHttpController(
            CatalogFacade catalog, MallHttpContextPort context, MallHttpEnvelopePort envelope) {
        super(context, envelope);
        this.catalog = catalog;
    }

    @GetMapping("/ops/business-types")
    public Object types() {
        return ok(catalog.overview(context.operator("commerce:catalog:read")).businessTypes());
    }

    @PostMapping("/ops/business-types")
    public Object createType(@RequestBody String body) {
        return ok(
                catalog.saveBusinessType(
                        context.operator("commerce:catalog:edit"),
                        json.read(body, BusinessTypeDraft.class)));
    }

    @PutMapping("/ops/business-types/{typeCode}")
    public Object updateType(@PathVariable("typeCode") String type, @RequestBody String body) {
        var d = json.read(body, TypeUpdate.class);
        return ok(
                catalog.saveBusinessType(
                        context.operator("commerce:catalog:edit"),
                        new BusinessTypeDraft(
                                code(type),
                                d.name(),
                                d.sortOrder(),
                                d.enabled(),
                                d.expectedVersion(),
                                d.reason())));
    }

    public record TypeUpdate(
            String name, int sortOrder, boolean enabled, long expectedVersion, String reason) {}

    @GetMapping("/ops/business-ends")
    public Object ends() {
        return ok(catalog.overview(context.operator("commerce:catalog:read")).businessEnds());
    }

    @GetMapping("/ops/business-ends/deployment-options")
    public Object deploymentOptions() {
        return ok(catalog.deploymentOptions(context.operator("commerce:catalog:read")));
    }

    public record EndRequest(
            String code,
            String entitlementCode,
            String name,
            EndScope scope,
            List<String> allowedBusinessTypes,
            ReleaseStatus releaseStatus,
            boolean saleEnabled,
            boolean bootstrapRequired,
            boolean enabled,
            String deploymentNo,
            long expectedVersion,
            String reason) {}

    @PostMapping("/ops/business-ends")
    public Object end(@RequestBody String body) {
        var actor = context.operator("commerce:catalog:edit");
        var d = json.read(body, EndRequest.class);
        BusinessEndDeploymentOption binding = null;
        if (d.deploymentNo() != null) {
            binding =
                    catalog.deploymentOptions(actor).stream()
                            .filter(v -> v.deploymentNo().equals(d.deploymentNo()))
                            .findFirst()
                            .orElseThrow(() -> new MallException("DEPLOYMENT_UNAVAILABLE"));
            if (!binding.code().equals(d.code())
                    || !binding.entitlementCode().equals(d.entitlementCode())
                    || d.allowedBusinessTypes() == null
                    || !binding.allowedBusinessTypes().containsAll(d.allowedBusinessTypes()))
                throw new MallException("INVALID_REQUEST");
        }
        if (d.releaseStatus() == ReleaseStatus.VERIFIED && binding == null)
            throw new MallException("DEPLOYMENT_UNAVAILABLE");
        return ok(
                catalog.saveBusinessEnd(
                        actor,
                        new BusinessEndDraft(
                                d.code(),
                                d.entitlementCode(),
                                d.name(),
                                d.scope(),
                                d.allowedBusinessTypes(),
                                d.releaseStatus(),
                                d.saleEnabled(),
                                d.bootstrapRequired(),
                                d.enabled(),
                                binding == null ? null : binding.clientId(),
                                binding == null ? null : binding.routePolicyRef(),
                                binding == null ? null : binding.acceptanceRef(),
                                d.expectedVersion(),
                                d.reason())));
    }

    @GetMapping("/ops/industry-plans")
    public Object plans() {
        return ok(catalog.overview(context.operator("commerce:catalog:read")).planDefinitions());
    }

    public record PlanRequest(
            String planVersionNo,
            String typeCode,
            PlanTier tier,
            String displayName,
            String summary,
            List<String> entitlementCodes,
            String policyVersion,
            long expectedVersion,
            String reason) {}

    @PostMapping("/ops/industry-plans/{planNo}/versions")
    public Object plan(@PathVariable("planNo") String no, @RequestBody String body) {
        var d = json.read(body, PlanRequest.class);
        return ok(
                catalog.savePlan(
                        context.operator("commerce:catalog:edit"),
                        new PlanDraft(
                                code(no),
                                d.planVersionNo(),
                                d.typeCode(),
                                d.tier(),
                                d.displayName(),
                                d.summary(),
                                d.entitlementCodes(),
                                d.policyVersion(),
                                d.expectedVersion(),
                                d.reason())));
    }

    public record IndustryPublish(
            String operationNo,
            String freePlanVersionNo,
            String proPlanVersionNo,
            String chainPlanVersionNo,
            List<String> candidateSkuVersionNos,
            long expectedCatalogVersion,
            Map<String, Long> expectedPlanVersions,
            Map<String, Long> expectedSkuVersions,
            Map<String, Long> expectedPlanDefinitionVersions,
            Map<String, Long> expectedSkuDefinitionVersions,
            String reason) {}

    @PostMapping("/ops/business-types/{typeCode}/plan-publications")
    public Object publish(@PathVariable("typeCode") String type, @RequestBody String body) {
        var d = json.read(body, IndustryPublish.class);
        return ok(
                catalog.publishIndustry(
                        context.operator("commerce:catalog:publish"),
                        new IndustryPublicationCommand(
                                d.operationNo(),
                                code(type),
                                d.freePlanVersionNo(),
                                d.proPlanVersionNo(),
                                d.chainPlanVersionNo(),
                                d.candidateSkuVersionNos(),
                                d.expectedCatalogVersion(),
                                d.expectedPlanVersions(),
                                d.expectedSkuVersions(),
                                d.expectedPlanDefinitionVersions(),
                                d.expectedSkuDefinitionVersions(),
                                d.reason())));
    }

    public record Stop(String operationNo, long expectedVersion, String reason) {}

    private Object stop(ActorContext operator, String no, String body, SaleTarget target) {
        var d = json.read(body, Stop.class);
        return ok(
                catalog.stopSale(
                        operator,
                        new StopSaleCommand(
                                d.operationNo(),
                                target,
                                code(no),
                                d.expectedVersion(),
                                d.reason())));
    }

    @PostMapping("/ops/industry-plans/{planNo}/off-sale")
    public Object stopPlan(@PathVariable("planNo") String no, @RequestBody String body) {
        return stop(context.operator("commerce:catalog:publish"), no, body, SaleTarget.PLAN);
    }
}
