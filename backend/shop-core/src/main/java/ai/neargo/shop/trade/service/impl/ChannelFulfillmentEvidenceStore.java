package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.RetailChannelFulfillmentSealed;
import ai.neargo.shop.trade.entity.OrdChannelFulfillmentEvidence;
import ai.neargo.shop.trade.entity.OrdChannelImport;
import ai.neargo.shop.trade.entity.OrdIndustryFulfillment;
import ai.neargo.shop.trade.mapper.ChannelFulfillmentEvidenceMapper;
import ai.neargo.shop.trade.policy.ChannelFulfillmentDeltaPolicy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stable owner proof is appended only inside the original final primary commit; legacy terminal
 * actions are never backfilled.
 */
@Service
@ConditionalOnProperty(name = "shop.trade.channel.enabled", havingValue = "true")
public final class ChannelFulfillmentEvidenceStore implements IndustryChannelFulfillmentSourcePort {
    private final IndustryChannelOrderStore source;
    private final ChannelFulfillmentEvidenceMapper rows;

    public ChannelFulfillmentEvidenceStore(
            IndustryChannelOrderStore source, ChannelFulfillmentEvidenceMapper rows) {
        this.source = source;
        this.rows = rows;
    }

    public Optional<RetailChannelFulfillmentSealed> seal(
            OrdIndustryFulfillment original,
            IndustryFulfillmentPort.Fulfill command,
            InventoryDemandPlanPort.Receipt inventory,
            Map<Long, BigDecimal> before) {
        require(TransactionSynchronizationManager.isActualTransactionActive());
        var ref = command.reference();
        require(
                "RETAIL".equals(ref.source().sourceSystem())
                        && "CHANNEL_ORDER".equals(ref.source().sourceType())
                        && List.of(
                                        "PARTIAL",
                                        "VERIFIED",
                                        "BUYER_CONFIRM_PENDING",
                                        "HANDOVER_COMPLETE")
                                .contains(original.getStatus())
                        && List.of("PICKUP", "DELIVERY_HANDOVER").contains(command.kind())
                        && original.getInventoryReceiptJson() != null
                        && original.getRequestHash().equals(PricingSnapshotHash.of(command)));
        var q =
                new Query(
                        new Scope(
                                ref.scope().tenantNo(),
                                ref.scope().entityNo(),
                                ref.scope().storeNo()),
                        ref.source().sourceNo(),
                        ref.orderNo(),
                        original.getFulfillmentNo(),
                        "1");
        var accepted =
                source.imports.selectOne(
                        Wrappers.<OrdChannelImport>query()
                                .eq("tenant_no", q.scope().tenantNo())
                                .eq("entity_no", q.scope().entityNo())
                                .eq("store_no", q.scope().storeNo())
                                .eq("source_system", "RETAIL")
                                .eq("source_no", q.sourceOrderRef())
                                .eq("source_version", ref.source().sourceVersion())
                                .eq("source_hash", original.getSourceHash())
                                .eq("order_no", ref.orderNo())
                                .eq("sub_order_no", ref.subOrderNo())
                                .eq("status", "ACCEPTED")
                                .last("FOR UPDATE"));
        require(
                accepted != null
                        && accepted.getImportRef() != null
                        && !accepted.getImportRef().isBlank()
                        && accepted.getAllocationsJson() != null
                        && command.planHash().equals(inventory.planHash())
                        && original.getPlanHash().equals(command.planHash())
                        && original.getInventoryReceiptJson()
                                .equals(source.json.writeValueAsString(inventory)));
        validate(q);
        var allocations =
                source.json
                        .readValue(
                                accepted.getAllocationsJson(),
                                IndustryChannelOrderCommitter.StoredAcceptance.class)
                        .allocations();
        var lines = new ArrayList<Line>();
        for (var target : command.lines()) {
            var matches =
                    allocations.stream()
                            .filter(
                                    a ->
                                            a.orderItemId() == target.orderItemId()
                                                    && a.componentRef()
                                                            .equals(target.sourceComponentRef()))
                            .toList();
            require(matches.size() == 1);
            var allocation = matches.getFirst();
            var delta =
                    ChannelFulfillmentDeltaPolicy.delta(
                            before.get(target.orderItemId()),
                            target.cumulativeQty(),
                            new BigDecimal(allocation.physicalQuantity()),
                            target.unit(),
                            allocation.unit());
            if (delta.signum() > 0) {
                require("MAIN".equals(allocation.externalKind()));
                lines.add(
                        new Line(
                                allocation.sourceLineRef(),
                                allocation.componentRef(),
                                allocation.coreItemRef(),
                                delta.stripTrailingZeros().toPlainString(),
                                target.unit()));
            }
        }
        // An idempotent no-op has no new physical fact to publish.
        if (lines.isEmpty()) return Optional.empty();
        var prior = find(q);
        var inventoryHash = PricingSnapshotHash.of(inventory);
        if (prior != null) {
            require(
                    prior.getRequestHash().equals(original.getRequestHash())
                            && prior.getInventoryReceiptHash().equals(inventoryHash));
            checked(q, prior);
            return Optional.empty();
        }
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var data =
                new Data(
                        q,
                        "PICKUP".equals(command.kind()) ? "PICKED_UP" : "HANDED_OVER",
                        lines,
                        now,
                        BizKey.next("OCF"),
                        original.getSourceHash(),
                        original.getRequestHash(),
                        command.planHash(),
                        inventoryHash,
                        accepted.getImportRef());
        var row = new OrdChannelFulfillmentEvidence();
        source.audit(row, q.scope());
        row.setCreatedBy(command.actorNo());
        row.setUpdatedBy(command.actorNo());
        row.setEntityNo(q.scope().entityNo());
        row.setStoreNo(q.scope().storeNo());
        row.setSourceOrderRef(q.sourceOrderRef());
        row.setOrderNo(q.coreOrderRef());
        row.setSubOrderNo(ref.subOrderNo());
        row.setFulfillmentNo(q.fulfillmentNo());
        row.setRevisionNo(q.revision());
        row.setReceiptNo(data.receiptNo());
        row.setStage(data.stage());
        row.setSourceHash(data.originalSourceHash());
        row.setRequestHash(data.requestHash());
        row.setPlanHash(data.planHash());
        row.setInventoryReceiptHash(inventoryHash);
        row.setContentHash(PricingSnapshotHash.of(data));
        row.setPayloadSnapshot(source.json.writeValueAsString(data));
        row.setOccurredAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        require(rows.insert(row) == 1);
        return Optional.of(
                new RetailChannelFulfillmentSealed(
                        q.scope().tenantNo(),
                        q.scope().entityNo(),
                        q.scope().storeNo(),
                        accepted.getImportRef(),
                        q.sourceOrderRef(),
                        q.coreOrderRef(),
                        q.fulfillmentNo(),
                        q.revision(),
                        data.stage(),
                        data.receiptNo(),
                        row.getContentHash(),
                        data.occurredAt()));
    }

