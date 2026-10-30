package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.dto.MappingContracts.CreateResourceMappingReq;
import ai.neargo.shop.commerce.dto.MappingContracts.ReviseResourceMappingReq;
import ai.neargo.shop.commerce.service.ResourceMappingService;
import ai.neargo.shop.commerce.service.ResourceMappingVerificationService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Parent;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Proof;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Query;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Status;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.SourceContext;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

class ResourceMappingTest {
    static class Gateway implements ChannelResourceIdentityGateway {
        int calls;
        boolean unknown, wrong, expired;
        Runnable hook;

        public String profileNo() {
            return "PP";
        }

        public Set<Capability> capabilities() {
            return Set.of(Capability.CATALOG);
        }

        public Proof query(Query request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            if (hook != null) {
                var saved = hook;
                hook = null;
                saved.run();
            }
            var id =
                    wrong
                            ? new Identity("WRONG", request.identity().parents())
                            : request.identity();
            return new Proof(
                    unknown ? Status.UNKNOWN : Status.VERIFIED,
                    request.entityNo(),
                    request.storeNo(),
                    request.connectionNo(),
                    request.objectType(),
                    id,
                    "RESOURCE_RECEIPT",
                    Instant.now().plusSeconds(expired ? -1 : 600));
        }
    }

    CreateResourceMappingReq req(String parent, String sourceRev) {
        return new CreateResourceMappingReq(
                "CON",
                ObjectType.SKU,
                new Identity("REMOTE_SKU", List.of(new Parent(ObjectType.OFFER, parent))),
                Owner.FOOD,
                "SKU",
                sourceRev,
                "OFFER",
                new SourceContext("MENU", null, ReadMode.CURRENT));
    }

