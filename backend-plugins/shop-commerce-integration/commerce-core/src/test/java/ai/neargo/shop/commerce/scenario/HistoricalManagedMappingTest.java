package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.entity.CimResourceMappingRevision;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingRevisionMapper;
import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.CatalogResourceIndex;
import ai.neargo.shop.commerce.service.ResourceMappingVerificationService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderMappingResolver;
import ai.neargo.shop.commerce.service.impl.HistoricalManagedMappingService;
import ai.neargo.shop.commerce.service.impl.ManagedMappingProjectionService;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Section;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.SectionSource;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Parent;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

class HistoricalManagedMappingTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    record State(
            CatalogSynchronizationTest.State app,
            Document catalog,
            CimOrderLink order,
            HistoricalManagedMappingService history) {}

    Document detailed(Document old) {
        var d = CatalogDetailsPolicyTest.fromLegacy(old);
        var source = d.sources().getFirst();
        var sections =
                d.details().sections().stream()
                        .map(
                                section ->
                                        new Section(
                                                section.key(),
                                                section.name(),
                                                section.sortNo(),
                                                new SectionSource(
                                                        source.sourceRef(),
                                                        source.selectionRevision(),
                                                        section.key())))
                        .toList();
        var offers =
                d.details().offers().stream()
                        .map(
                                offer ->
                                        new ChannelCatalogDetails.Offer(
                                                offer.offerRef(),
                                                offer.sourceRef(),
                                                offer.selectionRevision(),
                                                offer.goodsRef(),
                                                offer.name(),
                                                offer.sectionKey(),
                                                offer.variants(),
                                                offer.images(),
                                                "LOCAL_OFFER"))
                        .toList();
        return CatalogV3PolicyTest.upgrade(
                new Document(
                        d.owner(),
                        d.currency(),
                        d.sources(),
                        d.sections(),
                        d.offers(),
                        d.schedules(),
                        d.validUntil(),
                        d.receiptNo(),
                        2,
                        new ChannelCatalogDetails(sections, offers, d.details().schedules())),
                "S");
    }

    String remote(CatalogResourceIndex.Key key) {
        return "MOCK_" + key.type().name() + "_" + Integer.toUnsignedString(key.value().hashCode());
    }

    Identity identity(Document d, ObjectType type) {
        var resource =
                CatalogResourceIndex.resources(d).stream()
                        .filter(r -> r.key().type() == type)
                        .findFirst()
                        .orElseThrow();
        return new Identity(
                remote(resource.key()),
                resource.parents().stream()
                        .map(parent -> new Parent(parent.type(), remote(parent)))
                        .toList());
    }

    void run(Consumer<State> assertions) {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            s.source().evidenceTtlSeconds = 20;
                            s.source().documentTransform = this::detailed;
                            s.gateway().availabilitySupported = true;
                            s.gateway().resultFactory =
                                    publication ->
                                            new ChannelCatalogGateway.Result(
                                                    publication.operationNo(),
                                                    publication.generation(),
                                                    publication.contentHash(),
                                                    ChannelCatalogGateway.Outcome.SUCCEEDED,
                                                    true,
                                                    "MOCK_TASK",
                                                    "MOCK_TERMINAL_RECEIPT",
                                                    CatalogResourceIndex.resources(
                                                                    publication.document())
                                                            .stream()
                                                            .map(
                                                                    resource ->
                                                                            new ChannelCatalogGateway
                                                                                    .ItemResult(
                                                                                    resource.key()
                                                                                            .type()
                                                                                            .name(),
                                                                                    resource.key()
                                                                                            .value(),
                                                                                    remote(
                                                                                            resource
                                                                                                    .key()),
                                                                                    resource
                                                                                            .sourceRef(),
                                                                                    "SUCCEEDED",
                                                                                    null,
                                                                                    "MOCK_ITEM:"
                                                                                            + remote(
                                                                                                    resource
                                                                                                            .key())))
                                                            .toList());
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
                            var preview =
                                    s.previews()
                                            .preview(
                                                    "E",
                                                    "S",
                                                    scope.scopeNo(),
                                                    "A",
                                                    new PreviewCatalogReq(scope.version()),
                                                    "preview");
                            var run =
                                    s.runs()
                                            .submit(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new SubmitSyncRunReq(
                                                            scope.scopeNo(),
                                                            preview.snapshotNo(),
                                                            preview.hash(),
                                                            scope.version(),
                                                            "publish"),
                                                    "publish");
                            s.recovery().recoverDue(10);
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT status FROM cim_sync_run WHERE"
                                                                + " run_no=?",
                                                            String.class,
                                                            run.runNo()))
                                    .isEqualTo("SUCCEEDED");
                            var order = new CimOrderLink();
                            order.setChannelOrderNo("HISTORICAL_ORDER");
                            order.setEntityNo("E");
                            order.setStoreNo("S");
                            order.setConnectionNo("CON");
                            order.setSourceOwner("FOOD");
                            assertions.accept(
                                    new State(
                                            s,
                                            preview.document(),
                                            order,
                                            s.context()
                                                    .getBean(
                                                            HistoricalManagedMappingService
                                                                    .class)));
                        });
    }

    @Test
    void oldGenerationCanAcquireHistoricalMappingsAfterTheCurrentProjectionWasSuperseded() {
        run(
                s -> {
                    s.app().sql().execute("UPDATE cim_catalog_scope SET generation=generation+1");
                    s.app().context().getBean(ManagedMappingProjectionService.class).recoverDue(10);
                    assertThat(
                                    s.app()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_resource_mapping",
                                                    Integer.class))
                            .isZero();
                    var session = s.history().session(s.order(), s.catalog());
                    var reference =
                            session.resolve(
                                    ObjectType.OFFER, identity(s.catalog(), ObjectType.OFFER));
                    assertThat(
                                    session.resolve(
                                            ObjectType.OFFER,
                                            identity(s.catalog(), ObjectType.OFFER)))
                            .isEqualTo(reference);
                    assertThat(
                                    s.app()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT active_revision_no FROM"
                                                        + " cim_resource_mapping",
                                                    String.class))
                            .isNull();
                    var snapshot =
                            s.app()
                                    .sql()
                                    .queryForObject(
                                            "SELECT mapping_snapshot FROM"
                                                + " cim_resource_mapping_revision",
                                            String.class);
                    var parsed = json.readTree(snapshot);
                    assertThat(parsed.get("historicalEvidence").get("basis").asString())
                            .isEqualTo("TERMINAL_PUBLICATION");
                    assertThat(parsed.get("externalProof").isNull()).isTrue();
                    assertThat(
                                    parsed.get("sourceProof")
                                            .get("canonicalContext")
                                            .get("mode")
                                            .asString())
                            .isEqualTo("HISTORICAL");
                });
    }

    @Test
    void historicalAppendDoesNotReplaceCurrentVerifiedOrWorkingPointersAndKeepsSequenceMonotonic() {
        run(
                s -> {
                    s.app().context().getBean(ManagedMappingProjectionService.class).recoverDue(10);
                    s.app()
                            .context()
                            .getBean(ResourceMappingVerificationService.class)
                            .verifyDue(100);
                    var before =
                            s.app()
                                    .sql()
                                    .queryForList(
                                            "SELECT"
                                                + " mapping_no,latest_revision_no,active_revision_no"
                                                + " FROM cim_resource_mapping ORDER BY id");
                    s.history()
                            .session(s.order(), s.catalog())
                            .resolve(ObjectType.OFFER, identity(s.catalog(), ObjectType.OFFER));
                    assertThat(
                                    s.app()
                                            .sql()
                                            .queryForList(
                                                    "SELECT"
                                                        + " mapping_no,latest_revision_no,active_revision_no"
                                                        + " FROM cim_resource_mapping ORDER BY id"))
                            .isEqualTo(before);
                    assertThat(
                                    s.app()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM"
                                                        + " cim_resource_mapping_revision WHERE"
                                                        + " validation_status='VERIFIED' AND reason"
                                                        + " LIKE 'HISTORICAL_PUBLICATION:%'",
                                                    Integer.class))
                            .isOne();
                    assertThat(
                                    s.app()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM (SELECT"
                                                        + " mapping_no,revision,COUNT(*) n FROM"
                                                        + " cim_resource_mapping_revision GROUP BY"
                                                        + " mapping_no,revision HAVING COUNT(*)>1)"
                                                        + " duplicates",
                                                    Integer.class))
                            .isZero();
                });
    }

    @Test
    void aMissingParentReceiptOrChangedOriginalHashCannotCreateHistoricalAuthority() {
        run(
                s -> {
                    s.app()
                            .sql()
                            .execute("DELETE FROM cim_sync_run_item WHERE object_type='CATEGORY'");
                    assertThatThrownBy(
                                    () ->
                                            s.history()
                                                    .session(s.order(), s.catalog())
                                                    .resolve(
                                                            ObjectType.OFFER,
                                                            identity(
                                                                    s.catalog(), ObjectType.OFFER)))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(
                                    s.app()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_resource_mapping",
                                                    Integer.class))
                            .isZero();
                });
        run(
                s -> {
                    s.app().source().hashChanged = true;
                    assertThatThrownBy(
                                    () ->
                                            s.history()
                                                    .session(s.order(), s.catalog())
                                                    .resolve(
                                                            ObjectType.OFFER,
                                                            identity(
                                                                    s.catalog(), ObjectType.OFFER)))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(
                                    s.app()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_resource_mapping",
                                                    Integer.class))
                            .isZero();
                });
    }

    @Test
    void moreThan100LegitimateHistoricalRevisionsDoNotDisableAllOrderResolution() {
        run(
                s -> {
                    var session = s.history().session(s.order(), s.catalog());
                    var offer =
                            session.resolve(
                                    ObjectType.OFFER, identity(s.catalog(), ObjectType.OFFER));
                    session.resolve(ObjectType.SKU, identity(s.catalog(), ObjectType.SKU));
                    var mapper = s.app().context().getBean(ResourceMappingRevisionMapper.class);
                    var original =
                            mapper.selectOne(
                                    Wrappers.<CimResourceMappingRevision>query()
                                            .eq("revision_no", offer.revisionNo()));
                    for (int i = 0; i < 120; i++) {
                        var revision = new CimResourceMappingRevision();
                        BeanUtils.copyProperties(original, revision);
                        revision.setId(null);
                        revision.setRevisionNo("HISTORY" + i);
                        revision.setRevision(original.getRevision() + i + 1);
                        mapper.insert(revision);
                    }
                    var line =
                            new ChannelOrderGateway.Line(
                                    "LINE",
                                    identity(s.catalog(), ObjectType.OFFER),
                                    identity(s.catalog(), ObjectType.SKU),
                                    "1",
                                    "EA",
                                    100,
                                    0,
                                    0,
                                    100,
                                    List.of(),
                                    null);
                    var order =
                            new ChannelOrderGateway.Document(
                                    1,
                                    "REMOTE_ACCOUNT",
                                    "REMOTE_STORE",
                                    "EXTERNAL",
                                    "R1",
                                    1L,
                                    CatalogDocumentPolicy.hash(s.catalog(), json),
                                    "TWD",
                                    ChannelOrderGateway.State.ACCEPTED,
                                    ChannelOrderGateway.PaymentState.PLATFORM_PAID,
                                    "REMOTE_PAYMENT",
                                    ChannelOrderGateway.Fulfillment.INSTANT_DELIVERY,
                                    Instant.now(),
                                    null,
                                    null,
                                    List.of(line),
                                    new ChannelOrderGateway.Totals(0, 0, 0, 0, 100, 100));
                    assertThat(
                                    s.app()
                                            .context()
                                            .getBean(ChannelOrderMappingResolver.class)
                                            .resolve(s.order(), order))
                            .hasSize(1);
                });
    }
}