    @Override
    public Facts read(Query query) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source lookup outside caller transaction required");
        validate(query);
        return source.query(
                () -> {
                    var row = find(query);
                    if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    var data = checked(query, row);
                    var now = Instant.now();
                    return new Facts(
                            data,
                            row.getContentHash(),
                            now,
                            now.plusSeconds(30),
                            PricingSnapshotHash.of(List.of(data, now)));
                });
    }

    private OrdChannelFulfillmentEvidence find(Query q) {
        return rows.selectOne(
                Wrappers.<OrdChannelFulfillmentEvidence>query()
                        .eq("tenant_no", q.scope().tenantNo())
                        .eq("entity_no", q.scope().entityNo())
                        .eq("store_no", q.scope().storeNo())
                        .eq("source_order_ref", q.sourceOrderRef())
                        .eq("order_no", q.coreOrderRef())
                        .eq("fulfillment_no", q.fulfillmentNo())
                        .eq("revision_no", q.revision()));
    }

    private Data checked(Query q, OrdChannelFulfillmentEvidence row) {
        var data = source.json.readValue(row.getPayloadSnapshot(), Data.class);
        require(
                q.equals(data.query())
                        && data.importRef() != null
                        && !data.importRef().isBlank()
                        && row.getContentHash().equals(PricingSnapshotHash.of(data))
                        && row.getReceiptNo().equals(data.receiptNo())
                        && row.getStage().equals(data.stage())
                        && row.getSourceHash().equals(data.originalSourceHash())
                        && row.getRequestHash().equals(data.requestHash())
                        && row.getPlanHash().equals(data.planHash())
                        && row.getInventoryReceiptHash().equals(data.inventoryReceiptHash())
                        && row.getOccurredAt().toInstant(ZoneOffset.UTC).equals(data.occurredAt())
                        && !data.lines().isEmpty()
                        && !data.occurredAt().isAfter(Instant.now()));
        return data;
    }

    private static void validate(Query q) {
        if (q == null
                || q.scope() == null
                || !"MAIN".equals(q.scope().tenantNo())
                || !"1".equals(q.revision())) throw BizException.of(ErrorCode.BAD_REQUEST);
        for (var id :
                new String[] {
                    q.scope().entityNo(),
                    q.scope().storeNo(),
                    q.sourceOrderRef(),
                    q.coreOrderRef(),
                    q.fulfillmentNo()
                })
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
                throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void require(boolean ok) {
        if (!ok) throw BizException.of(ErrorCode.CONFLICT);
    }
}