    @Test
    void publicWriteIsDraftAndExternalParentIdentityIsStable() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var svc = s.context().getBean(ResourceMappingService.class);
                            var a = svc.createMapping("E", "S", "A", req("P1", "R1"), "a");
                            assertThat(a.active()).isNull();
                            assertThat(a.latest().validationStatus()).isEqualTo("DRAFT");
                            assertThat(svc.createMapping("E", "S", "A", req("P1", "R1"), "a"))
                                    .isEqualTo(a);
                            var b = svc.createMapping("E", "S", "A", req("P2", "R1"), "b");
                            assertThat(b.mappingNo()).isNotEqualTo(a.mappingNo());
                            assertThatThrownBy(
                                            () ->
                                                    svc.createMapping(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            req("P1", "R2"),
                                                            "duplicate"))
                                    .isInstanceOf(BizException.class);
                            assertThatThrownBy(
                                            () ->
                                                    svc.listRevisions(
                                                            "OTHER", "S", a.mappingNo(), 1, 20))
                                    .isInstanceOf(BizException.class);
                            assertThatThrownBy(
                                            () ->
                                                    svc.listRevisions(
                                                            "E", "OTHER", a.mappingNo(), 1, 20))
                                    .isInstanceOf(BizException.class);
                            assertThat(
                                            svc.listMappings(
                                                            "E",
                                                            "S",
                                                            1,
                                                            20,
                                                            "CON",
                                                            ObjectType.SKU,
                                                            "REMOTE_SKU",
                                                            "SKU")
                                                    .total())
                                    .isEqualTo(2);
                            assertThat(
                                            svc.listMappings(
                                                            "E",
                                                            "S",
                                                            1,
                                                            20,
                                                            null,
                                                            null,
                                                            null,
                                                            "SKU' OR 1=1 --")
                                                    .total())
                                    .isZero();
                            var resolver = s.context().getBean(ChannelResourceMappingPort.class);
                            assertThatThrownBy(
                                            () ->
                                                    resolver.resolveHistorical(
                                                            new ChannelResourceMappingPort
                                                                    .Reference(
                                                                    "E",
                                                                    "S",
                                                                    "CON",
                                                                    a.mappingNo(),
                                                                    a.latest().revisionNo())))
                                    .isInstanceOf(BizException.class);
                        });
    }

    @Test
    void trustedProofAppendsVerifiedRevisionAndOldRevisionRemainsFrozen() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var svc = s.context().getBean(ResourceMappingService.class);
                            var verifier =
                                    s.context().getBean(ResourceMappingVerificationService.class);
                            var resolver = s.context().getBean(ChannelResourceMappingPort.class);
                            var a = svc.createMapping("E", "S", "A", req("P1", "R1"), "a");
                            assertThat(verifier.verifyDue(10).verified()).isEqualTo(1);
                            var current =
                                    svc.listMappings("E", "S", 1, 20, "CON", null, null, null)
                                            .records()
                                            .getFirst();
                            assertThat(current.active().validationStatus()).isEqualTo("VERIFIED");
                            var original = current.active().revisionNo();
                            var draft =
                                    svc.reviseMapping(
                                            "E",
                                            "S",
                                            a.mappingNo(),
                                            "A",
                                            new ReviseResourceMappingReq(
                                                    current.version(),
                                                    Owner.FOOD,
                                                    "SKU",
                                                    "R2",
                                                    "OFFER",
                                                    "new"),
                                            "revise");
                            assertThat(draft.active()).isNull();
                            verifier.verifyDue(10);
                            var historical =
                                    resolver.resolveHistorical(
                                            new ChannelResourceMappingPort.Reference(
                                                    "E", "S", "CON", a.mappingNo(), original));
                            assertThat(historical.sourceRevision()).isEqualTo("R1");
                            assertThat(svc.listRevisions("E", "S", a.mappingNo(), 1, 20).records())
                                    .hasSize(4);
                            assertThat(
                                            svc.listRevisions("E", "S", a.mappingNo(), 1, 20)
                                                    .records()
                                                    .getLast()
                                                    .validationStatus())
                                    .isEqualTo("DRAFT");
                            s.source().hashChanged = true;
                            assertThatThrownBy(
                                            () ->
                                                    resolver.resolveHistorical(
                                                            new ChannelResourceMappingPort
                                                                    .Reference(
                                                                    "E",
                                                                    "S",
                                                                    "CON",
                                                                    a.mappingNo(),
                                                                    original)))
                                    .isInstanceOf(BizException.class);
                        });
    }

    @Test
    void unknownExpiredOrMismatchedRemoteProofNeverActivates() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var svc = s.context().getBean(ResourceMappingService.class);
                            var verifier =
                                    s.context().getBean(ResourceMappingVerificationService.class);
                            var gw =
                                    (Gateway)
                                            s.context()
                                                    .getBean(ChannelResourceIdentityGateway.class);
                            svc.createMapping("E", "S", "A", req("P1", "R1"), "a");
                            gw.unknown = true;
                            assertThat(verifier.verifyDue(10).verified()).isZero();
                            s.sql()
                                    .update(
                                            "UPDATE cim_resource_mapping SET"
                                                    + " next_verification_at=?",
                                            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
                            gw.unknown = false;
                            gw.wrong = true;
                            verifier.verifyDue(10);
                            s.sql()
                                    .update(
                                            "UPDATE cim_resource_mapping SET"
                                                    + " next_verification_at=?",
                                            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
                            gw.wrong = false;
                            gw.expired = true;
                            verifier.verifyDue(10);
                            assertThat(
                                            svc.listMappings(
                                                            "E", "S", 1, 20, null, null, null, null)
                                                    .records()
                                                    .getFirst()
                                                    .active())
                                    .isNull();
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                    + " cim_operation_attempt WHERE"
                                                                    + " outcome='SUCCEEDED'",
                                                            Long.class))
                                    .isZero();
                        });
    }

    @Test
    void lateVerificationCannotActivateRevisedDraft() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var svc = s.context().getBean(ResourceMappingService.class);
                            var verifier =
                                    s.context().getBean(ResourceMappingVerificationService.class);
                            var gw =
                                    (Gateway)
                                            s.context()
                                                    .getBean(ChannelResourceIdentityGateway.class);
                            var a = svc.createMapping("E", "S", "A", req("P1", "R1"), "a");
                            gw.hook =
                                    () -> {
                                        var current =
                                                svc.listMappings(
                                                                "E", "S", 1, 20, null, null, null,
                                                                null)
                                                        .records()
                                                        .getFirst();
                                        svc.reviseMapping(
                                                "E",
                                                "S",
                                                a.mappingNo(),
                                                "A",
                                                new ReviseResourceMappingReq(
                                                        current.version(),
                                                        Owner.FOOD,
                                                        "SKU",
                                                        "R2",
                                                        "OFFER",
                                                        "new draft"),
                                                "revise");
                                    };
                            verifier.verifyDue(10);
                            var current =
                                    svc.listMappings("E", "S", 1, 20, null, null, null, null)
                                            .records()
                                            .getFirst();
                            assertThat(current.active()).isNull();
                            assertThat(current.latest().sourceRevision()).isEqualTo("R2");
                            assertThat(current.latest().validationStatus()).isEqualTo("DRAFT");
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                + " cim_operation_attempt WHERE"
                                                                + " late_evidence_ref IS NOT NULL",
                                                            Long.class))
                                    .isEqualTo(1);
                        });
    }

    @Test
    void sourceOrGrantFailureCannotBeBypassedByPublicFields() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var svc = s.context().getBean(ResourceMappingService.class);
                            var verifier =
                                    s.context().getBean(ResourceMappingVerificationService.class);
                            var gw =
                                    (Gateway)
                                            s.context()
                                                    .getBean(ChannelResourceIdentityGateway.class);
                            assertThatThrownBy(
                                            () ->
                                                    svc.createMapping(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            new CreateResourceMappingReq(
                                                                    "CON",
                                                                    ObjectType.SKU,
                                                                    req("P", "R1")
                                                                            .externalIdentity(),
                                                                    Owner.RETAIL,
                                                                    "SKU",
                                                                    "R1",
                                                                    "OFFER"),
                                                            "mixed"))
                                    .isInstanceOf(BizException.class);
                            svc.createMapping("E", "S", "A", req("P", "R1"), "a");
                            s.source().stale = true;
                            verifier.verifyDue(10);
                            assertThat(gw.calls).isZero();
                            s.source().stale = false;
                            s.sql().update("UPDATE cim_account SET status='REVOKED'");
                            s.sql()
                                    .update(
                                            "UPDATE cim_resource_mapping SET"
                                                    + " next_verification_at=?",
                                            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
                            verifier.verifyDue(10);
                            assertThat(gw.calls).isZero();
                            assertThat(
                                            svc.listMappings(
                                                            "E", "S", 1, 20, null, null, null, null)
                                                    .records()
                                                    .getFirst()
                                                    .active())
                                    .isNull();
                        });
    }

    @Test
    void manualRevisionKeepsActorAndRevisionSupersedesInFlightQueryImmediately() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var svc = s.context().getBean(ResourceMappingService.class);
                            var verifier =
                                    s.context().getBean(ResourceMappingVerificationService.class);
                            var gw =
                                    (Gateway)
                                            s.context()
                                                    .getBean(ChannelResourceIdentityGateway.class);
                            var created = svc.createMapping("E", "S", "A", req("P", "R1"), "a");
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT created_by FROM"
                                                                + " cim_resource_mapping_revision"
                                                                + " WHERE revision_no=?",
                                                            String.class,
                                                            created.latest().revisionNo()))
                                    .isEqualTo("A");
                            gw.hook =
                                    () -> {
                                        var current =
                                                svc.listMappings(
                                                                "E", "S", 1, 20, null, null, null,
                                                                null)
                                                        .records()
                                                        .getFirst();
                                        svc.reviseMapping(
                                                "E",
                                                "S",
                                                created.mappingNo(),
                                                "A",
                                                new ReviseResourceMappingReq(
                                                        current.version(),
                                                        Owner.FOOD,
                                                        "SKU",
                                                        "R2",
                                                        "OFFER",
                                                        "new draft"),
                                                "revise");
                                        assertThat(
                                                        s.sql()
                                                                .queryForObject(
                                                                        "SELECT status FROM"
                                                                            + " cim_operation WHERE"
                                                                            + " operation_type='MAPPING_VERIFY'",
                                                                        String.class))
                                                .isEqualTo("SUPERSEDED");
                                    };
                            verifier.verifyDue(10);
                        });
    }

    @Test
    void legacySourceProofCannotConfirmSalesOfferMembership() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var svc = s.context().getBean(ResourceMappingService.class);
                            var verifier =
                                    s.context().getBean(ResourceMappingVerificationService.class);
                            var gw =
                                    (Gateway)
                                            s.context()
                                                    .getBean(ChannelResourceIdentityGateway.class);
                            s.source().legacyOfferProof = true;
                            svc.createMapping("E", "S", "A", req("P", "R1"), "a");
                            verifier.verifyDue(10);
                            assertThat(gw.calls).isZero();
                            assertThat(
                                            svc.listMappings(
                                                            "E", "S", 1, 20, null, null, null, null)
                                                    .records()
                                                    .getFirst()
                                                    .active())
                                    .isNull();
                        });
    }

    @Test
    void identityVerificationDoesNotRequirePublicationButPublishingStillDoes() {
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        s -> {
                            assertThat(s.context().getBeansOfType(ChannelCatalogGateway.class))
                                    .isEmpty();
                            var svc = s.context().getBean(ResourceMappingService.class);
                            var verifier =
                                    s.context().getBean(ResourceMappingVerificationService.class);
                            svc.createMapping("E", "S", "A", req("P", "R1"), "a");
                            assertThat(verifier.verifyDue(10).verified()).isEqualTo(1);
                            assertThat(
                                            svc.listMappings(
                                                            "E", "S", 1, 20, null, null, null, null)
                                                    .records()
                                                    .getFirst()
                                                    .active()
                                                    .validationStatus())
                                    .isEqualTo("VERIFIED");
                            var scope =
                                    s.scopes()
                                            .createScope(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new CreateCatalogScopeReq(
                                                            "CON",
                                                            "STORE",
                                                            "Menu",
                                                            List.of(
                                                                    new SourceReq(
                                                                            Owner.FOOD,
                                                                            "MENU",
                                                                            "R1",
                                                                            0))),
                                                    "scope");
                            var p =
                                    s.previews()
                                            .preview(
                                                    "E",
                                                    "S",
                                                    scope.scopeNo(),
                                                    "A",
                                                    new PreviewCatalogReq(scope.version()),
                                                    "preview");
                            assertThatThrownBy(
                                            () ->
                                                    s.runs()
                                                            .submit(
                                                                    "E",
                                                                    "S",
                                                                    "A",
                                                                    new SubmitSyncRunReq(
                                                                            scope.scopeNo(),
                                                                            p.snapshotNo(),
                                                                            p.hash(),
                                                                            scope.version(),
                                                                            "publish"),
                                                                    "publish"))
                                    .isInstanceOf(BizException.class);
                        });
    }

    @Test
    void historicalResolutionUsesOriginalConnectionOwnerAfterRouteRevision() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            var svc = s.context().getBean(ResourceMappingService.class);
                            var verifier =
                                    s.context().getBean(ResourceMappingVerificationService.class);
                            var resolver = s.context().getBean(ChannelResourceMappingPort.class);
                            var m = svc.createMapping("E", "S", "A", req("P", "R1"), "a");
                            verifier.verifyDue(10);
                            var original =
                                    svc.listMappings("E", "S", 1, 20, null, null, null, null)
                                            .records()
                                            .getFirst()
                                            .active()
                                            .revisionNo();
                            s.sql()
                                    .update(
                                            "INSERT INTO"
                                                + " cim_connection_revision(revision_no,entity_no,connection_no,revision,source_owner,accept_mode,fulfillment_profile,catalog_authority,config_json,config_hash)"
                                                + " VALUES"
                                                + " ('CR2','E','CON',2,'RETAIL','MANUAL','INSTANT','REMOTE','{}',?)",
                                            "d".repeat(64));
                            s.sql()
                                    .update(
                                            "UPDATE cim_store_connection SET"
                                                    + " active_revision_no='CR2'");
                            assertThat(
                                            resolver.resolveHistorical(
                                                            new ChannelResourceMappingPort
                                                                    .Reference(
                                                                    "E",
                                                                    "S",
                                                                    "CON",
                                                                    m.mappingNo(),
                                                                    original))
                                                    .sourceOwner())
                                    .isEqualTo(Owner.FOOD);
                            assertThatThrownBy(
                                            () ->
                                                    svc.createMapping(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            req("OTHER", "R1"),
                                                            "wrong-current-owner"))
                                    .isInstanceOf(BizException.class);
                        });
    }
}
