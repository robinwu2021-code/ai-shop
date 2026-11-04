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

    @GetMapping("/ops/biz-mall/catalog")
    public Object overview() {
        return ok(catalog.overview(context.operator("commerce:catalog:read")));
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

    @GetMapping("/ops/biz-mall/products")
    public Object products() {
        return ok(catalog.overview(context.operator("commerce:catalog:read")).products());
    }

    @PostMapping("/ops/biz-mall/products")
    public Object createProduct(@RequestBody String body) {
        return ok(
                catalog.saveProduct(
                        context.operator("commerce:catalog:edit"),
                        json.read(body, ProductDraft.class)));
    }

    public record ProductUpdate(
            String categoryCode,
            ProductKind kind,
            String name,
            String description,
            List<String> allowedBusinessTypes,
            int sortOrder,
            long expectedVersion,
            String reason) {}

    @PutMapping("/ops/biz-mall/products/{productNo}")
    public Object product(@PathVariable("productNo") String no, @RequestBody String body) {
        var d = json.read(body, ProductUpdate.class);
        return ok(
                catalog.saveProduct(
                        context.operator("commerce:catalog:edit"),
                        new ProductDraft(
                                code(no),
                                d.categoryCode(),
                                d.kind(),
                                d.name(),
                                d.description(),
                                d.allowedBusinessTypes(),
                                d.sortOrder(),
                                d.expectedVersion(),
                                d.reason())));
    }

    public record RefundPolicyInput(String unusedDigital, String usedDigital) {}

    public record SkuRequest(
            String skuNo,
            String skuVersionNo,
            String title,
            Money listPrice,
            Money price,
            List<String> allowedBusinessTypes,
            RefundPolicyInput refundPolicy,
            List<SkuComponent> components,
            int maxPerOrder,
            long expectedVersion,
            String reason) {}

    @PostMapping("/ops/biz-mall/products/{productNo}/skus")
    public Object sku(@PathVariable("productNo") String product, @RequestBody String body) {
        var d = json.read(body, SkuRequest.class);
        var p = d.refundPolicy();
        if (p == null
                || !Set.of("FULL_ORIGINAL_ALLOCATION", "MANUAL_ONLY").contains(p.unusedDigital())
                || !"MANUAL_ONLY".equals(p.usedDigital()))
            throw new MallException("INVALID_REFUND_POLICY");
        String policy =
                "{\"schema\":\"commerce-refund-v1\",\"unusedDigital\":\""
                        + p.unusedDigital()
                        + "\",\"usedDigital\":\"MANUAL_ONLY\"}";
        return ok(
                catalog.saveSku(
                        context.operator("commerce:catalog:edit"),
                        new SkuDraft(
                                code(product),
                                d.skuNo(),
                                d.skuVersionNo(),
                                d.title(),
                                d.listPrice(),
                                d.price(),
                                d.allowedBusinessTypes(),
                                policy,
                                d.components(),
                                d.maxPerOrder(),
                                d.expectedVersion(),
                                d.reason())));
    }

    @GetMapping("/ops/biz-mall/sku-versions/{versionNo}")
    public Object skuVersion(@PathVariable("versionNo") String version) {
        return ok(
                catalog.overview(context.operator("commerce:catalog:read")).skuVersions().stream()
                        .filter(v -> code(version).equals(v.draft().skuVersionNo()))
                        .findFirst()
                        .orElseThrow(() -> new MallException("NOT_FOUND")));
    }

    public record PublishSku(
            String operationNo, long expectedVersion, long expectedSkuVersion, String reason) {}

    @PostMapping("/ops/biz-mall/sku-versions/{versionNo}/publish")
    public Object publishSku(@PathVariable("versionNo") String no, @RequestBody String body) {
        var d = json.read(body, PublishSku.class);
        return ok(
                catalog.publishSku(
                        context.operator("commerce:catalog:publish"),
                        new SkuPublicationCommand(
                                d.operationNo(),
                                code(no),
                                d.expectedVersion(),
                                d.expectedSkuVersion(),
                                d.reason())));
    }

    public record Stop(String operationNo, long expectedVersion, String reason) {}

    private Object stop(String no, String body, SaleTarget target) {
        var d = json.read(body, Stop.class);
        return ok(
                catalog.stopSale(
                        context.operator("commerce:catalog:publish"),
                        new StopSaleCommand(
                                d.operationNo(),
                                target,
                                code(no),
                                d.expectedVersion(),
                                d.reason())));
    }

    @PostMapping("/ops/industry-plans/{planNo}/off-sale")
    public Object stopPlan(@PathVariable("planNo") String no, @RequestBody String body) {
        return stop(no, body, SaleTarget.PLAN);
    }

    @PostMapping("/ops/biz-mall/skus/{skuNo}/off-sale")
    public Object stopSku(@PathVariable("skuNo") String no, @RequestBody String body) {
        return stop(no, body, SaleTarget.SKU);
    }
}
