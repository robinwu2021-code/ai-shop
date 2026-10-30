package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.RedemptionContracts;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.mapper.RedemptionItemMapper;
import ai.neargo.shop.commerce.mapper.RedemptionLinkMapper;
import ai.neargo.shop.commerce.mapper.RedemptionPreviewMapper;
import ai.neargo.shop.commerce.mapper.RedemptionReversalMapper;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.ChannelRedemptionServiceImpl;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;
import ai.neargo.shop.spi.commerce.ChannelRedemptionGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

class ChannelRedemptionRecoveryTest {
    final ChannelResourceIdentityGateway.Identity identity =
            new ChannelResourceIdentityGateway.Identity("REMOTE_SKU", List.of());

    class Gateway implements ChannelRedemptionGateway {
        int sends, queries, reverseSends, reverseQueries;
        Action sent, queried, reverseSent, reverseQueried;

        public String profileNo() {
            return "PP";
        }

        public Set<Capability> capabilities() {
            return Set.of(Capability.REDEMPTION);
        }

        public Verified verifyCertificate(Verify q) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var now = Instant.now();
            return new Verified(
                    q.operationNo(),
                    PricingSnapshotHash.of(q),
                    Status.CONFIRMED,
                    new Certificate(
                            "a".repeat(64),
                            identity,
                            null,
                            new BigDecimal("10"),
                            BigDecimal.ZERO,
                            new BigDecimal("10"),
                            "KG",
                            now.plusSeconds(3600),
                            now.plusSeconds(29),
                            "VERIFY_RECEIPT"));
        }

        Result result(Action a, Status status) {
            return new Result(
                    a.operationNo(),
                    PricingSnapshotHash.of(a),
                    status,
                    a.certificateIdentityHash(),
                    status == Status.UNKNOWN ? null : a.quantity(),
                    status == Status.UNKNOWN ? null : a.quantity(),
                    a.unit(),
                    status == Status.UNKNOWN ? null : "ORIGINAL_ISSUER_RECEIPT");
        }

