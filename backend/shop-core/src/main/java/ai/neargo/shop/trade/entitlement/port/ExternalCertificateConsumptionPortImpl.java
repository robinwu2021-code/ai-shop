package ai.neargo.shop.trade.entitlement.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort;
import ai.neargo.shop.spi.entitlement.ExternalCertificateEvidencePort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.entitlement.entity.OrdExternalCertificateUse;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdChannelImport;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.ExternalCertificateUseMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelImportMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.order.entity.OrdAfterSaleItem;
import ai.neargo.shop.trade.order.mapper.AfterSaleItemMapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Completed original goods/service facts authorize separate external certificates; no fake local
 * asset, holder or payment is created.
 */
@Component
@ConditionalOnProperty(name = "shop.entitlement.external-certificate.enabled", havingValue = "true")
public class ExternalCertificateConsumptionPortImpl implements ExternalCertificateConsumptionPort {
    private final ExternalCertificateUseMapper uses;
    private final AfterSaleMapper afterSales;
    private final AfterSaleItemMapper returnLines;
    private final SubOrderMapper subs;
    private final OrderItemMapper items;
    private final ChannelImportMapper channels;
    private final StoreDirectoryPort stores;
    private final ObjectProvider<ExternalCertificateEvidencePort> evidence;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ExternalCertificateConsumptionPortImpl(
            ExternalCertificateUseMapper uses,
            AfterSaleMapper afterSales,
            AfterSaleItemMapper returnLines,
            SubOrderMapper subs,
            OrderItemMapper items,
            ChannelImportMapper channels,
            StoreDirectoryPort stores,
            ObjectProvider<ExternalCertificateEvidencePort> evidence,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.uses = uses;
        this.afterSales = afterSales;
        this.returnLines = returnLines;
        this.subs = subs;
        this.items = items;
        this.channels = channels;
        this.stores = stores;
        this.evidence = evidence;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    private ExternalCertificateEvidencePort evidence() {
        var providers = evidence.orderedStream().limit(2).toList();
        if (providers.size() != 1)
            throw new UnsupportedOperationException(
                    "Original external certificate proof owner unavailable");
        return providers.getFirst();
    }

    @Override
    public Authorization authorize(
            Scope scope, Business business, String previewRef, String previewHash) {
        outside();
        scope(scope);
        check(business != null && business.orderItemId() > 0);
        text(business.subOrderNo(), 32);
        text(previewRef, 32);
        hash(previewHash);
        var proof = evidence().verified(scope, previewRef, previewHash);
        check(
                proof != null
                        && scope.equals(proof.scope())
                        && previewRef.equals(proof.previewRef())
                        && previewHash.equals(proof.previewHash())
                        && proof.validUntil() != null
                        && proof.validUntil().isAfter(Instant.now())
                        && proof.remainingQuantity() != null
                        && proof.remainingQuantity().signum() > 0);
        hash(proof.certificateIdentityHash());
        text(proof.originalSkuRef(), 128);
        text(proof.unit(), 32);
        var card = stores.cards(List.of(scope.storeNo())).get(scope.storeNo());
        check(card != null && card.active() && scope.entityNo().equals(card.entityNo()));
        return atomic(
                () -> {
                    var source =
                            source(
                                    scope,
                                    business,
                                    proof.originalSkuRef(),
                                    proof.externalOrderRef());
                    var existing =
                            uses.selectOne(
                                    Wrappers.<OrdExternalCertificateUse>query()
                                            .eq("tenant_no", scope.tenantNo())
                                            .eq("entity_no", scope.entityNo())
                                            .eq("store_no", scope.storeNo())
                                            .eq("preview_ref", previewRef));
                    if (existing != null) {
                        check(
                                existing.getPreviewHash().equals(previewHash)
                                        && business.subOrderNo().equals(existing.getSubOrderNo())
                                        && business.orderItemId() == existing.getOrderItemId());
                        return view(existing);
                    }
                    check(
                            proof.validUntil().isAfter(Instant.now())
                                    && proof.unit().equals(source.unit())
                                    && proof.remainingQuantity().compareTo(source.quantity()) >= 0);
                    var active =
                            uses.selectList(
                                    Wrappers.<OrdExternalCertificateUse>query()
                                            .eq("tenant_no", scope.tenantNo())
                                            .eq("entity_no", scope.entityNo())
                                            .eq("store_no", scope.storeNo())
                                            .eq("sub_order_no", business.subOrderNo())
                                            .eq("order_item_id", business.orderItemId())
                                            .eq("purpose", "USE")
                                            .in("status", "RESERVED", "PENDING", "CONFIRMED")
                                            .last("LIMIT 1001"));
                    check(active.size() <= 1000);
                    BigDecimal consumed = BigDecimal.ZERO;
                    for (var row : active)
                        if (!"RESERVED".equals(row.getStatus())
                                || row.getExpiresAt().isAfter(now()))
                            consumed = consumed.add(row.getQuantity());
                    // This first owner supports one complete actual original item, never an
                    // inferred coupon-to-physical conversion.
                    check(consumed.signum() == 0);
                    var row = new OrdExternalCertificateUse();
                    stamp(row, scope);
                    row.setAuthorizationNo(BizKey.next("ECUA"));
                    row.setPurpose("USE");
                    row.setSubOrderNo(business.subOrderNo());
                    row.setOrderItemId(business.orderItemId());
                    row.setPreviewRef(previewRef);
                    row.setPreviewHash(previewHash);
                    row.setCertificateIdentityHash(proof.certificateIdentityHash());
                    row.setSourceHash(PricingSnapshotHash.of(source));
                    row.setSourceSnapshot(json.writeValueAsString(source));
                    row.setQuantity(source.quantity());
                    row.setUnit(source.unit());
                    row.setStatus("RESERVED");
                    var expires =
                            proof.validUntil().isBefore(Instant.now().plusSeconds(30))
                                    ? proof.validUntil()
                                    : Instant.now().plusSeconds(30);
                    row.setExpiresAt(LocalDateTime.ofInstant(expires, ZoneOffset.UTC));
                    uses.insert(row);
                    return view(row);
                });
    }

    private record Source(
            Business business,
            String orderNo,
            String skuNo,
            BigDecimal quantity,
            String unit,
            long subVersion,
            long itemVersion,
            long completedAt,
            String externalOrderRef) {}

    private Source source(Scope scope, Business business, String sku, String externalOrder) {
        var sub =
                subs.selectOne(
                        Wrappers.<OrdSubOrder>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("sub_order_no", business.subOrderNo())
                                .last("FOR UPDATE"));
        check(
                sub != null
                        && OrdSubOrder.COMPLETED.equals(sub.getStatus())
                        && sub.getCompletedAt() != null
                        && sub.getCompletedAt() > 0
                        && sub.getCompletedAt() <= Instant.now().toEpochMilli()
                        && sub.getVersion() != null);
        var item =
                items.selectOne(
                        Wrappers.<OrdItem>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("sub_order_no", business.subOrderNo())
                                .eq("order_no", sub.getOrderNo())
                                .eq("id", business.orderItemId()));
        check(
                item != null
                        && item.getVersion() != null
                        && sku.equals(item.getSkuNo())
                        && item.getSaleQuantity() != null
                        && item.getSaleQuantity().signum() > 0
                        && item.getQuantityUnit() != null
                        && !item.getQuantityUnit().isBlank()
                        && item.getSaleQuantity().stripTrailingZeros().scale() <= 6
                        && (item.getRefundedQty() != null && item.getRefundedQty() == 0));
        if (externalOrder != null) {
            var original =
                    channels.selectOne(
                            Wrappers.<OrdChannelImport>query()
                                    .eq("tenant_no", scope.tenantNo())
                                    .eq("entity_no", scope.entityNo())
                                    .eq("store_no", scope.storeNo())
                                    .eq("sub_order_no", business.subOrderNo())
                                    .eq("order_no", sub.getOrderNo()));
            check(
                    original != null
                            && "ACCEPTED".equals(original.getStatus())
                            && externalOrder.equals(original.getExternalOrderRef()));
        }
        return new Source(
                business,
                sub.getOrderNo(),
                item.getSkuNo(),
                item.getSaleQuantity(),
                item.getQuantityUnit(),
                sub.getVersion(),
                item.getVersion(),
                sub.getCompletedAt(),
                externalOrder);
    }

    @Override
    public Authorization freeze(Scope scope, String authorizationRef) {
        outside();
        scope(scope);
        return atomic(
                () -> {
                    var before =
                            uses.selectOne(
                                    Wrappers.<OrdExternalCertificateUse>query()
                                            .eq("tenant_no", scope.tenantNo())
                                            .eq("entity_no", scope.entityNo())
                                            .eq("store_no", scope.storeNo())
                                            .eq("authorization_no", authorizationRef));
                    check(before != null);
                    if (SetHolder.TERMINAL.contains(before.getStatus())
                            || "PENDING".equals(before.getStatus())) return view(before);
                    Object current;
                    if ("REVERSE".equals(before.getPurpose())) {
                        var reversal = json.readValue(before.getSourceSnapshot(), Reversal.class);
                        current =
                                returnProof(
                                        scope,
                                        before.getOriginalAuthorizationNo(),
                                        reversal.afterSaleNo());
                        row(scope, before.getOriginalAuthorizationNo());
                    } else {
                        var original = json.readValue(before.getSourceSnapshot(), Source.class);
                        current =
                                source(
                                        scope,
                                        original.business(),
                                        original.skuNo(),
                                        original.externalOrderRef());
                    }
                    var row = row(scope, authorizationRef);
                    if (SetHolder.TERMINAL.contains(row.getStatus())
                            || "PENDING".equals(row.getStatus())) return view(row);
                    check(
                            "RESERVED".equals(row.getStatus())
                                    && row.getExpiresAt().isAfter(now())
                                    && row.getSourceHash().equals(PricingSnapshotHash.of(current)));
                    uses.update(
                            Wrappers.<OrdExternalCertificateUse>update()
                                    .eq("tenant_no", scope.tenantNo())
                                    .eq("id", row.getId())
                                    .eq("status", "RESERVED")
                                    .set("status", "PENDING")
                                    .setSql("version=version+1"));
                    row.setStatus("PENDING");
                    return view(row);
                });
    }

    @Override
    public Authorization reconcile(Scope scope, String authorizationRef) {
        outside();
        scope(scope);
        var proof = evidence().decision(scope, authorizationRef);
        check(
                proof != null
                        && scope.equals(proof.scope())
                        && authorizationRef.equals(proof.authorizationRef()));
        return atomic(
                () -> {
                    var preliminary = peek(scope, authorizationRef);
                    if ("REVERSE".equals(preliminary.getPurpose()))
                        row(scope, preliminary.getOriginalAuthorizationNo());
                    var row = row(scope, authorizationRef);
                    if (SetHolder.TERMINAL.contains(row.getStatus())) return view(row);
                    check(
                            "PENDING".equals(row.getStatus())
                                    && row.getCertificateIdentityHash()
                                            .equals(proof.certificateIdentityHash())
                                    && row.getSourceHash().equals(proof.sourceHash())
                                    && row.getQuantity().compareTo(proof.quantity()) == 0
                                    && row.getUnit().equals(proof.unit()));
                    if (!"CONFIRMED".equals(proof.status()) && !"REJECTED".equals(proof.status()))
                        return view(row);
                    text(proof.originalReceiptRef(), 256);
                    String decision = BizKey.next("ECUD");
                    uses.update(
                            Wrappers.<OrdExternalCertificateUse>update()
                                    .eq("tenant_no", scope.tenantNo())
                                    .eq("id", row.getId())
                                    .eq("status", "PENDING")
                                    .set("status", proof.status())
                                    .set("decision_ref", decision)
                                    .set("original_receipt_ref", proof.originalReceiptRef())
                                    .setSql("version=version+1"));
                    if ("REVERSE".equals(row.getPurpose()) && "CONFIRMED".equals(proof.status())) {
                        check(
                                uses.update(
                                                Wrappers.<OrdExternalCertificateUse>update()
                                                        .eq("tenant_no", scope.tenantNo())
                                                        .eq("entity_no", scope.entityNo())
                                                        .eq("store_no", scope.storeNo())
                                                        .eq(
                                                                "authorization_no",
                                                                row.getOriginalAuthorizationNo())
                                                        .eq("status", "CONFIRMED")
                                                        .set("status", "REVERSED")
                                                        .setSql("version=version+1"))
                                        == 1);
                    }
                    row.setStatus(proof.status());
                    row.setDecisionRef(decision);
                    return view(row);
                });
    }

    private record Reversal(
            String originalAuthorizationRef,
            String originalSourceHash,
            String afterSaleNo,
            String subOrderNo,
            long itemId,
            int billingQuantity,
            long receivedAt,
            long afterSaleVersion,
            long lineVersion) {}

    private OrdExternalCertificateUse peek(Scope scope, String authorizationRef) {
        var row =
                uses.selectOne(
                        Wrappers.<OrdExternalCertificateUse>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("authorization_no", authorizationRef));
        check(row != null);
        return row;
    }

    private Reversal returnProof(Scope scope, String authorizationRef, String afterSaleNo) {
        var original = peek(scope, authorizationRef);
        var sub =
                subs.selectOne(
                        Wrappers.<OrdSubOrder>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("sub_order_no", original.getSubOrderNo())
                                .last("FOR UPDATE"));
        check(sub != null);
        var receipt =
                afterSales.selectOne(
                        Wrappers.<OrdAfterSale>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("entity_no", scope.entityNo())
                                .eq("sub_order_no", sub.getSubOrderNo())
                                .eq("order_no", sub.getOrderNo())
                                .eq("after_sale_no", afterSaleNo)
                                .last("FOR UPDATE"));
        if (receipt == null
                || !OrdAfterSale.RETURN_REFUND.equals(receipt.getType())
                || !OrdAfterSale.REFUNDED.equals(receipt.getStatus())
                || receipt.getReturnReceivedAt() == null
                || receipt.getReturnReceivedAt() <= 0
                || receipt.getReturnReceivedAt() > Instant.now().toEpochMilli())
            throw new UnsupportedOperationException(
                    "Completed original physical/business return receipt unavailable");
        var selected =
                returnLines.selectOne(
                        Wrappers.<OrdAfterSaleItem>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("after_sale_no", afterSaleNo)
                                .eq("sub_order_no", sub.getSubOrderNo())
                                .eq("item_id", original.getOrderItemId()));
        var item =
                items.selectOne(
                        Wrappers.<OrdItem>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("sub_order_no", sub.getSubOrderNo())
                                .eq("order_no", sub.getOrderNo())
                                .eq("id", original.getOrderItemId()));
        if (selected == null
                || item == null
                || selected.getQty() == null
                || item.getQty() == null
                || selected.getQty() <= 0
                || !selected.getQty().equals(item.getQty()))
            throw new UnsupportedOperationException(
                    "Only an actual complete original-item physical return is supported");
        check(receipt.getVersion() != null && selected.getVersion() != null);
        return new Reversal(
                authorizationRef,
                original.getSourceHash(),
                afterSaleNo,
                sub.getSubOrderNo(),
                original.getOrderItemId(),
                selected.getQty(),
                receipt.getReturnReceivedAt(),
                receipt.getVersion(),
                selected.getVersion());
    }

    @Override
    public Authorization authorizeReversal(
            Scope scope, String authorizationRef, String afterSaleNo) {
        outside();
        scope(scope);
        text(authorizationRef, 32);
        text(afterSaleNo, 32);
        return atomic(
                () -> {
                    var proof = returnProof(scope, authorizationRef, afterSaleNo);
                    var original = row(scope, authorizationRef);
                    var existing =
                            uses.selectOne(
                                    Wrappers.<OrdExternalCertificateUse>query()
                                            .eq("tenant_no", scope.tenantNo())
                                            .eq("entity_no", scope.entityNo())
                                            .eq("store_no", scope.storeNo())
                                            .eq("original_authorization_no", authorizationRef)
                                            .eq("reversal_ref", afterSaleNo));
                    if (existing != null) {
                        check(existing.getSourceHash().equals(PricingSnapshotHash.of(proof)));
                        if ("RESERVED".equals(existing.getStatus())
                                && !existing.getExpiresAt().isAfter(now())) {
                            var expires = now().plusSeconds(30);
                            uses.update(
                                    Wrappers.<OrdExternalCertificateUse>update()
                                            .eq("tenant_no", scope.tenantNo())
                                            .eq("id", existing.getId())
                                            .eq("status", "RESERVED")
                                            .set("expires_at", expires)
                                            .setSql("version=version+1"));
                            existing.setExpiresAt(expires);
                        }
                        return view(existing);
                    }
                    check("CONFIRMED".equals(original.getStatus()));
                    var pending =
                            uses.selectCount(
                                    Wrappers.<OrdExternalCertificateUse>query()
                                            .eq("tenant_no", scope.tenantNo())
                                            .eq("original_authorization_no", authorizationRef)
                                            .eq("purpose", "REVERSE")
                                            .in("status", "RESERVED", "PENDING", "CONFIRMED"));
                    check(pending == 0);
                    var row = new OrdExternalCertificateUse();
                    stamp(row, scope);
                    row.setAuthorizationNo(BizKey.next("ECUR"));
                    row.setOriginalAuthorizationNo(authorizationRef);
                    row.setPurpose("REVERSE");
                    row.setReversalRef(afterSaleNo);
                    row.setSubOrderNo(original.getSubOrderNo());
                    row.setOrderItemId(original.getOrderItemId());
                    row.setPreviewRef(
                            "REVR"
                                    + PricingSnapshotHash.of(List.of(authorizationRef, afterSaleNo))
                                            .substring(0, 28));
                    row.setPreviewHash(original.getPreviewHash());
                    row.setCertificateIdentityHash(original.getCertificateIdentityHash());
                    row.setSourceHash(PricingSnapshotHash.of(proof));
                    row.setSourceSnapshot(json.writeValueAsString(proof));
                    row.setQuantity(original.getQuantity());
                    row.setUnit(original.getUnit());
                    row.setStatus("RESERVED");
                    row.setExpiresAt(now().plusSeconds(30));
                    uses.insert(row);
                    return view(row);
                });
    }

    @Override
    public Authorization query(Scope scope, String authorizationRef) {
        scope(scope);
        return atomic(() -> view(row(scope, authorizationRef)));
    }

    private static class SetHolder {
        static final Set<String> TERMINAL = Set.of("CONFIRMED", "REJECTED", "REVERSED");
    }

    private OrdExternalCertificateUse row(Scope scope, String reference) {
        text(reference, 32);
        var row =
                uses.selectOne(
                        Wrappers.<OrdExternalCertificateUse>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("authorization_no", reference)
                                .last("FOR UPDATE"));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private Authorization view(OrdExternalCertificateUse row) {
        return new Authorization(
                row.getAuthorizationNo(),
                new Scope(row.getTenantNo(), row.getEntityNo(), row.getStoreNo()),
                new Business(row.getSubOrderNo(), row.getOrderItemId()),
                row.getPreviewRef(),
                row.getPreviewHash(),
                row.getCertificateIdentityHash(),
                row.getSourceHash(),
                row.getQuantity(),
                row.getUnit(),
                row.getStatus(),
                row.getExpiresAt().toInstant(ZoneOffset.UTC),
                row.getDecisionRef(),
                row.getOriginalAuthorizationNo(),
                row.getPurpose());
    }

    private <T> T atomic(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(() -> tx.execute(status -> work.get()));
    }

    private static void stamp(OrdExternalCertificateUse row, Scope scope) {
        row.setTenantNo(scope.tenantNo());
        row.setEntityNo(scope.entityNo());
        row.setStoreNo(scope.storeNo());
        row.setCreatedAt(now());
        row.setUpdatedAt(now());
        row.setCreatedBy("SYSTEM");
        row.setUpdatedBy("SYSTEM");
        row.setDeleted(0);
        row.setVersion(0L);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static void scope(Scope scope) {
        check(scope != null && "MAIN".equals(scope.tenantNo()));
        text(scope.entityNo(), 32);
        text(scope.storeNo(), 32);
    }

    private static void text(String s, int max) {
        check(s != null && !s.isBlank() && s.length() <= max);
    }

    private static void hash(String s) {
        check(s != null && s.matches("[a-f0-9]{64}"));
    }

    private static void check(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Certificate evidence calls need external transaction boundary");
    }
}
