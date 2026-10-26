package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration;
import ai.neargo.shop.commerce.dto.CatalogContracts.CancelQueuedSyncReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveCatalogSourcesReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveSyncPolicyReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.service.CatalogPublicationService;
import ai.neargo.shop.commerce.service.CatalogScopeService;
import ai.neargo.shop.commerce.service.CommerceRecoveryService;
import ai.neargo.shop.commerce.service.SyncPolicyService;
import ai.neargo.shop.commerce.service.SyncRunService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.sql.DataSource;

class CatalogSynchronizationTest {
    static class Source implements ChannelCatalogSourcePort, ChannelSourceValidationPort {
        boolean detailed;
        boolean stale;
        boolean outage;
        boolean negativePrice;
        boolean hashChanged;
        boolean legacyOfferProof;
        Owner sourceOwner = Owner.FOOD;
        int captures;
        Runnable captureHook;

        public Owner owner() {
            return sourceOwner;
        }

        public Validation validate(ChannelSourceValidationPort.Source request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (outage) throw new IllegalStateException("source unavailable");
            return new Validation(
                    stale ? Status.STALE : Status.VALID,
                    request.sourceRef(),
                    request.sourceRevision(),
                    (hashChanged ? "b" : "a").repeat(64),
                    Instant.now().plusSeconds(600),
                    "OWNER_RECEIPT",
                    legacyOfferProof ? null : request.salesOfferRef());
        }

        public Document capture(CaptureCommand request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            captures++;
            if (captureHook != null) captureHook.run();
            var document =
                    new Document(
                            request.owner(),
                            "TWD",
                            request.sources(),
                            List.of(new Section("CAT", "Menu", 0)),
                            List.of(
                                    new Offer(
                                            "OFFER",
                                            request.sources().getFirst().sourceRef(),
                                            request.sources().getFirst().selectionRevision(),
                                            "Tea",
                                            "CAT",
                                            List.of(
                                                    new Sku(
                                                            "SKU",
                                                            "Regular",
                                                            price(negativePrice ? -1 : 100),
                                                            "STOCK_POLICY")),
                                            List.of(
                                                    new OptionGroup(
                                                            "TOPPINGS",
                                                            "Toppings",
                                                            0,
                                                            2,
                                                            List.of(
                                                                    new OptionChoice(
                                                                            "PEARL",
                                                                            "Pearl",
                                                                            price(20),
                                                                            "TOPPING_STOCK",
                                                                            0,
                                                                            2)))),
                                            List.of(),
                                            List.of())),
                            List.of(),
                            Instant.now().plusSeconds(600),
                            "CATALOG_RECEIPT");
            return detailed ? CatalogDetailsPolicyTest.fromLegacy(document) : document;
        }

        private Price price(long amount) {
            return new Price(amount, "TWD", "PRICE", "R1", "TAX", "PRICE_RECEIPT");
        }
    }

    static class Gateway implements ChannelCatalogGateway {
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        Outcome next = Outcome.SUBMITTED;
        boolean terminal;
        boolean detailedSupported;
        Publication lastPublication;

        public boolean supportsDocumentSchema(int schema) {
            return schema == 1 || schema == 2 && detailedSupported;
        }

        boolean timeout;
        Runnable sendHook;
        Outcome queryNext;
        boolean queryTerminal;

        public String profileNo() {
            return "PP";
        }

        public Set<Capability> capabilities() {
            return Set.of(Capability.CATALOG);
        }

        public boolean supportsQuery() {
            return true;
        }

        public Result publish(Publication request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends.incrementAndGet();
            lastPublication = request;
            if (sendHook != null) sendHook.run();
            if (timeout) throw new IllegalStateException("external timeout");
            return result(request);
        }

        public Result query(Publication request, String taskRef) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            queries.incrementAndGet();
            if (queryNext != null)
                return new Result(
                        request.operationNo(),
                        request.generation(),
                        request.contentHash(),
                        queryNext,
                        queryTerminal,
                        "REMOTE_TASK",
                        "QUERY_RECEIPT",
                        List.of());
            return result(request);
        }

