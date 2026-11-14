package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.MallException;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Mall products and SKUs (/ops/biz-mall catalog, products, SKU versions, SKU off-sale). Same
 * facade and authority as the industry catalog; split by resource, not by endpoint count.
 */
@RestController
public final class CommerceMallProductHttpController extends MallHttpSupport {
    private final CatalogFacade catalog;

    public CommerceMallProductHttpController(
            CatalogFacade catalog, MallHttpContextPort context, MallHttpEnvelopePort envelope) {
        super(context, envelope);
        this.catalog = catalog;
    }

    @GetMapping("/ops/biz-mall/catalog")
    public Object overview() {
        return ok(catalog.overview(context.operator("commerce:catalog:read")));
    }

    /** Flat category tree, ordered by (level, sortOrder, categoryCode). */
    @GetMapping("/ops/biz-mall/categories")
    public Object categories() {
        return ok(catalog.categories(context.operator("commerce:catalog:read")));
    }

    @PostMapping("/ops/biz-mall/categories")
    public Object createCategory(@RequestBody String body) {
        return ok(
                catalog.saveCategory(
                        context.operator("commerce:catalog:edit"),
                        json.read(body, CategoryDraft.class)));
    }

    public record CategoryUpdate(
            String parentCode,
            Map<String, String> names,
            String icon,
            int sortOrder,
            boolean enabled,
            long expectedVersion,
            String reason) {}

    /** The path code wins; category codes are immutable. */
    @PutMapping("/ops/biz-mall/categories/{categoryCode}")
    public Object category(@PathVariable("categoryCode") String no, @RequestBody String body) {
        var d = json.read(body, CategoryUpdate.class);
        return ok(
                catalog.saveCategory(
                        context.operator("commerce:catalog:edit"),
                        new CategoryDraft(
                                code(no),
                                d.parentCode(),
                                d.names(),
                                d.icon(),
                                d.sortOrder(),
                                d.enabled(),
                                d.expectedVersion(),
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

    @PostMapping("/ops/biz-mall/skus/{skuNo}/off-sale")
    public Object stopSku(@PathVariable("skuNo") String no, @RequestBody String body) {
        return stop(context.operator("commerce:catalog:publish"), no, body, SaleTarget.SKU);
    }
}
