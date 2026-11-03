package ai.neargo.commerce.core.access;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;

import java.time.*;
import java.util.*;

public final class EndAccessService implements EndAccessFacade {

    public record State(
            String merchant,
            String type,
            long version,
            List<AccessSegment> segments,
            boolean revoked) {}

    public interface Repository {
        void requireOuter(StoreRef store);

        State lockAndRead(StoreRef store, String end, String code, String clientId);
    }

    private final Repository repo;
    private final CommerceUnitOfWork uow;
    private final EndAccessAuthorityPort host;
    private final EndContextCodecPort codec;
    private final Clock clock;
    private final Duration ttl;

    public EndAccessService(
            Repository repo,
            CommerceUnitOfWork uow,
            EndAccessAuthorityPort host,
            EndContextCodecPort codec,
            Clock clock,
            Duration ttl) {
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException(
                    "End context TTL must be positive and at most five minutes");
        this.repo = repo;
        this.uow = uow;
        this.host = host;
        this.codec = codec;
        this.clock = clock;
        this.ttl = ttl;
    }

    public AccessDecision check(AccessCheck c) {
        try {
            return uow.required(() -> evaluate(c, false).decision());
        } catch (AccessDenied denied) {
            return denied(c, denied.status());
        } catch (RuntimeException unavailable) {
            return denied(c, AccessStatus.CHECK_UNAVAILABLE);
        }
    }

    public AccessDecision requireForWrite(AccessCheck c) {
        repo.requireOuter(c.requestedStore());
        return guarded(() -> require(evaluate(c, false).decision()));
    }

    public EndContextToken issueEndContext(IssueEndContextCommand cmd) {
        if (!AUDIENCE.equals(cmd.audience())) throw new AccessDenied(AccessStatus.FORBIDDEN);
        var check =
                new AccessCheck(
                        cmd.actor(),
                        cmd.requestedStore(),
                        cmd.resolvedStore(),
                        Optional.empty(),
                        cmd.endCode(),
                        cmd.entitlementCode(),
                        cmd.clientId(),
                        ENTER_END,
                        Optional.empty());
        return guarded(
                () ->
                        uow.required(
                                () -> {
                                    var evaluation = evaluate(check, true);
                                    var decision = require(evaluation.decision());
                                    var now = decision.serverNow();
                                    var expiry =
                                            Collections.min(
                                                    List.of(
                                                            now.plus(ttl),
                                                            evaluation.sessionExpiry(),
                                                            decision.nextBoundaryAt()
                                                                    .orElse(Instant.MAX)));
                                    var proof =
                                            new EndContext(
                                                    UUID.randomUUID().toString(),
                                                    cmd.actor(),
                                                    cmd.requestedStore(),
                                                    cmd.endCode(),
                                                    cmd.entitlementCode(),
                                                    cmd.clientId(),
                                                    AUDIENCE,
                                                    decision.entitlementVersion(),
                                                    now,
                                                    expiry);
                                    return new EndContextToken(
                                            codec.encode(proof),
                                            expiry,
                                            decision.entitlementVersion());
                                }));
    }

    private static <T> T guarded(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (AccessDenied denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
        }
    }

    /**
     * Membership/entry permission still apply. This status endpoint never grants a usable
     * credential.
     */
    public AccessDecision inspect(IssueEndContextCommand cmd) {
        var check =
                new AccessCheck(
                        cmd.actor(),
                        cmd.requestedStore(),
                        cmd.resolvedStore(),
                        Optional.empty(),
                        cmd.endCode(),
                        cmd.entitlementCode(),
                        cmd.clientId(),
                        ENTER_END,
                        Optional.empty());
        try {
            return uow.required(() -> evaluate(check, true).decision());
        } catch (AccessDenied denied) {
            return denied(check, denied.status());
        } catch (RuntimeException unavailable) {
            return denied(check, AccessStatus.CHECK_UNAVAILABLE);
        }
    }

