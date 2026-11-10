package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.ActualOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AppliedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.InitialEffects;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.InitialProjection;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.InitialSegment;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.PermanentUnit;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Request;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Segment;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.WorkflowAuthorityLookup;
import ai.neargo.shop.spi.trade.ExecutionCompletionContract;
import ai.neargo.shop.spi.trade.ExecutionCompletionContract.CompleteExecution;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionStartContract;
import ai.neargo.shop.spi.trade.ExecutionStartContract.AcknowledgeResourceStart;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.IntentState;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceContinuity;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import ai.neargo.shop.trade.entity.OrdCheckoutLine;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdManualServiceAdmission;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdServiceExecution;
import ai.neargo.shop.trade.entity.OrdServiceExecutionAction;
import ai.neargo.shop.trade.entity.OrdServiceExecutionUnit;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import ai.neargo.shop.trade.mapper.ServiceExecutionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.order.service.create.CheckoutClock;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.trade.service.ServiceExecutionService.ExecutionCoordinates;
import com.baomidou.mybatisplus.annotation.TableField;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real Core service, source-root locks, execution/unit/action SQL and transactions. H2 only adapts physical DDL;
 * signed resource/stock/source ports represent independent owners. Native schema and races have separate fixtures.
 */
class ServiceExecutionLifecycleIntegrationTest {
    private static final String H = "a".repeat(64);
    private static final String NAMESPACE = IdempotencyScope.requestHash("BEAUTY_EXECUTION_ACTOR_V1",
            "MAIN", "E", "S", "MERCHANT", "MCH", "ACCOUNT");
    private static final Instant NOW = Instant.parse("2026-11-10T02:00:00Z");
    private final ExecutionScope scope = new ExecutionScope("MAIN", "E", "S");
    private final PersonnelOperatorFactsPort personnelOwner = mock(PersonnelOperatorFactsPort.class);
    private final ServiceExecutionSourcePort sourceOwner = mock(ServiceExecutionSourcePort.class);
    private final ResourceExecutionEvidencePort resources = mock(ResourceExecutionEvidencePort.class);
    private final ExecutionStockRetentionPort retained = mock(ExecutionStockRetentionPort.class);
    private final ResourceOccupationLifecyclePort lifecycle = mock(ResourceOccupationLifecyclePort.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private TransactionTemplate writes;
    private ServiceExecutionServiceImpl service;
    private ManualAdmissionMapper admissions;
    private ExecutionBinding binding;
    private StartIntent intent;
    private ExecutionReference reference;
    private CoreResourceProofFixture resourceFixture;
    private AcknowledgeResourceStart ack;
    private CompleteExecution completion;
    private Result endResult;

    @BeforeEach
    void fixture() throws Exception {
        var data = new DriverManagerDataSource("jdbc:h2:mem:core_lifecycle_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(data);
        create("ord_order", OrdOrder.class);
        create("ord_sub_order", OrdSubOrder.class);
        create("ord_checkout_operation", OrdCheckoutOperation.class);
        create("ord_checkout_line", OrdCheckoutLine.class);
        create("ord_manual_service_admission", OrdManualServiceAdmission.class);
        create("ord_service_execution", OrdServiceExecution.class);
        create("ord_service_execution_unit", OrdServiceExecutionUnit.class);
        create("ord_service_execution_action", OrdServiceExecutionAction.class);
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        for (var mapper : List.of(CheckoutMappers.OperationMapper.class, ManualAdmissionMapper.class,
                ServiceExecutionMapper.class, ServiceExecutionMapper.UnitMapper.class,
                ServiceExecutionMapper.ActionMapper.class)) config.addMapper(mapper);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(data); factory.setConfiguration(config); factory.afterPropertiesSet();
        var sql = new SqlSessionTemplate(factory.getObject());
        var schema = mock(ServiceExecutionSchemaVerifier.class);
        when(schema.installation()).thenReturn(ServiceExecutionSchemaVerifier.Installation.INSTALLED);
        var manager = new DataSourceTransactionManager(data);
        writes = new TransactionTemplate(manager);
        admissions = sql.getMapper(ManualAdmissionMapper.class);
        service = new ServiceExecutionServiceImpl(sql.getMapper(CheckoutMappers.OperationMapper.class),
                mock(CheckoutMappers.QuoteMapper.class), mock(CheckoutMappers.ConfirmationMapper.class),
                mock(CheckoutMappers.LineMapper.class), mock(OrderItemMapper.class), admissions,
                sql.getMapper(ServiceExecutionMapper.class), sql.getMapper(ServiceExecutionMapper.UnitMapper.class),
                sql.getMapper(ServiceExecutionMapper.ActionMapper.class), provider(ServiceExecutionSourcePort.class, sourceOwner),
                provider(PersonnelOperatorFactsPort.class, personnelOwner),
                mock(StockDispositionPort.class), schema, json,
                new CheckoutClock(provider(Clock.class, Clock.fixed(NOW, ZoneOffset.UTC))), manager);
        service.setStartEvidenceOwners(provider(ResourceExecutionEvidencePort.class, resources),
                provider(ExecutionStockRetentionPort.class, retained));
        service.setCompletionEvidenceOwner(provider(ResourceOccupationLifecyclePort.class, lifecycle));
        seedPreparedOriginal();
        reference = DelegatedIdentityContext.call(CoreResourceProofFixture.ACTOR,
                () -> service.referenceOriginal(new ExecutionLookup(binding)));
        var original = service.queryOriginalEvidence(new ExecutionEvidenceLookup(reference));
        original.validate();
        resourceFixture = CoreResourceProofFixture.create(original, CoreResourceProofFixture.resourcePlan(NOW), NOW);
        var applied = applied(resourceFixture);
        when(resources.queryOriginal(any())).thenReturn(applied);
        when(retained.queryOriginalV2(any())).thenReturn(resourceFixture.retention());
        ack = ExecutionStartContract.request(reference, applied.original(), "CORE_ACK");
        var end = new Request(new WorkflowAuthorityLookup(reference, "END_SOURCE", H),
                applied.original(), "OCC", 1, ResourceOccupationLifecycleContract.Action.END,
                "END_SOURCE", H, null, List.of(), List.of()).signed();
        completion = ExecutionCompletionContract.request(reference, end, "END_SOURCE");
        endResult = new Result(end, "END_COMMAND", "END_RESULT", 2, "CLEANING", NOW.plusSeconds(1800),
                NOW.plusSeconds(1800), List.of(
                    new Segment("SEG_STAFF", "STAFF_SLOT", "STAFF1", "PERSON", 1, NOW, NOW.plusSeconds(1800), false, null),
                    new Segment("SEG_ROOM", "ROOM_SLOT", "ROOM1", null, 1, NOW, NOW.plusSeconds(1800), true, null)),
                null, null).signed();
        endResult.validate();
        when(lifecycle.queryOriginal(any())).thenReturn(Optional.of(endResult));
    }

    @Test
    void realStartThenCompletionReplaysWithoutDoublePerformingAndOriginalsStayReadable() {
        var started = service.markStarted(ack); started.validate();
        assertThat(service.markStarted(ack)).isEqualTo(started);
        assertThat(state("ord_service_execution")).isEqualTo("STARTED");
        assertThat(state("ord_service_execution_unit")).isEqualTo("RESERVED");
        var result = service.completeExecution(completion); result.validate();
        assertThat(service.completeExecution(completion)).isEqualTo(result);
        assertThat(service.queryCompletionOriginal(new ExecutionEvidenceLookup(reference))).isEqualTo(result);
        assertThat(service.queryStartOriginal(new ExecutionEvidenceLookup(reference))).isEqualTo(started);
        var original = service.queryOriginalEvidence(new ExecutionEvidenceLookup(reference)); original.validate();
        assertThat(original.facts().unitState().name()).isEqualTo("PERFORMED");
        assertThat(original.facts().currentExecutionNo()).isNull();
        assertThat(original.facts().prepareResult()).isEqualTo(resourceFixture.core().facts().prepareResult());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_service_execution_action WHERE kind='FULFILL'", Long.class))
                .isEqualTo(1);
        assertThat(state("ord_service_execution_unit")).isEqualTo("PERFORMED");
    }

    @Test
    void unknownEndNeverChangesExecutionUnitOrCompletionJournal() {
        service.markStarted(ack);
        when(lifecycle.queryOriginal(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeExecution(completion)).isInstanceOf(BizException.class);
        assertThat(state("ord_service_execution")).isEqualTo("STARTED");
        assertThat(state("ord_service_execution_unit")).isEqualTo("RESERVED");
        assertThat(service.queryCompletionOriginal(new ExecutionEvidenceLookup(reference)).outcome())
                .isEqualTo(ExecutionCompletionContract.Outcome.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_service_execution_action WHERE kind='FULFILL'", Long.class))
                .isZero();
    }

    @Test
    void sourceSuspensionPreventsNewWorkButRetainsTheAlreadyEndedCountFact() {
        service.markStarted(ack);
        writes.executeWithoutResult(tx -> {
            admissions.lockParent("ORDER"); admissions.lockChildren("ORDER");
            assertThat(service.requestSuspendLocked(new ExecutionCoordinates(scope, "BEAUTY", "OP", "ORDER", "CHILD"),
                    "SOURCE_SUSPENDED")).isEqualTo(1);
        });
        assertThat(state("ord_service_execution")).isEqualTo("STARTED_SUSPENDED");
        var result = service.completeExecution(completion); result.validate();
        assertThat(result.proof().previousState()).isEqualTo("STARTED_SUSPENDED");
        assertThat(result.proof().sourceState()).isEqualTo("SUSPENDED");
        assertThat(result.proof().reason()).isEqualTo("SOURCE_SUSPENDED");
        assertThat(state("ord_service_execution")).isEqualTo("FULFILLED");
        assertThat(state("ord_service_execution_unit")).isEqualTo("PERFORMED");
    }

    @Test
    void aDifferentOriginalOrAttemptCannotReachTheOwnersCompletion() {
        service.markStarted(ack);
        var other = new ExecutionReference(reference.schema(), scope, reference.sourceKind(), reference.bindingSchema(),
                reference.executionNo(), reference.grantNo(), reference.unitNo(), reference.taskNo(), reference.workOrderNo(), 2,
                reference.preparedGrantRevision(), reference.bindingHash(), reference.payloadHash(), reference.originalBindingBytesHash(),
                reference.sourceIdentityHash(), reference.sourceIntentHash(), reference.sourceFenceNo(), reference.sourceFenceRevision(),
                reference.planHash(), reference.plannedResourceCommandNo(), reference.plannedResourceCommandHash(),
                reference.prepareActionNo(), reference.prepareActionHash(), reference.prepareKeyHash(), null).signed();
        assertThatThrownBy(() -> service.queryCompletionOriginal(new ExecutionEvidenceLookup(other))).isInstanceOf(BizException.class);
        var end = completion.resourceEndOriginal();
        var foreign = new Request(end.authority(), end.startOriginal(), "OTHER_OCC", end.expectedRevision(),
                end.action(), end.actionNo(), end.keyHash(), null, List.of(), List.of()).signed();
        var different = ExecutionCompletionContract.request(reference, foreign, completion.actionNo());
        assertThatThrownBy(() -> service.completeExecution(different)).isInstanceOf(IllegalArgumentException.class);
        assertThat(state("ord_service_execution_unit")).isEqualTo("RESERVED");
    }

    @Test
    void concurrentOriginalCompletionsCommitOnePermanentPerformedUnit() throws Exception {
        service.markStarted(ack);
        var bothReadEnd = new CountDownLatch(2);
        when(lifecycle.queryOriginal(any())).thenAnswer(call -> {
            bothReadEnd.countDown();
            assertThat(bothReadEnd.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(endResult);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.completeExecution(completion));
            var second = pool.submit(() -> service.completeExecution(completion));
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_service_execution_action WHERE kind='FULFILL'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM ord_service_execution_unit", Long.class)).isEqualTo(1);
        assertThat(state("ord_service_execution_unit")).isEqualTo("PERFORMED");
    }

    private void seedPreparedOriginal() {
        var person = new OperatorPersonnelFact("PERSON", "EMPLOYMENT", 0, 0, 0,
                NOW.minusSeconds(86400), NOW.plusSeconds(86400));
        var provisional = new ExecutionBinding(scope, "BEAUTY", "OP", "ORDER", "CHILD", "LINE", 1, "WORK", 1,
                "TASK", 0, "BIND", 1, "EX", 1, "SUBMISSION", "FENCE", 1, 1, 1, H, "DEFINITION", H, H, H);
        binding = new ExecutionBinding(scope, "BEAUTY", "OP", "ORDER", "CHILD", "LINE", 1, "WORK", 1,
                "TASK", 0, "BIND", 1, "EX", 1, "SUBMISSION", "FENCE", 1, 1, 1, H, "DEFINITION", H, H,
                ServiceExecutionContract.payloadHash(provisional, person, NAMESPACE, "BTRC", H));
        intent = new StartIntent(binding, IntentState.FROZEN, 1, "MANUAL_OFFLINE", H, person, NAMESPACE, "BTRC", H,
                "{}", H, new SourceContinuity(1, 1, "READY_UNCHANGED", 1L, H, null), null);
        when(sourceOwner.inspectOriginal(any(StartIntentLookup.class))).thenReturn(intent);
        when(sourceOwner.inspect(any(StartIntentLookup.class))).thenReturn(intent);
        when(personnelOwner.current(any())).thenReturn(new PersonnelOperatorFactsPort.Observation(
                PersonnelOperatorFactsPort.LinkState.LINKED, NOW, person));
        var needs = List.of(new StockDispositionPort.Need("SKU", "S", "FULFILL", 1));
        var stock = new StockDispositionPort.Original("MAIN", "E", "ORDER", "PLATFORM", 2,
                StockDispositionContract.manifestHash(needs), needs);
        insert("ord_order", row("order_no", "ORDER"));
        insert("ord_sub_order", row("order_no", "ORDER", "sub_order_no", "CHILD"));
        insert("ord_checkout_operation", row("caller", "BEAUTY", "operation_no", "OP", "entity_no", "E", "store_no", "S",
                "order_no", "ORDER", "sub_order_no", "CHILD", "admission_kind", "MANUAL_OFFLINE", "confirmation_no", "CONFIRMATION"));
        insert("ord_checkout_line", row("caller", "BEAUTY", "operation_no", "OP",
                "sub_order_no", "CHILD", "source_line_no", "LINE", "order_item_id", 1L));
        insert("ord_manual_service_admission", row("caller", "BEAUTY", "operation_no", "OP", "entity_no", "E", "store_no", "S",
                "order_no", "ORDER", "sub_order_no", "CHILD", "declaration_no", "DECLARATION", "admission_revision", 1L,
                "stock_manifest_json", json.writeValueAsString(stock), "stock_manifest_hash", stock.manifestHash(),
                "stock_authority", "PLATFORM", "stock_lifecycle_version", 2, "stock_operation_no", StockDispositionContract.operationNo(stock)));
        String identity = CheckoutDigests.hash(ServiceExecutionContract.canonical(List.of(scope, "OP", "ORDER", "CHILD", "LINE", 1L)));
        insert("ord_service_execution_unit", row("unit_no", "UNIT", "entity_no", "E", "store_no", "S", "caller", "BEAUTY",
                "source_operation_no", "OP", "order_no", "ORDER", "sub_order_no", "CHILD", "source_line_no", "LINE",
                "order_item_id", 1L, "unit_ordinal", 1, "ordered_units", 1, "quantity", 1, "uom", "COUNT", "task_no", "TASK",
                "work_order_no", "WORK", "binding_no", "BIND", "binding_revision", 1L, "state", "RESERVED",
                "current_execution_no", "EX", "attempt_no", 1, "source_identity_hash", identity));
        String prepareHash = ServiceExecutionContract.actionHash(binding, "PREPARE", "PREPARE", 0, NAMESPACE,
                new StartIntentLookup(binding), null);
        insert("ord_service_execution_action", row("action_no", "PREPARE", "execution_no", "EX", "entity_no", "E", "store_no", "S",
                "kind", "PREPARE", "actor_namespace_hash", NAMESPACE, "request_key_hash", H, "action_hash", prepareHash,
                "expected_revision", 0L, "observation_epoch", 0L, "result_state", "PREPARED", "result_revision", 1L));
        insert("ord_service_execution", row("execution_no", "EX", "unit_no", "UNIT", "entity_no", "E", "store_no", "S",
                "attempt_no", 1, "grant_no", "GRANT", "quantity", 1, "uom", "COUNT", "state", "PREPARED",
                "binding_json", ServiceExecutionContract.bindingJson(binding), "binding_hash", ServiceExecutionContract.bindingHash(binding),
                "source_intent_hash", ServiceExecutionContract.intentHash(intent), "prepare_action_no", "PREPARE", "prepare_key_hash", H,
                "prepare_action_hash", prepareHash, "declaration_no", "DECLARATION", "admission_revision", 1L,
                "manual_source_revision", 1L, "manual_source_hash", H, "original_stock_manifest_hash", stock.manifestHash(),
                "confirmation_no", "CONFIRMATION", "stock_observation_hash", H, "source_fence_no", "FENCE", "source_fence_revision", 1L,
                "resource_command_no", "BTRC", "resource_command_hash", H, "observation_epoch", 0L));
    }

    private OriginalResult applied(CoreResourceProofFixture fixture) {
        var actual = new ActualOriginal("NATIVE_OWNER", "RESOURCE_COMMAND", "RESOURCE_OCCUPATION_START_V1", H);
        var units = List.of(
                new PermanentUnit("RU_STAFF", "STAFF_SLOT", "DIRECT", "STAFF_SLOT", "STAFF1", null, null, null, null, null, null, 1, NOW),
                new PermanentUnit("RU_ROOM", "ROOM_SLOT", "DIRECT", "ROOM_SLOT", "ROOM1", null, null, null, null, null, null, 1, NOW));
        var segments = List.of(
                new InitialSegment("SEG_STAFF", "STAFF_SLOT", "STAFF1", "PERSON", 1, List.of("RU_STAFF"), H,
                        fixture.source().resourcePlanJson(), "{}", NOW, NOW.plusSeconds(3600)),
                new InitialSegment("SEG_ROOM", "ROOM_SLOT", "ROOM1", null, 1, List.of("RU_ROOM"), H,
                        fixture.source().resourcePlanJson(), "{}", NOW, NOW.plusSeconds(3600)));
        var projections = List.of(
                new InitialProjection("PROJ_STAFF", "SEG_STAFF", "STAFF_SLOT", "STAFF1", "BUCKET_STAFF", NOW, NOW.plusSeconds(300), 1),
                new InitialProjection("PROJ_ROOM", "SEG_ROOM", "ROOM_SLOT", "ROOM1", "BUCKET_ROOM", NOW, NOW.plusSeconds(300), 1));
        var personnel = fixture.core().facts().frozenPersonnel();
        var employment = new ResourceQualificationPort.EmploymentWitness(personnel.assignmentNo(),
                personnel.assignmentRevision(), personnel.validFrom(), personnel.validTo());
        var staffSchedule = new ResourceQualificationPort.ScheduleWitness("STAFF_RULE", 1,
                personnel.assignmentNo(), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        var roomSchedule = new ResourceQualificationPort.ScheduleWitness("ROOM_RULE", 1,
                null, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        var bucket = new ResourceQualificationPort.CoveredBucket(NOW, NOW.plusSeconds(300), 1);
        var qualified = new ResourceQualificationPort.QualifiedGroup("STAFF1", "STAFF_SLOT", "PERSON", 0, 1,
                List.of(bucket), List.of(), List.of(staffSchedule), employment);
        var access = new ResourceOccupationContract.AccessWitness("ACCOUNT", "PERSON", 1,
                List.of(new ResourceOccupationContract.GrantWitness(1, 1, "S", "SERVICE", 0)),
                List.of(new ResourceOccupationContract.RoleWitness(1, 1, "E", "SERVICE",
                        "[\"biz:service:task:self\"]", 0)), personnel);
        var qualifications = List.of(
                new ResourceOccupationContract.QualificationWitness("STAFF1", "PERSON", 1,
                        List.of(qualified), List.of(staffSchedule), employment),
                new ResourceOccupationContract.QualificationWitness("ROOM1", null, 1,
                        List.of(), List.of(roomSchedule), null));
        var effects = new InitialEffects(units, List.of(), segments, projections, List.of(access), qualifications,
                fixture.source().resourcePlanHash());
        String effectsHash = ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_EFFECTS_V1", effects);
        var accepted = fixture.accepted(NOW);
        var applied = new AppliedStart("RESOURCE_OCCUPATION_APPLIED_V1", accepted.planned(), actual,
                "RESOURCE_RESULT", 1, ResourceOccupationContract.resultHash(actual, "OCC", NOW, effectsHash), "OCC", NOW,
                accepted, effects, effectsHash, null).signed();
        var requested = fixture.request().lookup();
        var lookup = new ResourceOccupationContract.OriginalLookup(requested.reference(), requested.source(),
                requested.requestKey(), requested.requestHash(), actual);
        var original = new OriginalResult("RESOURCE_OCCUPATION_ORIGINAL_V1", lookup,
                ResourceOccupationContract.Outcome.APPLIED, actual, accepted, applied, null, false, false, 1, null, null).signed();
        original.validate();
        return original;
    }

    private String state(String table) { return jdbc.queryForObject("SELECT state FROM " + table, String.class); }

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        var beans = new StaticListableBeanFactory(); beans.addBean("owner", value);
        return beans.getBeanProvider(type);
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tenant_no", "MAIN"); values.put("version", 0L); values.put("deleted", 0);
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    private void insert(String table, Map<String, Object> values) {
        String columns = String.join(",", values.keySet());
        jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
    }

    private void create(String table, Class<?> entity) {
        var definitions = new ArrayList<String>();
        for (Class<?> type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                var annotation = field.getAnnotation(TableField.class);
                if (Modifier.isStatic(field.getModifiers()) || annotation != null && !annotation.exist()) continue;
                String column = field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
                String sqlType = field.getType() == Long.class || field.getType() == long.class ? "BIGINT"
                        : field.getType() == Integer.class || field.getType() == int.class ? "INT"
                        : field.getType() == BigDecimal.class ? "DECIMAL(18,6)"
                        : field.getType() == LocalDateTime.class ? "TIMESTAMP(3)"
                        : field.getType() == Boolean.class || field.getType() == boolean.class ? "BOOLEAN" : "VARCHAR(2097152)";
                definitions.add("`" + column + "` " + sqlType + ("id".equals(column) ? " AUTO_INCREMENT PRIMARY KEY" : ""));
            }
        }
        jdbc.execute("CREATE TABLE " + table + " (" + String.join(",", definitions) + ")");
    }
}
