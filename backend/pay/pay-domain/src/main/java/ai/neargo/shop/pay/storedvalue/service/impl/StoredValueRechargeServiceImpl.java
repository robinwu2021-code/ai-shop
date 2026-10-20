package ai.neargo.shop.pay.storedvalue.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeWriter;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreateRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** New promises require a real core source. Existing received promises survive changes to live activity flags. */
@Service
@Transactional(transactionManager = "payTxManager", propagation = Propagation.NOT_SUPPORTED)
public class StoredValueRechargeServiceImpl implements StoredValueRechargeService {
    private final StoredValueRechargeWriter writer;
    private final RechargeSourcePort sources;
    private final PayEntitlementFeatureGate features;
    public StoredValueRechargeServiceImpl(StoredValueRechargeWriter writer, RechargeSourcePort sources,
                                         PayEntitlementFeatureGate features) {
        this.writer = writer; this.sources = sources; this.features = features;
    }
    @Override public RechargeView create(EntitlementContext ctx, String entity, CreateRecharge c) {
        main(ctx); var prior = writer.existing(ctx, entity, c); if (prior != null) return prior;
        features.requireStoredValue();
        var source = sources.resolve(ctx, c.sourceRef());
        if (!source.context().equals(ctx) || !entity.equals(source.permit().entityNo())
                || !c.permit().equals(source.permit()) || !Instant.now().isBefore(source.expiresAt())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return writer.create(ctx, entity, c, source);
    }
    @Override public RechargeView submit(EntitlementContext ctx, String entity, SubmitRecharge c) {
        main(ctx); return writer.submit(ctx, entity, c);
    }
    @Override public RechargeView receive(EntitlementContext ctx, String entity, ReceiveRecharge c) {
        main(ctx); return writer.receive(ctx, entity, c);
    }
    @Override public RechargeView cancel(EntitlementContext ctx, String entity, CancelRecharge c) {
        main(ctx);
        var source = writer.exists(ctx, entity, c.rechargeNo()) ? null : sources.byRechargeNo(ctx, c.rechargeNo());
        if (source != null && (!entity.equals(source.permit().entityNo())
                || !c.rechargeNo().equals(source.rechargeNo()))) throw BizException.of(ErrorCode.NOT_FOUND);
        return writer.cancel(ctx, entity, c, source);
    }
    @Override public RechargeView query(EntitlementContext ctx, String entity, String rechargeNo) {
        main(ctx); return writer.query(ctx, entity, rechargeNo);
    }
    private static void main(EntitlementContext ctx) {
        if (!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
}
