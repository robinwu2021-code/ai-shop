package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.spi.resource.ResourceCommandFactsPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads exact original journal truth after current operator checks; caller-provided result fields are never used. */
@Component
public class ResourceCommandFactsPortImpl implements ResourceCommandFactsPort {
    private final ResourceOccupationGuard occupations;
    private final MerchantMappers.ResourceCommandMapper commands;
    private final MerchantOperatorAccessPort access;
    public ResourceCommandFactsPortImpl(ResourceOccupationGuard occupations, MerchantMappers.ResourceCommandMapper commands,
            MerchantOperatorAccessPort access) {
        this.occupations = occupations;
        this.commands = commands;
        this.access = access;
    }
    @Override
    public Lookup lookup(ResourceSourceAuthorityPort.Request request) {
        if (request == null) { throw new IllegalArgumentException("original resource request required"); }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("original command facts require no caller transaction");
        }
        var b = request.binding();
        if (!access.allowed(new MerchantOperatorAccessPort.Query(b.tenantNo(), b.entityNo(), b.storeNo(),
                request.operatorNo(), request.realm(), request.subjectKind(), "biz:order:view"))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return DataScopeContext.executeWithoutScope(() -> {
            occupations.requireCommon(new ResourceCommandScopeRow(b.tenantNo(), b.entityNo(), b.storeNo()));
            var row = commands.selectOriginalFact(new ResourceCommandScopeRow(b.tenantNo(), b.entityNo(), b.storeNo()),
                    b.commandNo());
            if (row == null) { return new Lookup(null); }
            if (!"BOOKING_V1".equals(row.commandKind())) { throw BizException.of(ErrorCode.CONFLICT); }
            var actual = new ResourceSourceAuthorityPort.Binding(row.tenantNo(), row.entityNo(), row.storeNo(),
                    row.commandNo(), row.sourceType(), row.sourceNo(), row.sourceRevision(), row.action(),
                    row.commandHash(), ResourcePlanContract.hash(row.frozenPayload()));
            if (!actual.equals(b)) { throw BizException.of(ErrorCode.CONFLICT); }
            Status status = Status.valueOf(row.status());
            String hash = status == Status.ACCEPTED ? null : ResourceCommandFactsPort.fingerprint(actual, status,
                    row.revision(), row.resultPayload(), row.rejectionCode());
            return new Lookup(new Fact(actual, status, row.revision(), row.resultPayload(), row.rejectionCode(), hash));
        });
    }
}
