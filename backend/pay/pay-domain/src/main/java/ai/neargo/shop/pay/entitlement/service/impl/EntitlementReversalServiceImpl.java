package ai.neargo.shop.pay.entitlement.service.impl;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.unavailable;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.service.EntitlementReversalService;
import ai.neargo.shop.pay.entitlement.service.EntitlementReversalWriter;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseReversalMapper;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.OrderBenefitReverse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseReceipt;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.ReversalView;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Source-owner verification is outside the pay resource transaction; UNKNOWN never credits an asset. */
@Service
public class EntitlementReversalServiceImpl implements EntitlementReversalService {
    private final EntUseReversalMapper reversals;
    private final EntUsageMapper usages;
    private final EntUseIntentMapper intents;
    private final EntitlementReversalWriter writer;
    private final ObjectProvider<SourceDecisionPort> decisions;
    private final ObjectMapper json;
    public EntitlementReversalServiceImpl(EntUseReversalMapper reversals, EntUsageMapper usages,
            EntUseIntentMapper intents, EntitlementReversalWriter writer,
            ObjectProvider<SourceDecisionPort> decisions, ObjectMapper json) {
        this.reversals = reversals; this.usages = usages; this.intents = intents;
        this.writer = writer; this.decisions = decisions; this.json = json;
    }
    @Override public ReversalView queryReverse(EntitlementContext ctx, String entity, String reverseNo) {
        ContractChecks.id(entity); ContractChecks.id(reverseNo);
        var row = reversals.find(ctx.tenantNo(), reverseNo);
        var usage = row == null ? null : usages.selectOne(Wrappers.<EntUsage>lambdaQuery()
                .eq(EntUsage::getTenantNo, ctx.tenantNo()).eq(EntUsage::getUsageNo, row.getUsageNo())
                .eq(EntUsage::getEntityNo, entity));
        if (usage == null) return new ReversalView(reverseNo, State.NOT_FOUND, null, null);
        var receipt = writer.view(row);
        return new ReversalView(reverseNo, receipt.status(), receipt.usageNo(), receipt.occurredAt());
    }
    @Override public UseReceipt reverse(EntitlementContext ctx, OrderBenefitReverse c) {
        if (!"MAIN".equals(ctx.tenantNo())) unavailable();
        var prior = reversals.find(ctx.tenantNo(), c.reverseNo());
        if (prior != null) {
            if (!prior.getPayloadHash().equals(digest(json.writeValueAsString(c)))) conflict();
            return writer.view(prior);
        }
        if (c.assetKind() != AssetKind.ORDER_COUPON || !"CORE".equals(c.sourceRef().sourceSystem())
                || !"ORDER_REFUND".equals(c.sourceRef().sourceType())
                || !c.usageNo().equals(c.sourceRef().sourceLineNo())
                || c.sourceRef().unitIndex() != null || c.sourceRef().entitlementIndex() != null) conflict();
        var usage = usages.selectOne(Wrappers.<EntUsage>lambdaQuery().eq(EntUsage::getTenantNo, ctx.tenantNo())
                .eq(EntUsage::getUsageNo, c.usageNo()));
        if (usage == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var intent = intents.find(ctx.tenantNo(), usage.getOperationNo());
        if (intent == null || !"COMMITTED".equals(intent.getStatus())
                || !"ORDER_COUPON".equals(usage.getAssetKind())) conflict();
        var original = json.readValue(intent.getCommandSnapshot(), Prepared.class);
        var owner = decisions.getIfAvailable(); if (owner == null) unavailable();
        var fact = owner.query(ctx, c.sourceRef());
        if (fact == null) unavailable();
        if (fact.status() == SourceDecisionPort.State.UNKNOWN || fact.status() == SourceDecisionPort.State.PENDING) {
            return new UseReceipt(c.reverseNo(), State.PROCESSING, null, null);
        }
        if (fact.status() != SourceDecisionPort.State.COMMITTED || !c.sourceRef().equals(fact.source())
                || !c.decisionRef().equals(fact.decision()) || !usage.getUserNo().equals(fact.userNo())
                || !usage.getEntityNo().equals(fact.entityNo()) || !usage.getStoreNo().equals(fact.storeNo())
                || !usage.getCurrency().equals(fact.currency()) || !Objects.equals(c.amountMinor(), fact.amountMinor())
                || !original.source().orderSnapshotHash().equals(fact.snapshotHash())) conflict();
        return writer.apply(ctx, c, usage.getOperationNo());
    }
}
