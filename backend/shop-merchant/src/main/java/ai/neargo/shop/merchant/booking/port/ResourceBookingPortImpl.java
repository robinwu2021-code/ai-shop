package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.ResourceBookingService;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.Binding;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Thin trusted server bridge; preserves original identity and bytes without exposing a generic public endpoint. */
@Component
public class ResourceBookingPortImpl implements ResourceBookingPort {
    private final ResourceOccupationGuard occupations;
    private final ResourceCommandService commands;
    private final ResourceBookingService bookings;
    private final MerchantMappers.BookingHoldMapper holds;
    private final MerchantMappers.BookingOwnerMapper owners;
    private final MerchantMappers.ResourceCommandMapper journal;

    public ResourceBookingPortImpl(ResourceOccupationGuard occupations, ResourceCommandService commands, ResourceBookingService bookings,
            MerchantMappers.BookingHoldMapper holds, MerchantMappers.BookingOwnerMapper owners,
            MerchantMappers.ResourceCommandMapper journal) {
        this.occupations = occupations;
        this.commands = commands;
        this.bookings = bookings;
        this.holds = holds;
        this.owners = owners;
        this.journal = journal;
    }
    @Override
    public Receipt accept(Actor actor, Accept request) {
        authenticate(actor, request.scope());
        var t = request.target();
        return receipt(commands.accept(new ResourceCommandService.Accept(scope(request.scope()),
                ResourceCommandService.Action.valueOf(request.action()), request.key(), request.sourceType(),
                request.sourceNo(), request.expectedOwnerVersion(), request.sourceRevision(), t == null ? null
                : new ResourceCommandService.Target(t.holdNo(), t.holdVersion(), t.leaseRevision()),
                request.frozenPayload())));
    }
    @Override
    public Optional<Receipt> query(Actor actor, Scope scope, String commandNo) {
        authenticate(actor, scope);
        return commands.find(scope(scope), commandNo).map(this::receipt);
    }
    @Override
    public Receipt execute(Actor actor, Scope scope, String commandNo) {
        authenticate(actor, scope);
        return receipt(bookings.execute(scope(scope), commandNo));
    }
    @Override
    public Hold queryHold(Actor actor, Binding binding, String holdNo) {
        authenticate(actor, new Scope(binding.tenantNo(), binding.entityNo(), binding.storeNo()));
        return reconcileHold(binding, holdNo);
    }
    @Override
    public Optional<Receipt> reconcile(Binding binding) {
        noTransaction();
        return DataScopeContext.executeWithoutScope(() -> {
            var scope = new ResourceCommandScopeRow(binding.tenantNo(), binding.entityNo(), binding.storeNo());
            occupations.requireCommon(scope);
            var row = journal.selectCommand(scope, binding.commandNo());
            if (row == null) { return Optional.empty(); }
            if (!"BOOKING_V1".equals(row.getCommandKind())) { throw BizException.of(ErrorCode.CONFLICT); }
            var owner = owners.selectOwner(scope, row.getOwnerNo());
            if (owner == null) { throw new IllegalStateException("original owner binding unavailable"); }
            var actual = new Binding(row.getTenantNo(), row.getEntityNo(), row.getStoreNo(), row.getCommandNo(),
                    owner.getSourceType(), owner.getSourceNo(), row.getSourceRevision(), row.getAction(),
                    row.getPayloadHash(), ResourcePlanContract.hash(row.getFrozenPayload()));
            if (!actual.equals(binding)) { throw BizException.of(ErrorCode.CONFLICT); }
            var target = row.getTargetHoldNo() == null ? null : new Target(row.getTargetHoldNo(),
                    row.getExpectedHoldVersion(), row.getExpectedLeaseRevision());
            return Optional.of(new Receipt(actual, row.getOwnerNo(), row.getStatus(), row.getVersion(),
                    row.getExpectedOwnerVersion(), target, row.getFrozenPayload(), row.getResultPayload(),
                    row.getRejectionCode()));
        });
    }
    @Override
    public Hold reconcileHold(Binding binding, String holdNo) {
        var receipt = reconcile(binding).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        return DataScopeContext.executeWithoutScope(() -> {
            var scope = new ResourceCommandScopeRow(binding.tenantNo(), binding.entityNo(), binding.storeNo());
            var hold = holds.selectOwnedHold(scope, receipt.ownerNo(), holdNo);
            var owner = owners.selectBySource(scope, binding.sourceType(), binding.sourceNo());
            if (hold == null || owner == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
            return new Hold(hold.getHoldNo(), hold.getStatus(), hold.getVersion(), hold.getLeaseRevision(),
                    hold.getSourceRevision(), owner.getOwnerVersion(), hold.getCurrentPlanHash());
        });
    }
    private void authenticate(Actor actor, Scope scope) {
        noTransaction();
        var user = SecurityUtils.requireUser();
        if (actor == null || !user.userNo().equals(actor.operatorNo()) || !user.tenantNo().equals(scope.tenantNo())
                || !user.realm().name().equals(actor.realm()) || !user.subjectKind().name().equals(actor.subjectKind())
                || !("CONSUMER".equals(actor.realm()) && "USR".equals(actor.subjectKind())
                || "MERCHANT".equals(actor.realm()) && SetKinds.allowed(actor.subjectKind()))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
    private void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("booking bridge requires no caller transaction");
        }
    }
    private ResourceCommandService.Scope scope(Scope value) {
        return new ResourceCommandService.Scope(value.tenantNo(), value.entityNo(), value.storeNo());
    }
    private Receipt receipt(ResourceCommandService.Receipt value) {
        var s = value.scope();
        var binding = new Binding(s.tenantNo(), s.entityNo(), s.storeNo(), value.commandNo(), value.sourceType(),
                value.sourceNo(), value.sourceRevision(), value.action().name(), value.payloadHash(),
                ResourcePlanContract.hash(value.frozenPayload()));
        var t = value.target();
        return new Receipt(binding, value.ownerNo(), value.status().name(), value.version(),
                value.expectedOwnerVersion(), t == null ? null
                : new Target(t.holdNo(), t.holdVersion(), t.leaseRevision()), value.frozenPayload(),
                value.resultPayload(), value.rejectionCode());
    }
    private static final class SetKinds {
        private static boolean allowed(String kind) { return "USR".equals(kind) || "MCH".equals(kind); }
    }
}