    private record Evaluation(AccessDecision decision, Instant sessionExpiry) {}

    private Evaluation evaluate(AccessCheck c, boolean issuing) {
        var store = c.requestedStore();
        var actor = c.actor();
        if (actor.subjectKind() != SubjectKind.ACCOUNT
                || !store.tenantNo().equals(actor.tenantNo())
                || !store.equals(c.resolvedStore())
                || c.pathStore().filter(s -> !store.equals(s)).isPresent()
                || blank(actor.principalNo())
                || blank(actor.accountSessionFingerprint())
                || blank(c.entitlementCode())
                || blank(c.clientId())
                || blank(c.requiredCapability())) throw new AccessDenied(AccessStatus.FORBIDDEN);
        // Acquire the account before the fresh admission snapshot and before sampling business
        // time.
        var state = repo.lockAndRead(store, c.endCode(), c.entitlementCode(), c.clientId());
        var auth =
                host.verify(
                        actor, store, c.entitlementCode(), c.requiredCapability(), clock.instant());
        var now = Instant.ofEpochMilli(clock.millis());
        if (!auth.sessionExpiry().isAfter(now))
            throw new AccessDenied(AccessStatus.ACCOUNT_INVALID);
        var facts = auth.store();
        if (!store.equals(facts.store())
                || facts.model() != CommerceModel.STORE_V1
                || blank(facts.businessTypeCode())
                || !facts.businessTypeCode().equals(state.type())
                || !facts.merchantNo().equals(state.merchant()))
            throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
        if (facts.status() != StoreStatus.ACTIVE)
            throw new AccessDenied(AccessStatus.STORE_DISABLED);
        if (!auth.permitted()) throw new AccessDenied(AccessStatus.FORBIDDEN);
        var coverage = CoverageCalculator.evaluate(c.entitlementCode(), state.segments(), now);
        AccessStatus status;
        if (coverage.currentCoverage().isPresent()) {
            status =
                    issuing || validProof(c, state.version(), now)
                            ? AccessStatus.ALLOWED
                            : AccessStatus.CONTEXT_REFRESH_REQUIRED;
        } else if (coverage.nextStartAt().isPresent()) status = AccessStatus.NOT_STARTED;
        else if (state.revoked()) status = AccessStatus.REVOKED;
        else if (coverage.mostRecentEndedCoverage().isPresent()) status = AccessStatus.EXPIRED;
        else status = AccessStatus.NOT_PURCHASED;
        var decision =
                new AccessDecision(
                        status,
                        store,
                        c.entitlementCode(),
                        state.version(),
                        now,
                        coverage.nextBoundaryAt(),
                        status == AccessStatus.ALLOWED
                                ? Optional.empty()
                                : Optional.of(status.name()));
        return new Evaluation(decision, auth.sessionExpiry());
    }

    private boolean validProof(AccessCheck c, long version, Instant now) {
        return c.endContext()
                .flatMap(token -> codec.verify(token, AUDIENCE, now).context())
                .filter(
                        p ->
                                p.actor().equals(c.actor())
                                        && p.store().equals(c.requestedStore())
                                        && p.endCode().equals(c.endCode())
                                        && p.entitlementCode().equals(c.entitlementCode())
                                        && p.clientId().equals(c.clientId())
                                        && p.audience().equals(AUDIENCE)
                                        && p.entitlementVersion() == version
                                        && !now.isBefore(p.issuedAt())
                                        && now.isBefore(p.expiresAt()))
                .isPresent();
    }

    private AccessDecision denied(AccessCheck c, AccessStatus status) {
        return new AccessDecision(
                status,
                c.requestedStore(),
                c.entitlementCode(),
                0,
                clock.instant(),
                Optional.empty(),
                Optional.of(status.name()));
    }

    private static AccessDecision require(AccessDecision decision) {
        if (decision.status() != AccessStatus.ALLOWED) throw new AccessDenied(decision.status());
        return decision;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
