package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.policy.ResourcePlanCodec;
import ai.neargo.shop.merchant.policy.ResourcePlanCodec.FrozenPlan;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.merchant.service.ResourceCommandService.Execution;
import ai.neargo.shop.merchant.service.ResourceCommandService.Receipt;
import ai.neargo.shop.merchant.service.ResourceCommandService.Scope;
import ai.neargo.shop.merchant.service.ResourceCommandService.Status;
import ai.neargo.shop.merchant.service.ResourceSourceAuthorizationService;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.Binding;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.Request;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.State;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A missing or uncertain source never authorizes; source IO and resource writes have separate boundaries. */
@Service
public class ResourceSourceAuthorizationServiceImpl implements ResourceSourceAuthorizationService {
    private final ResourceCommandService commands;
    private final Map<String, ResourceSourceAuthorityPort> authorities;
    private final Clock clock;
    private final DataSource dataSource;

    public ResourceSourceAuthorizationServiceImpl(ResourceCommandService commands,
            List<ResourceSourceAuthorityPort> authorities, ObjectProvider<Clock> clocks,
            @Qualifier("dataSource") DataSource dataSource) {
        this.commands = commands;
        this.dataSource = dataSource;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
        var registry = new HashMap<String, ResourceSourceAuthorityPort>();
        for (var authority : authorities) {
            String type = authority.sourceType();
            if (type == null || !type.matches("[A-Z][A-Z0-9_]{0,31}")
                    || registry.putIfAbsent(type, authority) != null) {
                throw new IllegalArgumentException("unique valid resource source authority required");
            }
        }
        this.authorities = Map.copyOf(registry);
    }

    @Override
    public Resolution authorize(Scope scope, String commandNo) {
        return resolve(scope, commandNo, false);
    }

    @Override
    public Resolution recover(Scope scope, String commandNo) {
        return resolve(scope, commandNo, true);
    }

    private Resolution resolve(Scope scope, String commandNo, boolean recovery) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("source authority IO requires no caller transaction");
        }
        Receipt command = commands.find(scope, commandNo)
                .orElseThrow(() -> new IllegalStateException("original resource command unavailable"));
        if (command.status() != Status.ACCEPTED) {
            throw new IllegalStateException("query original terminal resource result instead of freezing again");
        }
        var plan = ResourcePlanCodec.decode(command.frozenPayload());
        var binding = binding(command);
        var authority = authorities.get(command.sourceType());
        if (authority == null) {
            throw new IllegalStateException("no resource source authority configured");
        }
        var operator = SecurityUtils.requireUser();
        var request = new Request(binding, operator.userNo(), operator.realm().name(), operator.subjectKind().name());
        var decision = recovery ? authority.query(request) : authority.freeze(request);
        if (decision == null) {
            throw new IllegalStateException("source returned no authoritative decision");
        }
        checkBinding(binding, decision.binding());
        return new Resolution(decision, decision.state() == State.AUTHORIZED
                ? new Permit(this, decision, plan, request) : null);
    }

    @Override
    public FrozenPlan requireAuthorized(Execution execution, Authorization authorization) {
        try {
            commands.requireExecution(execution);
            if (!(authorization instanceof Permit permit) || permit.service != this) {
                throw new IllegalStateException("owner-issued source authorization required");
            }
            checkBinding(binding(execution.command()), permit.decision.binding());
            effective(permit);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    // Do not re-check a consumed Execution: completeSuccess has legitimately consumed it by now.
                    effective(permit);
                }
            });
            return permit.plan;
        } catch (RuntimeException failure) {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && resource instanceof ConnectionHolder holder) {
                holder.setRollbackOnly();
            }
            throw failure;
        }
    }

    private Binding binding(Receipt command) {
        return new Binding(command.scope().tenantNo(), command.scope().entityNo(), command.scope().storeNo(),
                command.commandNo(), command.sourceType(), command.sourceNo(), command.sourceRevision(),
                command.action().name(), command.payloadHash(), ResourcePlanCodec.hash(command.frozenPayload()));
    }

    private void effective(Permit permit) {
        var user = SecurityUtils.requireUser();
        if (!user.userNo().equals(permit.request.operatorNo())
                || !user.realm().name().equals(permit.request.realm())
                || !user.subjectKind().name().equals(permit.request.subjectKind())
                || !user.tenantNo().equals(permit.request.binding().tenantNo())) {
            throw new IllegalStateException("resource source permission operator changed");
        }
        var now = clock.instant();
        if (now.isBefore(permit.decision.issuedAt()) || !now.isBefore(permit.decision.validUntil())) {
            throw new IllegalStateException("resource source permission is not currently effective");
        }
    }

    private void checkBinding(Binding expected, Binding actual) {
        if (!expected.tenantNo().equals(actual.tenantNo()) || !expected.entityNo().equals(actual.entityNo())
                || !expected.storeNo().equals(actual.storeNo()) || !expected.commandNo().equals(actual.commandNo())
                || !expected.sourceType().equals(actual.sourceType()) || !expected.sourceNo().equals(actual.sourceNo())
                || expected.sourceRevision() != actual.sourceRevision() || !expected.action().equals(actual.action())
                || !expected.commandHash().equals(actual.commandHash())) {
            throw new IllegalStateException("resource source command binding mismatch");
        }
        if (!expected.planHash().equals(actual.planHash())) {
            throw new IllegalStateException("resource source complete plan binding mismatch");
        }
    }

    private record Permit(ResourceSourceAuthorizationServiceImpl service,
            ResourceSourceAuthorityPort.Decision decision, FrozenPlan plan, Request request) implements Authorization {
    }
}
