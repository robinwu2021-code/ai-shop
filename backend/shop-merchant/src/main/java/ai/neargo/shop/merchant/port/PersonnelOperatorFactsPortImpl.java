package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.mapper.MerchantMappers.OperatorAccessMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelAccountMapper;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A repeatable-read observation rechecks canonical live access on both sides without exporting
 * login identities.
 */
@Component
public class PersonnelOperatorFactsPortImpl implements PersonnelOperatorFactsPort {
    private final MerchantOperatorAccessPort access;
    private final OperatorAccessMapper accessMapper;
    private final PersonnelAccountMapper links;
    private final Clock clock;
    private final TransactionTemplate snapshot;

    public PersonnelOperatorFactsPortImpl(
            MerchantOperatorAccessPort access,
            OperatorAccessMapper accessMapper,
            PersonnelAccountMapper links,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<Clock> clocks) {
        this.access = access;
        this.accessMapper = accessMapper;
        this.links = links;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
        this.snapshot = new TransactionTemplate(manager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Observation current(Query query) {
        DelegatedIdentityContext.outside();
        if (query == null) {
            throw new IllegalArgumentException("explicit operator query required");
        }
        requireIdentity(query.access());
        var account =
                access.resolveAuthorizedOperator(query.access())
                        .orElseThrow(PersonnelOperatorFactsPortImpl::denied);
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Observation result =
                DataScopeContext.executeWithoutScope(
                        () ->
                                snapshot.execute(
                                        status ->
                                                load(
                                                        query.access(),
                                                        account.accountNo(),
                                                        observedAt)));
        var after =
                access.resolveAuthorizedOperator(query.access())
                        .orElseThrow(PersonnelOperatorFactsPortImpl::denied);
        requireIdentity(query.access());
        if (!after.equals(account)) {
            throw denied();
        }
        return result;
    }

    private Observation load(
            MerchantOperatorAccessPort.Query query, String accountNo, Instant observedAt) {
        // The canonical resolver intentionally remains outside this transaction; only its unchanged
        // SQL runs here.
        var authorization = accessMapper.selectAccess(query);
        if (authorization.size() != 1
                || !authorization.getFirst().allowed()
                || !accountNo.equals(authorization.getFirst().accountNo())) {
            throw denied();
        }
        var rows =
                links.selectCurrentOperatorPersonnel(
                        query.tenantNo(),
                        query.entityNo(),
                        query.storeNo(),
                        accountNo,
                        LocalDateTime.ofInstant(observedAt, ZoneOffset.UTC));
        if (rows.size() > 1) {
            throw new IllegalStateException("ambiguous current personnel relationship");
        }
        if (rows.isEmpty()) {
            return new Observation(LinkState.UNLINKED, observedAt, null);
        }
        var row = rows.getFirst();
        if (row.linkNo() == null || row.linkNo().isBlank() || row.linkNo().length() > 32) {
            throw new IllegalStateException("inconsistent current personnel relationship");
        }
        try {
            var fact =
                    new OperatorPersonnelFact(
                            row.personnelNo(),
                            row.assignmentNo(),
                            row.personnelRevision(),
                            row.assignmentRevision(),
                            row.linkRevision(),
                            utc(row.validFrom()),
                            utc(row.validTo()));
            return new Observation(LinkState.LINKED, observedAt, fact);
        } catch (IllegalArgumentException | NullPointerException inconsistent) {
            // Owner inconsistency is dependency failure, never a caller BAD_REQUEST or authorized
            // absence.
            throw new IllegalStateException("inconsistent current personnel snapshot");
        }
    }

    private static void requireIdentity(MerchantOperatorAccessPort.Query query) {
        var expected =
                new DelegatedIdentity(
                        query.tenantNo(), query.realm(), query.subjectKind(), query.operatorNo());
        if (!expected.equals(DelegatedIdentityContext.current())) {
            throw denied();
        }
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static BizException denied() {
        return BizException.of(ErrorCode.FORBIDDEN);
    }
}