        private Result result(Publication request) {
            return new Result(
                    request.operationNo(),
                    request.generation(),
                    request.contentHash(),
                    next,
                    terminal,
                    "REMOTE_TASK",
                    "PROVIDER_RECEIPT",
                    List.of());
        }
    }

    record State(
            JdbcTemplate sql,
            CatalogScopeService scopes,
            CatalogPublicationService previews,
            SyncRunService runs,
            SyncPolicyService policies,
            CommerceRecoveryService recovery,
            Source source,
            Gateway gateway,
            ApplicationContext context) {}

    void run(Consumer<State> test) {
        run(Owner.FOOD, test);
    }

    private void run(Owner owner, Consumer<State> test) {
        run(owner, true, test);
    }

    void runWithoutPublishing(Consumer<State> test) {
        run(Owner.FOOD, false, test);
    }

    private void run(Owner owner, boolean publishing, Consumer<State> test) {
        var source = new Source();
        source.sourceOwner = owner;
        var gateway = new Gateway();
        var runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(PrimaryPersistenceConfiguration.class)
                        .withConfiguration(
                                AutoConfigurations.of(CommerceCoreAutoConfiguration.class))
                        .withBean(Source.class, () -> source)
                        .withBean(
                                ChannelResourceIdentityGateway.class,
                                () -> new ResourceMappingTest.Gateway())
                        .withPropertyValues(
                                "shop.commerce.enabled=true",
                                "shop.commerce.migration=MIGRATE",
                                "spring.datasource.url=jdbc:h2:mem:"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                                "spring.datasource.username=sa",
                                "spring.datasource.password=");
        if (publishing) runner = runner.withBean(ChannelCatalogGateway.class, () -> gateway);
        runner.run(
                c -> {
                    assertThat(c).hasNotFailed();
                    var sql = new JdbcTemplate(c.getBean(DataSource.class));
                    sql.update(
                            "INSERT INTO"
                                    + " cim_provider(provider_no,provider_code,name,status)"
                                    + " VALUES ('P','TEST','Test','ACTIVE')");
                    sql.update(
                            "INSERT INTO"
                                + " cim_product_profile(profile_no,provider_no,product_code,market,protocol_code,capability_definition,identity_schema_version,identity_namespace,store_identity_scope,status)"
                                + " VALUES"
                                + " ('PP','P','DELIVERY','TW','TEST','{}',1,'test:TW','GLOBAL','VERIFIED')");
                    sql.update(
                            "INSERT INTO"
                                + " cim_account(account_no,entity_no,profile_no,external_account_ref,active_authorization_no,status)"
                                + " VALUES ('ACC','E','PP','REMOTE_ACCOUNT','AUTH','ACTIVE')");
                    sql.update(
                            "INSERT INTO"
                                + " cim_authorization_revision(authorization_no,entity_no,account_no,revision_no,credential_ref,granted_scopes,capability_hash,authorized_at,expires_at,status,evidence_ref)"
                                + " VALUES ('AUTH','E','ACC',1,'vault:ref','[\"CATALOG\"]',?, ?, ?,"
                                + " 'GRANTED','GRANT_RECEIPT')",
                            "a".repeat(64),
                            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                            LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
                    sql.update(
                            "INSERT INTO"
                                + " cim_store_connection(connection_no,entity_no,store_no,profile_no,account_no,external_store_ref,channel_store_key,active_revision_no,status,identity_receipt_ref,identity_verified_at,identity_valid_until)"
                                + " VALUES"
                                + " ('CON','E','S','PP','ACC','REMOTE_STORE',?,'CR','ACTIVE','IDENTITY',?,?)",
                            "c".repeat(64),
                            LocalDateTime.now(ZoneOffset.UTC),
                            LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
                    sql.update(
                            "INSERT INTO"
                                + " cim_connection_revision(revision_no,entity_no,connection_no,revision,source_owner,accept_mode,fulfillment_profile,catalog_authority,config_json,config_hash)"
                                + " VALUES ('CR','E','CON',1,?,'MANUAL','INSTANT','LOCAL','{}',?)",
                            owner.name(),
                            "d".repeat(64));
                    test.accept(
                            new State(
                                    sql,
                                    c.getBean(CatalogScopeService.class),
                                    c.getBean(CatalogPublicationService.class),
                                    c.getBean(SyncRunService.class),
                                    c.getBean(SyncPolicyService.class),
                                    c.getBean(CommerceRecoveryService.class),
                                    source,
                                    gateway,
                                    c.getSourceApplicationContext()));
                });
    }

    private CreateCatalogScopeReq create(String remote, Owner owner) {
        return new CreateCatalogScopeReq(
                "CON", remote, "Delivery menu", List.of(new SourceReq(owner, "MENU", "R1", 0)));
    }

    @Test
    void fullStoreScopeSourcesAndSnapshotAreVersionedAndScoped() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope(
                                    "E", "S", "A", create("STORE", Owner.FOOD), "create");
                    assertThat(
                                    s.scopes.createScope(
                                            "E", "S", "A", create("STORE", Owner.FOOD), "create"))
                            .isEqualTo(scope);
                    assertThatThrownBy(
                                    () ->
                                            s.scopes.createScope(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    create("BREAKFAST", Owner.FOOD),
                                                    "other"))
                            .isInstanceOf(BizException.class);
                    assertThatThrownBy(
                                    () ->
                                            s.scopes.createScope(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    create("STORE", Owner.RETAIL),
                                                    "mixed"))
                            .isInstanceOf(BizException.class);
                    assertThatThrownBy(() -> s.scopes.getScope("OTHER", "S", scope.scopeNo()))
                            .isInstanceOf(BizException.class);
                    var preview =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "preview");
                    assertThat(preview.itemCount()).isEqualTo(1);
                    assertThat(preview.currency()).isEqualTo("TWD");
                    assertThat(
                                    preview.document()
                                            .offers()
                                            .getFirst()
                                            .optionGroups()
                                            .getFirst()
                                            .choices()
                                            .getFirst()
                                            .price()
                                            .amountMinor())
                            .isEqualTo(20);
                    assertThat(s.gateway.sends).hasValue(0);
                });
    }

    @Test
    void changedOrExpiredEvidenceCannotBeSubmittedAndQueuedCancelNeverCallsGateway() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var preview =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "p");
                    s.source.hashChanged = true;
                    var req =
                            new SubmitSyncRunReq(
                                    scope.scopeNo(),
                                    preview.snapshotNo(),
                                    preview.hash(),
                                    scope.version(),
                                    "publish");
                    assertThatThrownBy(() -> s.runs.submit("E", "S", "A", req, "bad"))
                            .isInstanceOf(BizException.class);
                    s.source.hashChanged = false;
                    var run = s.runs.submit("E", "S", "A", req, "good");
                    assertThat(run.generation()).isNull();
                    assertThat(run.status()).isEqualTo("QUEUED");
                    var cancelled =
                            s.runs.cancelQueued(
                                    "E",
                                    "S",
                                    run.runNo(),
                                    "A",
                                    new CancelQueuedSyncReq(run.version(), "cancel"),
                                    "cancel");
                    assertThat(cancelled.status()).isEqualTo("CANCELLED");
                    assertThat(s.gateway.sends).hasValue(0);
                });
    }

    @Test
    void submittedAndUnknownKeepRemoteBarrierUntilTerminalProof() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var p =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "p");
                    var a =
                            s.runs.submit(
                                    "E",
                                    "S",
                                    "A",
                                    new SubmitSyncRunReq(
                                            scope.scopeNo(),
                                            p.snapshotNo(),
                                            p.hash(),
                                            scope.version(),
                                            "first"),
                                    "a");
                    s.recovery.recoverDue(20);
                    assertThat(s.gateway.sends).hasValue(1);
                    var pending = s.scopes.getScope("E", "S", scope.scopeNo());
                    assertThat(pending.activeOperationNo()).isNotNull();
                    assertThat(pending.generation()).isEqualTo(1);
                    var p2 =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(pending.version()),
                                    "p2");
                    s.runs.submit(
                            "E",
                            "S",
                            "A",
                            new SubmitSyncRunReq(
                                    scope.scopeNo(),
                                    p2.snapshotNo(),
                                    p2.hash(),
                                    pending.version(),
                                    "second"),
                            "b");
                    s.recovery.recoverDue(20);
                    assertThat(s.gateway.sends).hasValue(1);
                    s.sql.update(
                            "UPDATE cim_sync_run SET next_recovery_at=? WHERE run_no=?",
                            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                            a.runNo());
                    s.gateway.queryNext = ChannelCatalogGateway.Outcome.SUCCEEDED;
                    s.gateway.queryTerminal = true;
                    s.recovery.recoverDue(20);
                    assertThat(s.gateway.queries.get()).isGreaterThan(0);
                    var historical = s.scopes.getScope("E", "S", scope.scopeNo());
                    assertThat(historical.lastConfirmedSnapshotNo()).isEqualTo(p.snapshotNo());
                    assertThat(historical.remoteConsistency()).isNotEqualTo("CONSISTENT");
                });
    }

    @Test
    void timeoutRecoveryQueriesOriginalActionInsteadOfResending() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var p =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "p");
                    var run =
                            s.runs.submit(
                                    "E",
                                    "S",
                                    "A",
                                    new SubmitSyncRunReq(
                                            scope.scopeNo(),
                                            p.snapshotNo(),
                                            p.hash(),
                                            scope.version(),
                                            "send"),
                                    "s");
                    s.gateway.timeout = true;
                    s.recovery.recoverDue(10);
                    assertThat(s.runs.getRun("E", "S", run.runNo()).status()).isEqualTo("UNKNOWN");
                    s.sql.update(
                            "UPDATE cim_sync_run SET next_recovery_at=?",
                            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
                    s.gateway.timeout = false;
                    s.gateway.next = ChannelCatalogGateway.Outcome.PARTIAL;
                    s.gateway.terminal = true;
                    s.recovery.recoverDue(10);
                    assertThat(s.gateway.sends).hasValue(1);
                    assertThat(s.gateway.queries).hasValue(1);
                    assertThat(s.scopes.getScope("E", "S", scope.scopeNo()).activeOperationNo())
                            .isNull();
                    assertThat(
                                    s.scopes
                                            .getScope("E", "S", scope.scopeNo())
                                            .lastConfirmedSnapshotNo())
                            .isNull();
                });
    }

    @Test
    void automaticPolicyIsOffByDefaultAndPublishNotificationSharesQueue() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var policy = s.policies.getPolicy("E", "S", scope.scopeNo());
                    assertThat(policy.enabled()).isFalse();
                    s.policies.savePolicy(
                            "E",
                            "S",
                            scope.scopeNo(),
                            "A",
                            new SaveSyncPolicyReq(
                                    policy.version(), true, true, false, true, 0, "QUERY_ONLY"),
                            "enable");
                    s.recovery.onPublished("E", "S", Owner.FOOD, "MENU", "R2", "EVENT1");
                    s.recovery.onPublished("E", "S", Owner.FOOD, "MENU", "R2", "EVENT1");
                    s.recovery.recoverDue(10);
                    assertThat(
                                    s.sql.queryForObject(
                                            "SELECT COUNT(*) FROM cim_sync_run", Long.class))
                            .isEqualTo(1);
                    assertThat(s.gateway.sends).hasValue(1);
                });
    }

    @Test
    void staleSourceAndInvalidOriginalPriceNeverProduceAUsablePreview() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    s.source.stale = true;
                    assertThatThrownBy(
                                    () ->
                                            s.previews.preview(
                                                    "E",
                                                    "S",
                                                    scope.scopeNo(),
                                                    "A",
                                                    new PreviewCatalogReq(scope.version()),
                                                    "p"))
                            .isInstanceOf(BizException.class);
                    s.source.stale = false;
                    s.source.negativePrice = true;
                    assertThatThrownBy(
                                    () ->
                                            s.previews.preview(
                                                    "E",
                                                    "S",
                                                    scope.scopeNo(),
                                                    "A",
                                                    new PreviewCatalogReq(scope.version()),
                                                    "p2"))
                            .isInstanceOf(BizException.class);
                    assertThat(
                                    s.sql.queryForObject(
                                            "SELECT COUNT(*) FROM cim_catalog_snapshot",
                                            Long.class))
                            .isZero();
                });
    }

    @Test
    void expiredSnapshotAndChangedSourceBlockDispatch() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var p =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "p");
                    var req =
                            new SubmitSyncRunReq(
                                    scope.scopeNo(),
                                    p.snapshotNo(),
                                    p.hash(),
                                    scope.version(),
                                    "publish");
                    s.sql.update(
                            "UPDATE cim_catalog_snapshot SET expires_at=?",
                            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
                    assertThatThrownBy(() -> s.runs.submit("E", "S", "A", req, "expired"))
                            .isInstanceOf(BizException.class);
                    s.sql.update(
                            "UPDATE cim_catalog_snapshot SET expires_at=?",
                            LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
                    var run = s.runs.submit("E", "S", "A", req, "valid");
                    s.source.hashChanged = true;
                    s.recovery.recoverDue(10);
                    assertThat(s.runs.getRun("E", "S", run.runNo()).status()).isEqualTo("FAILED");
                    assertThat(s.gateway.sends).hasValue(0);
                });
    }

    @Test
    void retailUsesSameKernelAndCannotMixFood() {
        run(
                Owner.RETAIL,
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.RETAIL), "c");
                    var p =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "p");
                    assertThat(p.document().owner()).isEqualTo(Owner.RETAIL);
                    assertThatThrownBy(
                                    () ->
                                            s.scopes.saveSources(
                                                    "E",
                                                    "S",
                                                    scope.scopeNo(),
                                                    "A",
                                                    new SaveCatalogSourcesReq(
                                                            scope.version(),
                                                            List.of(
                                                                    new SourceReq(
                                                                            Owner.FOOD,
                                                                            "MENU",
                                                                            "R1",
                                                                            0))),
                                                    "mixed"))
                            .isInstanceOf(BizException.class);
                    s.gateway.next = ChannelCatalogGateway.Outcome.SUCCEEDED;
                    s.gateway.terminal = true;
                    s.runs.submit(
                            "E",
                            "S",
                            "A",
                            new SubmitSyncRunReq(
                                    scope.scopeNo(),
                                    p.snapshotNo(),
                                    p.hash(),
                                    scope.version(),
                                    "publish"),
                            "submit");
                    s.recovery.recoverDue(10);
                    assertThat(s.scopes.getScope("E", "S", scope.scopeNo()).remoteConsistency())
                            .isEqualTo("CONSISTENT");
                });
    }

    @Test
    void lateSenderCannotOverwriteRecoveredPartialOrReleaseNewGeneration() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var p =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "p");
                    var run =
                            s.runs.submit(
                                    "E",
                                    "S",
                                    "A",
                                    new SubmitSyncRunReq(
                                            scope.scopeNo(),
                                            p.snapshotNo(),
                                            p.hash(),
                                            scope.version(),
                                            "publish"),
                                    "submit");
                    s.gateway.sendHook =
                            () -> {
                                s.sql.update(
                                        "UPDATE cim_sync_run SET next_recovery_at=?",
                                        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
                                s.sql.update(
                                        "UPDATE cim_operation SET lease_until=?",
                                        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
                                s.gateway.next = ChannelCatalogGateway.Outcome.PARTIAL;
                                s.gateway.terminal = true;
                                s.recovery.recoverDue(10);
                                s.gateway.next = ChannelCatalogGateway.Outcome.SUCCEEDED;
                            };
                    s.recovery.recoverDue(10);
                    assertThat(s.runs.getRun("E", "S", run.runNo()).status()).isEqualTo("PARTIAL");
                    assertThat(
                                    s.scopes
                                            .getScope("E", "S", scope.scopeNo())
                                            .lastConfirmedSnapshotNo())
                            .isNull();
                    assertThat(
                                    s.sql.queryForObject(
                                            "SELECT late_outcome FROM cim_operation_attempt WHERE"
                                                    + " attempt_no=1",
                                            String.class))
                            .isEqualTo("SUCCEEDED");
                    assertThat(s.gateway.sends).hasValue(1);
                    assertThat(s.gateway.queries).hasValue(1);
                });
    }

    @Test
    void previewDoesNotPermitChangingScopeOrPublishingWithoutGrant() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var p =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "p");
                    var changed =
                            s.scopes.saveSources(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new SaveCatalogSourcesReq(
                                            scope.version(),
                                            List.of(new SourceReq(Owner.FOOD, "MENU", "R2", 0))),
                                    "change");
                    assertThat(changed.version()).isGreaterThan(scope.version());
                    assertThatThrownBy(
                                    () ->
                                            s.runs.submit(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new SubmitSyncRunReq(
                                                            scope.scopeNo(),
                                                            p.snapshotNo(),
                                                            p.hash(),
                                                            changed.version(),
                                                            "publish"),
                                                    "submit"))
                            .isInstanceOf(BizException.class);
                    var p2 =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(changed.version()),
                                    "p2");
                    s.sql.update("UPDATE cim_account SET status='REVOKED'");
                    assertThatThrownBy(
                                    () ->
                                            s.runs.submit(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new SubmitSyncRunReq(
                                                            scope.scopeNo(),
                                                            p2.snapshotNo(),
                                                            p2.hash(),
                                                            changed.version(),
                                                            "publish"),
                                                    "no-grant"))
                            .isInstanceOf(BizException.class);
                    assertThat(s.gateway.sends).hasValue(0);
                });
    }

    @Test
    void policyCannotPretendToSupportAvailabilityAndDuplicateEventsCannotChangePayload() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var policy = s.policies.getPolicy("E", "S", scope.scopeNo());
                    assertThatThrownBy(
                                    () ->
                                            s.policies.savePolicy(
                                                    "E",
                                                    "S",
                                                    scope.scopeNo(),
                                                    "A",
                                                    new SaveSyncPolicyReq(
                                                            policy.version(),
                                                            true,
                                                            true,
                                                            true,
                                                            true,
                                                            0,
                                                            "QUERY_ONLY"),
                                                    "bad"))
                            .isInstanceOf(BizException.class);
                    s.policies.savePolicy(
                            "E",
                            "S",
                            scope.scopeNo(),
                            "A",
                            new SaveSyncPolicyReq(
                                    policy.version(), true, true, false, true, 0, "QUERY_ONLY"),
                            "good");
                    s.recovery.onPublished("E", "S", Owner.FOOD, "MENU", "R2", "EVENT");
                    assertThatThrownBy(
                                    () ->
                                            s.recovery.onPublished(
                                                    "E", "S", Owner.FOOD, "MENU", "R3", "EVENT"))
                            .isInstanceOf(BizException.class);
                    assertThat(
                                    s.scopes
                                            .getScope("E", "S", scope.scopeNo())
                                            .sources()
                                            .getFirst()
                                            .selectionRevision())
                            .isEqualTo("R2");
                });
    }

    @Test
    void blockedQueueCannotStarveOriginalOperationRecoveryAtLimitOne() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var p =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "p");
                    var req =
                            new SubmitSyncRunReq(
                                    scope.scopeNo(),
                                    p.snapshotNo(),
                                    p.hash(),
                                    scope.version(),
                                    "publish");
                    var first = s.runs.submit("E", "S", "A", req, "a");
                    s.recovery.recoverDue(1);
                    s.runs.submit("E", "S", "A", req, "b");
                    s.sql.update(
                            "UPDATE cim_sync_run SET next_recovery_at=? WHERE status='QUEUED'",
                            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2));
                    s.sql.update(
                            "UPDATE cim_sync_run SET next_recovery_at=? WHERE run_no=?",
                            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                            first.runNo());
                    s.gateway.next = ChannelCatalogGateway.Outcome.SUCCEEDED;
                    s.gateway.terminal = true;
                    s.recovery.recoverDue(1);
                    assertThat(s.gateway.queries).hasValue(1);
                    assertThat(s.runs.getRun("E", "S", first.runNo()).status())
                            .isEqualTo("SUCCEEDED");
                });
    }

    @Test
    void oldSuccessIsHistoricalAndCannotConfirmChangedSourceConfiguration() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var p =
                            s.previews.preview(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new PreviewCatalogReq(scope.version()),
                                    "p");
                    var first =
                            s.runs.submit(
                                    "E",
                                    "S",
                                    "A",
                                    new SubmitSyncRunReq(
                                            scope.scopeNo(),
                                            p.snapshotNo(),
                                            p.hash(),
                                            scope.version(),
                                            "publish"),
                                    "a");
                    s.recovery.recoverDue(10);
                    s.scopes.saveSources(
                            "E",
                            "S",
                            scope.scopeNo(),
                            "A",
                            new SaveCatalogSourcesReq(
                                    scope.version(),
                                    List.of(new SourceReq(Owner.FOOD, "MENU", "R2", 0))),
                            "change");
                    s.sql.update(
                            "UPDATE cim_sync_run SET next_recovery_at=? WHERE run_no=?",
                            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                            first.runNo());
                    s.gateway.next = ChannelCatalogGateway.Outcome.SUCCEEDED;
                    s.gateway.terminal = true;
                    s.recovery.recoverDue(10);
                    var current = s.scopes.getScope("E", "S", scope.scopeNo());
                    assertThat(current.lastConfirmedSnapshotNo()).isEqualTo(p.snapshotNo());
                    assertThat(current.remoteConsistency()).isEqualTo("STALE");
                });
    }

    @Test
    void disablingPolicyDuringCapturePreventsAutomaticRunCreation() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var p = s.policies.getPolicy("E", "S", scope.scopeNo());
                    var enabled =
                            s.policies.savePolicy(
                                    "E",
                                    "S",
                                    scope.scopeNo(),
                                    "A",
                                    new SaveSyncPolicyReq(
                                            p.version(), true, true, false, true, 0, "QUERY_ONLY"),
                                    "enable");
                    s.recovery.onPublished("E", "S", Owner.FOOD, "MENU", "R2", "EVENT");
                    s.source.captureHook =
                            () -> {
                                var current = s.policies.getPolicy("E", "S", scope.scopeNo());
                                s.policies.savePolicy(
                                        "E",
                                        "S",
                                        scope.scopeNo(),
                                        "A",
                                        new SaveSyncPolicyReq(
                                                current.version(),
                                                false,
                                                true,
                                                false,
                                                true,
                                                0,
                                                "QUERY_ONLY"),
                                        "disable");
                            };
                    s.recovery.recoverDue(10);
                    assertThat(
                                    s.sql.queryForObject(
                                            "SELECT COUNT(*) FROM cim_sync_run", Long.class))
                            .isZero();
                    assertThat(s.gateway.sends).hasValue(0);
                    assertThat(s.policies.getPolicy("E", "S", scope.scopeNo()).enabled()).isFalse();
                });
    }

    @Test
    void scheduledRepairCannotSkipChangedConfigurationWithIdenticalContent() {
        run(
                s -> {
                    var scope =
                            s.scopes.createScope("E", "S", "A", create("STORE", Owner.FOOD), "c");
                    var p = s.policies.getPolicy("E", "S", scope.scopeNo());
                    s.policies.savePolicy(
                            "E",
                            "S",
                            scope.scopeNo(),
                            "A",
                            new SaveSyncPolicyReq(
                                    p.version(), true, true, false, true, 0, "QUERY_ONLY"),
                            "enable");
                    s.recovery.onPublished("E", "S", Owner.FOOD, "MENU", "R2", "EVENT");
                    s.gateway.next = ChannelCatalogGateway.Outcome.SUCCEEDED;
                    s.gateway.terminal = true;
                    s.recovery.recoverDue(10);
                    var current = s.scopes.getScope("E", "S", scope.scopeNo());
                    s.scopes.saveSources(
                            "E",
                            "S",
                            scope.scopeNo(),
                            "A",
                            new SaveCatalogSourcesReq(current.version(), current.sources()),
                            "same-content");
                    s.sql.update(
                            "UPDATE cim_sync_policy SET next_check_at=?",
                            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
                    s.recovery.recoverDue(10);
                    assertThat(s.gateway.sends).hasValue(2);
                    assertThat(s.scopes.getScope("E", "S", scope.scopeNo()).remoteConsistency())
                            .isEqualTo("CONSISTENT");
                });
    }
}
