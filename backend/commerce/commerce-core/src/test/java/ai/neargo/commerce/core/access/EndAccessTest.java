package ai.neargo.commerce.core.access;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.spi.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

class EndAccessTest {
    final Instant now = Instant.ofEpochMilli(123456789);
    final StoreRef store = new StoreRef("MAIN", "A"), other = new StoreRef("MAIN", "B");
    final ActorContext actor = new ActorContext("MAIN", SubjectKind.ACCOUNT, "owner", "session");
    Instant expiry = now.plusSeconds(400);
    boolean permitted = true, revoked;
    long version = 1;
    List<AccessSegment> rows =
            List.of(new AccessSegment("grant", "END_APP_POS", now, now.plusMillis(230)));
    EndContext proof;
    final EndContextCodecPort codec =
            new EndContextCodecPort() {
                public String encode(EndContext c) {
                    proof = c;
                    return "signed";
                }

                public EndContextVerification verify(String s, String aud, Instant at) {
                    return new EndContextVerification(Optional.ofNullable(proof), Optional.empty());
                }
            };
    final EndAccessService service =
            new EndAccessService(
                    new EndAccessService.Repository() {
                        public void requireOuter(StoreRef store) {}

                        public EndAccessService.State lockAndRead(
                                StoreRef store, String end, String code, String client) {
                            return new EndAccessService.State("M", "TYPE", version, rows, revoked);
                        }
                    },
                    new CommerceUnitOfWork() {
                        public <T> T required(Supplier<T> work) {
                            return work.get();
                        }
                    },
                    (a, s, code, cap, n) ->
                            new EndAccessService.Authority(
                                    new StoreSnapshot(
                                            s,
                                            "M",
                                            "TYPE",
                                            StoreStatus.ACTIVE,
                                            CommerceModel.STORE_V1),
                                    expiry,
                                    permitted),
                    codec,
                    Clock.fixed(now, ZoneOffset.UTC),
                    Duration.ofMinutes(5));

    EndContextToken issue() {
        return service.issueEndContext(
                new IssueEndContextCommand(
                        actor,
                        store,
                        store,
                        "APP_POS",
                        "END_APP_POS",
                        "pos",
                        EndAccessService.AUDIENCE));
    }

    AccessDecision check(ActorContext a, StoreRef resolved, StoreRef path) {
        return service.check(
                new AccessCheck(
                        a,
                        store,
                        resolved,
                        Optional.of(path),
                        "APP_POS",
                        "END_APP_POS",
                        "pos",
                        "biz:store",
                        Optional.of("signed")));
    }

    @Test
    void onlyMatchingSessionAndAllFourStoresCanUseProof() {
        issue();
        assertEquals(AccessStatus.ALLOWED, check(actor, store, store).status());
        assertEquals(AccessStatus.FORBIDDEN, check(actor, other, store).status());
        assertEquals(AccessStatus.FORBIDDEN, check(actor, store, other).status());
        var switched = new ActorContext("MAIN", SubjectKind.ACCOUNT, "owner", "another");
        assertEquals(AccessStatus.CONTEXT_REFRESH_REQUIRED, check(switched, store, store).status());
        assertEquals(AccessStatus.ALLOWED, check(actor, store, store).status());
        proof =
                new EndContext(
                        proof.contextNo(),
                        actor,
                        other,
                        proof.endCode(),
                        proof.entitlementCode(),
                        proof.clientId(),
                        proof.audience(),
                        proof.entitlementVersion(),
                        proof.issuedAt(),
                        proof.expiresAt());
        assertEquals(AccessStatus.CONTEXT_REFRESH_REQUIRED, check(actor, store, store).status());
    }

    @Test
    void sessionAndCommercialBoundariesCapFiveMinutes() {
        assertEquals(now.plusMillis(230), issue().expiresAt());
        rows = List.of(new AccessSegment("free", "END_APP_POS", now, null));
        assertEquals(now.plusSeconds(300), issue().expiresAt());
        expiry = now.plusMillis(11);
        assertEquals(expiry, issue().expiresAt());
    }

    @Test
    void statusAndRevisionAreReadAgain() {
        issue();
        version++;
        assertEquals(AccessStatus.CONTEXT_REFRESH_REQUIRED, check(actor, store, store).status());
        rows = List.of(new AccessSegment("grant", "END_APP_POS", now.minusMillis(20), now));
        assertEquals(AccessStatus.EXPIRED, check(actor, store, store).status());
        rows = List.of(new AccessSegment("grant", "END_APP_POS", now.plusMillis(1), null));
        assertEquals(AccessStatus.NOT_STARTED, check(actor, store, store).status());
        rows = List.of();
        assertEquals(AccessStatus.NOT_PURCHASED, check(actor, store, store).status());
        revoked = true;
        assertEquals(AccessStatus.REVOKED, check(actor, store, store).status());
    }

    @Test
    void commercialCoverageCannotGrantHostPermission() {
        issue();
        permitted = false;
        assertEquals(AccessStatus.FORBIDDEN, check(actor, store, store).status());
        assertThrows(AccessDenied.class, this::issue);
    }
}