        public Result redeemCertificate(Action a) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends++;
            sent = a;
            return result(a, Status.UNKNOWN);
        }

        public Result queryRedemption(Action a) {
            queries++;
            queried = a;
            return result(a, Status.CONFIRMED);
        }

        public Result reverseCertificate(Action a) {
            reverseSends++;
            reverseSent = a;
            return result(a, Status.UNKNOWN);
        }

        public Result queryReversal(Action a) {
            reverseQueries++;
            reverseQueried = a;
            return result(a, Status.CONFIRMED);
        }
    }

    static class Source implements ExternalCertificateConsumptionPort {
        Authorization use, reverse;
        boolean reversalSupported = true;
        int freezes;

        Authorization change(Authorization a, String status) {
            return new Authorization(
                    a.authorizationRef(),
                    a.scope(),
                    a.business(),
                    a.previewRef(),
                    a.previewHash(),
                    a.certificateIdentityHash(),
                    a.sourceHash(),
                    a.quantity(),
                    a.unit(),
                    status,
                    a.expiresAt(),
                    "CONFIRMED".equals(status) ? "ORIGINAL_CORE_DECISION" : null,
                    a.originalAuthorizationRef(),
                    a.purpose());
        }

        public Authorization authorize(
                Scope scope, Business business, String preview, String hash) {
            use =
                    new Authorization(
                            "SOURCE_USE",
                            scope,
                            business,
                            preview,
                            hash,
                            "a".repeat(64),
                            "b".repeat(64),
                            new BigDecimal("1.25"),
                            "KG",
                            "RESERVED",
                            Instant.now().plusSeconds(25),
                            null,
                            null,
                            "USE");
            return use;
        }

        public Authorization freeze(Scope scope, String ref) {
            freezes++;
            if (ref.equals("SOURCE_USE")) {
                use = change(use, "PENDING");
                return use;
            }
            reverse = change(reverse, "PENDING");
            return reverse;
        }

        public Authorization reconcile(Scope scope, String ref) {
            if (ref.equals("SOURCE_USE")) {
                use = change(use, "CONFIRMED");
                return use;
            }
            reverse = change(reverse, "CONFIRMED");
            return reverse;
        }

        public Authorization query(Scope scope, String ref) {
            return ref.equals("SOURCE_USE") ? use : reverse;
        }

        public Authorization authorizeReversal(Scope scope, String ref, String originalReturn) {
            if (!reversalSupported)
                throw new UnsupportedOperationException("Original return source unavailable");
            reverse =
                    new Authorization(
                            "SOURCE_REVERSE",
                            scope,
                            use.business(),
                            use.previewRef(),
                            use.previewHash(),
                            use.certificateIdentityHash(),
                            "c".repeat(64),
                            use.quantity(),
                            use.unit(),
                            "RESERVED",
                            Instant.now().plusSeconds(25),
                            null,
                            use.authorizationRef(),
                            "REVERSE");
            return reverse;
        }
    }

    @Test
    void
            originalConsumptionAndReversalEachSendOnceAndUnknownQueriesKeepTheExactFrozenOriginalAction() {
        var gateway = new Gateway();
        var source = new Source();
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        s -> {
                            s.sql()
                                    .execute(
                                            "UPDATE cim_authorization_revision SET"
                                                + " granted_scopes='[\"CATALOG\",\"REDEMPTION\"]'");
                            var mappings = mock(ResourceMappingMapper.class);
                            var map = new CimResourceMapping();
                            map.setMappingNo("MAP");
                            map.setActiveRevisionNo("MR");
                            when(mappings.selectList(any())).thenReturn(List.of(map));
                            ChannelResourceMappingPort authority =
                                    reference ->
                                            new ChannelResourceMappingPort.Resolution(
                                                    "MAP",
                                                    "MR",
                                                    ChannelResourceIdentityGateway.ObjectType.SKU,
                                                    identity,
                                                    ChannelSourceValidationPort.Owner.RETAIL,
                                                    "ACTUAL_SKU",
                                                    "R1",
                                                    null,
                                                    "d".repeat(64),
                                                    "OLD",
                                                    "FRESH");
                            var handles = mock(ChannelCertificateHandleStore.class);
                            var handleScope =
                                    new ChannelCertificateHandleStore.Scope("E", "S", "CON");
                            when(handles.read(handleScope, "HANDLE"))
                                    .thenReturn(
                                            new ChannelCertificateHandleStore.Handle(
                                                    "HANDLE",
                                                    7,
                                                    handleScope,
                                                    "ACC",
                                                    "PP",
                                                    9L,
                                                    "SEALED"));
                            var service =
                                    new ChannelRedemptionServiceImpl(
                                            s.context().getBean(CatalogPersistence.class),
                                            s.context().getBean(RedemptionPreviewMapper.class),
                                            s.context().getBean(RedemptionLinkMapper.class),
                                            s.context().getBean(RedemptionItemMapper.class),
                                            s.context().getBean(RedemptionReversalMapper.class),
                                            mappings,
                                            authority,
                                            handles,
                                            () -> List.of(source),
                                            List.of(gateway),
                                            Clock.systemUTC());
                            var preview =
                                    service.preview(
                                            "E",
                                            "S",
                                            "A",
                                            new RedemptionContracts.Preview(
                                                    "CON",
                                                    "HANDLE",
                                                    new ExternalCertificateConsumptionPort.Business(
                                                            "ACTUAL_SUB", 11)),
                                            "PREVIEW_KEY");
                            assertThat(preview.status()).isEqualTo("READY");
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT business_revision FROM"
                                                                + " cim_operation WHERE"
                                                                + " operation_type='CERTIFICATE_VERIFY'",
                                                            String.class))
                                    .isEqualTo("0");
                            assertThat(gateway.sends).isZero();
                            var op =
                                    service.redeem(
                                            "E",
                                            "S",
                                            "A",
                                            new RedemptionContracts.Redeem(
                                                    preview.previewRef(),
                                                    preview.expectedHash(),
                                                    preview.sourceAuthorizationRef()),
                                            "REDEEM_KEY");
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT business_revision FROM"
                                                                + " cim_operation WHERE"
                                                                + " operation_type='CERTIFICATE_REDEEM'",
                                                            String.class))
                                    .isEqualTo(source.use.sourceHash());
                            service.recoverDue(20);
                            assertThat(gateway.sends).isOne();
                            assertThat(source.freezes).isOne();
                            s.sql()
                                    .execute(
                                            "UPDATE cim_account SET"
                                                    + " active_authorization_no='ROTATED'");
                            s.sql()
                                    .execute(
                                            "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                                    + " 00:00:00' WHERE status='UNKNOWN'");
                            service.recoverDue(20);
                            assertThat(gateway.sends).isOne();
                            assertThat(gateway.queried).isEqualTo(gateway.sent);
                            assertThat(source.freezes).isOne();
                            var redemption =
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT redemption_no FROM cim_redemption_link",
                                                    String.class);
                            var ready = service.getRedemption("E", "S", redemption);
                            assertThat(ready.status()).isEqualTo("SUCCEEDED");
                            assertThat(ready.sourceDecisionRef())
                                    .isEqualTo("ORIGINAL_CORE_DECISION");
                            s.sql()
                                    .execute(
                                            "UPDATE cim_account SET"
                                                    + " active_authorization_no='AUTH'");
                            source.reversalSupported = false;
                            var waiting =
                                    service.reverse(
                                            "E",
                                            "S",
                                            "A",
                                            redemption,
                                            new RedemptionContracts.Reverse(
                                                    ready.version(),
                                                    "ACTUAL_RECEIVED_RETURN",
                                                    "Source not yet available"),
                                            "WAITING_KEY");
                            assertThat(waiting.status()).isEqualTo("WAITING_SOURCE");
                            assertThat(
                                            service.reverse(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    redemption,
                                                    new RedemptionContracts.Reverse(
                                                            ready.version(),
                                                            "ACTUAL_RECEIVED_RETURN",
                                                            "Source not yet available"),
                                                    "WAITING_KEY"))
                                    .isEqualTo(waiting);
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(DISTINCT semantic_key)"
                                                                + " FROM cim_operation WHERE"
                                                                + " business_ref=?",
                                                            Integer.class,
                                                            redemption))
                                    .isEqualTo(2);
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM cim_operation"
                                                                + " WHERE status='WAITING_SOURCE'",
                                                            Integer.class))
                                    .isOne();
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT business_revision FROM"
                                                                    + " cim_operation WHERE"
                                                                    + " status='WAITING_SOURCE'",
                                                            String.class))
                                    .isEqualTo(String.valueOf(ready.version()));
                            assertThat(waiting.errorCode())
                                    .isEqualTo("ORIGINAL_REVERSAL_SOURCE_UNAVAILABLE");
                            assertThat(gateway.reverseSends).isZero();
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                    + " cim_redemption_reversal",
                                                            Integer.class))
                                    .isZero();
                            source.reversalSupported = true;
                            service.reverse(
                                    "E",
                                    "S",
                                    "A",
                                    redemption,
                                    new RedemptionContracts.Reverse(
                                            ready.version(),
                                            "ACTUAL_RECEIVED_RETURN",
                                            "Original full return"),
                                    "REVERSE_KEY");
                            service.recoverDue(20);
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT business_revision FROM"
                                                                + " cim_operation WHERE"
                                                                + " operation_type='CERTIFICATE_REVERSE'"
                                                                + " AND status<>'WAITING_SOURCE'",
                                                            String.class))
                                    .isEqualTo(source.reverse.sourceHash());
                            assertThat(gateway.reverseSends).isOne();
                            s.sql()
                                    .execute(
                                            "UPDATE cim_account SET"
                                                    + " active_authorization_no='ROTATED'");
                            s.sql()
                                    .execute(
                                            "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                                    + " 00:00:00' WHERE status='UNKNOWN'");
                            service.recoverDue(20);
                            assertThat(gateway.reverseSends).isOne();
                            assertThat(gateway.reverseQueried).isEqualTo(gateway.reverseSent);
                            assertThat(service.getRedemption("E", "S", redemption).status())
                                    .isEqualTo("REVERSED");
                            assertThatThrownBy(
                                            () -> service.getRedemption("E", "OTHER", redemption))
                                    .isInstanceOf(RuntimeException.class);
                            assertThat(
                                            s.sql()
                                                    .queryForList(
                                                            "SELECT attempt_kind FROM"
                                                                + " cim_operation_attempt ORDER BY"
                                                                + " id",
                                                            String.class))
                                    .containsExactly("QUERY", "SEND", "QUERY", "SEND", "QUERY");
                        });
    }
}
