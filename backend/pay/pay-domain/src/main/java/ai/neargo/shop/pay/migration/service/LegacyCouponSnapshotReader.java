package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLegacyCouponOperationMapper;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetState;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Owned;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Snapshot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Owner was verified before this lock-free pay RR transaction; amounts, reservations and history share one snapshot. */
@Service
public class LegacyCouponSnapshotReader {
    private final EntInstanceMapper instances;
    private final EntLegacyCouponOperationMapper operations;
    private final PayEntitlementFeatureGate features;
    private final ObjectMapper json;
    public LegacyCouponSnapshotReader(EntInstanceMapper instances,EntLegacyCouponOperationMapper operations,
            PayEntitlementFeatureGate features,ObjectMapper json) {this.instances=instances;this.operations=operations;this.features=features;this.json=json;}
    @Transactional(transactionManager="payTxManager",propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true)
    public Snapshot read(EntitlementContext ctx,Owned command) {
        var asset=instances.selectOne(Wrappers.<EntInstance>lambdaQuery().eq(EntInstance::getTenantNo,ctx.tenantNo())
                .eq(EntInstance::getInstanceNo,command.targetInstanceNo()).eq(EntInstance::getUserNo,command.userNo())
                .eq(EntInstance::getEntityNo,command.entityNo()).eq(EntInstance::getKind,"ORDER_COUPON"));
        if(asset==null || asset.getTotalUnits()==null || asset.getRemainingUnits()==null || asset.getReservedUnits()==null)
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var rule=json.readValue(asset.getRuleSnapshot(),EntitlementDefinition.class);
        if(!"COUNT".equals(rule.usageModel()) || !"MERCHANT".equals(rule.funder()) || !"CNY".equals(rule.currency())
                || !java.util.Objects.equals(rule.totalUnits(),asset.getTotalUnits())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        AssetState state=AssetState.valueOf(asset.getStatus());
        var from=asset.getEffectiveFrom()==null?null:asset.getEffectiveFrom().toInstant(ZoneOffset.UTC);
        var until=asset.getEffectiveUntil()==null?null:asset.getEffectiveUntil().toInstant(ZoneOffset.UTC);
        if(state!=AssetState.REVOKED && state!=AssetState.FROZEN) {
            if(asset.getRemainingUnits()==0) state=AssetState.EXHAUSTED;
            else if(until!=null && !Instant.now().isBefore(until)) state=AssetState.EXPIRED;
        }
        boolean usable=features.legacyCouponEnabled() && state==AssetState.ACTIVE && from!=null && until!=null
                && !Instant.now().isBefore(from) && Instant.now().isBefore(until) && asset.getRemainingUnits()>asset.getReservedUnits();
        var last=operations.lastCommittedAt(ctx.tenantNo(),command.userNo(),command.entityNo(),asset.getInstanceNo());
        return new Snapshot(asset.getInstanceNo(),rule,asset.getTotalUnits(),asset.getRemainingUnits(),asset.getReservedUnits(),
                state,from,until,last==null?null:last.toInstant(ZoneOffset.UTC),usable);
    }
}
