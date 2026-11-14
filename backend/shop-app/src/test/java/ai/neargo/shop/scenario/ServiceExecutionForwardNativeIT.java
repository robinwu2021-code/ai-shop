package ai.neargo.shop.scenario;

import ai.neargo.shop.trade.service.impl.ServiceExecutionServiceImpl;
import ai.neargo.shop.infra.NativeResourceProofFixture;

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
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.util.Comparator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MySQL 9 forward-upgrade proof: genuine V604/V649/V651 via Flyway, physical descriptor inspection,
 * and real Core ACK/completion writes. Source roots are bounded JDBC fixtures; independent owners return signed proofs.
 */
@EnabledIfSystemProperty(named = "execution-forward.test.admin-url", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class ServiceExecutionForwardNativeIT {
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
    private JdbcTemplate admin;
    private String database;
    private ServiceExecutionSchemaVerifier physicalSchema;
    private TransactionTemplate writes;
    private ServiceExecutionServiceImpl service;
    private ManualAdmissionMapper admissions;
    private ExecutionBinding binding;
    private StartIntent intent;
    private ExecutionReference reference;
    private NativeResourceProofFixture resourceFixture;
    private AcknowledgeResourceStart ack;
    private CompleteExecution completion;
    private Result endResult;

    @BeforeEach
    void fixture() throws Exception {
        String adminUrl = System.getProperty("execution-forward.test.admin-url");
        String user = System.getProperty("execution-forward.test.user", "root");
        String password = System.getProperty("execution-forward.test.password", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, user, password));
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        database = "execution_forward_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        int query = adminUrl.indexOf('?');
        String address = query < 0 ? adminUrl : adminUrl.substring(0, query);
        String suffix = query < 0 ? "" : adminUrl.substring(query);
        String url = address.substring(0, address.lastIndexOf('/') + 1) + database + suffix;
        var data = new DriverManagerDataSource(url, user, password);
        jdbc = new JdbcTemplate(data);
        create("ord_order", OrdOrder.class);
        create("ord_sub_order", OrdSubOrder.class);
        historicalCreate("V599__checkout_source_foundation.sql", "ord_checkout_operation");
        historicalCreate("V599__checkout_source_foundation.sql", "ord_checkout_line");
        historicalCreate("V601__manual_service_admission.sql", "ord_manual_service_admission");
        try (var input = new ClassPathResource("db/migration/V601__manual_service_admission.sql").getInputStream()) {
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            jdbc.execute(script.substring(0, script.indexOf(';') + 1));
        }
        migrate(data, "668");
        physicalSchema = new ServiceExecutionSchemaVerifier(data, "flyway_schema_history", "");
        assertThat(physicalSchema.installation()).isEqualTo(ServiceExecutionSchemaVerifier.Installation.INSTALLED);
        physicalSchema.requireStartAvailable();
        assertThatThrownBy(physicalSchema::requireCompletionAvailable).isInstanceOf(BizException.class);
        migrate(data, "669");
        physicalSchema.requireStartAvailable();
        physicalSchema.requireCompletionAvailable();
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        for (var mapper : List.of(CheckoutMappers.OperationMapper.class, ManualAdmissionMapper.class,
                ServiceExecutionMapper.class, ServiceExecutionMapper.UnitMapper.class,
                ServiceExecutionMapper.ActionMapper.class)) config.addMapper(mapper);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(data); factory.setConfiguration(config); factory.afterPropertiesSet();
        var sql = new SqlSessionTemplate(factory.getObject());
        var schema = physicalSchema;
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
        reference = DelegatedIdentityContext.call(NativeResourceProofFixture.ACTOR,
                () -> service.referenceOriginal(new ExecutionLookup(binding)));
        var original = service.queryOriginalEvidence(new ExecutionEvidenceLookup(reference));
        original.validate();
        resourceFixture = NativeResourceProofFixture.create(original, NativeResourceProofFixture.resourcePlan(NOW), NOW, NativeResourceProofFixture.ACTOR);
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
    void nativeOriginalUniquenessAndTerminalTriggersCannotBeBypassedWithDirectSql() {
        failsNative(() -> jdbc.update("UPDATE ord_service_execution SET actual_resource_owner_no='INCOMPLETE'"), 3819);
        assertThat(state("ord_service_execution")).isEqualTo("PREPARED");
        service.markStarted(ack);
        assertThat(jdbc.queryForObject("SELECT actual_resource_command_no FROM ord_service_execution", String.class))
                .isEqualTo("RESOURCE_COMMAND");
        failsNative(() -> jdbc.update("UPDATE ord_service_execution SET actual_resource_command_no='OTHER'"), 1644);
        service.completeExecution(completion);
        assertThat(jdbc.queryForObject("SELECT version FROM ord_service_execution_action WHERE kind='START_ACK'", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM ord_service_execution_action WHERE kind='FULFILL'", Long.class))
                .isEqualTo(2);
        var columns = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='ord_service_execution_action' AND column_name<>'id' ORDER BY ordinal_position", String.class);
        String list = String.join(",", columns.stream().map(c -> "`" + c + "`").toList());
        failsNative(() -> jdbc.update("INSERT INTO ord_service_execution_action (" + list + ") SELECT " + list
                + " FROM ord_service_execution_action WHERE kind='FULFILL'"), 1062);
        failsNative(() -> jdbc.update("UPDATE ord_service_execution SET state='STARTED'"), 1644);
        failsNative(() -> jdbc.update("UPDATE ord_service_execution_unit SET state='RESERVED',current_execution_no='EX'"), 1644);
        failsNative(() -> jdbc.update("UPDATE ord_service_execution_action SET result_state='STARTED' WHERE kind='FULFILL'"), 1644);
        failsNative(() -> jdbc.update("UPDATE ord_service_execution_action SET proof_hash=? WHERE kind='FULFILL'", "b".repeat(64)), 1644);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_service_execution_action WHERE kind='FULFILL'", Long.class))
                .isEqualTo(1);
        physicalSchema.requireStartAvailable();
        physicalSchema.requireCompletionAvailable();
    }

    @AfterEach
    void dropDatabase() {
        if (admin != null && database != null) admin.execute("DROP DATABASE IF EXISTS " + database);
    }

    /** The original migration bytes are copied to an isolated Flyway location, never patched or marked by hand. */
    private void migrate(DataSource source, String target) throws Exception {
        Path directory = Files.createTempDirectory("execution-forward-native-");
        try {
            for (String script : List.of("V604__manual_service_execution_authority.sql",
                    "V668__service_execution_start_ack.sql", "V669__service_execution_completion.sql")) {
                try (var input = new ClassPathResource("db/migration/" + script).getInputStream()) {
                    Files.copy(input, directory.resolve(script));
                }
            }
            Flyway.configure().dataSource(source).baselineOnMigrate(true).baselineVersion("603")
                    .locations("filesystem:" + directory).target(target).load().migrate();
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
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
                "order_no", "ORDER", "sub_order_no", "CHILD", "admission_kind", "MANUAL_OFFLINE", "confirmation_no", "CONFIRMATION",
                "buyer_no", "BUYER", "actor_no", "ACCOUNT", "actor_realm", "MERCHANT", "actor_kind", "MCH",
                "request_key_hash", H, "payload_hash", H, "scope_revision", 1L, "scope_hash", H,
                "command_json", "{}", "phase", "CREATED", "current_quote_no", "QUOTE", "place_key_hash", H,
                "place_payload_hash", H));
        insert("ord_checkout_line", row("caller", "BEAUTY", "operation_no", "OP",
                "sub_order_no", "CHILD", "source_line_no", "LINE", "order_item_id", 1L,
                "client_line_ref", "CLIENT", "goods_no", "GOODS", "sku_no", "SKU", "quantity", 1, "uom", "COUNT"));
        insert("ord_manual_service_admission", row("caller", "BEAUTY", "operation_no", "OP", "entity_no", "E", "store_no", "S",
                "order_no", "ORDER", "sub_order_no", "CHILD", "declaration_no", "DECLARATION", "admission_revision", 1L,
                "stock_manifest_json", json.writeValueAsString(stock), "stock_manifest_hash", stock.manifestHash(),
                "stock_authority", "PLATFORM", "stock_lifecycle_version", 2, "stock_operation_no", StockDispositionContract.operationNo(stock),
                "buyer_no", "BUYER", "actor_no", "ACCOUNT", "actor_realm", "MERCHANT", "actor_kind", "MCH",
                "account_no", "ACCOUNT", "request_key_hash", H, "payload_hash", H, "method", "CASH", "declared_minor", 1L,
                "currency", "CNY", "received_at", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
                "recorded_at", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), "phase", "ADMITTED", "order_revision", 0L,
                "quote_hash", H, "stock_outcome", "APPLIED", "stock_state", "ALLOCATED", "stock_observation_hash", H));
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
                "expected_revision", 0L, "observation_epoch", 0L, "result_state", "PREPARED", "result_revision", 2L));
        insert("ord_service_execution", row("execution_no", "EX", "unit_no", "UNIT", "entity_no", "E", "store_no", "S",
                "attempt_no", 1, "grant_no", "GRANT", "quantity", 1, "uom", "COUNT", "state", "PREPARED",
                "binding_json", ServiceExecutionContract.bindingJson(binding), "binding_hash", ServiceExecutionContract.bindingHash(binding),
                "source_intent_hash", ServiceExecutionContract.intentHash(intent), "prepare_action_no", "PREPARE", "prepare_key_hash", H,
                "prepare_action_hash", prepareHash, "declaration_no", "DECLARATION", "admission_revision", 1L,
                "manual_source_revision", 1L, "manual_source_hash", H, "original_stock_manifest_hash", stock.manifestHash(),
                "confirmation_no", "CONFIRMATION", "stock_observation_hash", H, "source_fence_no", "FENCE", "source_fence_revision", 1L,
                "resource_command_no", "BTRC", "resource_command_hash", H, "observation_epoch", 0L));
    }

    private OriginalResult applied(NativeResourceProofFixture fixture) {
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

    private void failsNative(Runnable mutation, int errorCode) {
        assertThatThrownBy(mutation::run).isInstanceOf(DataAccessException.class).satisfies(failure -> {
            var cause = ((DataAccessException) failure).getMostSpecificCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getErrorCode()).isEqualTo(errorCode);
        });
    }

    private String state(String table) { return jdbc.queryForObject("SELECT state FROM " + table, String.class); }

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        var beans = new StaticListableBeanFactory(); beans.addBean("owner", value);
        return beans.getBeanProvider(type);
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tenant_no", "MAIN"); values.put("version", 1L); values.put("deleted", 0);
        values.put("created_at", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        values.put("updated_at", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    private void insert(String table, Map<String, Object> values) {
        String columns = String.join(",", values.keySet());
        jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
    }

    private void historicalCreate(String script, String table) throws Exception {
        String original;
        try (var input = new ClassPathResource("db/migration/" + script).getInputStream()) {
            original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var statement = Pattern.compile("(?is)CREATE TABLE " + table
                + " \\([\\s\\S]*?\\n\\) ENGINE[^\n]*;").matcher(original);
        assertThat(statement.find()).as("genuine source CREATE for %s", table).isTrue();
        jdbc.execute(statement.group());
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
                        : field.getType() == LocalDateTime.class ? "DATETIME(3)"
                        : field.getType() == Boolean.class || field.getType() == boolean.class ? "BOOLEAN" : "LONGTEXT";
                if ("tenant_no".equals(column)) sqlType = "VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
                if (List.of("entity_no", "store_no").contains(column)) sqlType = "VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin";
                if ("source_line_no".equals(column)) sqlType = "VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin";
                definitions.add("`" + column + "` " + sqlType + ("id".equals(column) ? " AUTO_INCREMENT PRIMARY KEY" : ""));
            }
        }
        jdbc.execute("CREATE TABLE " + table + " (" + String.join(",", definitions) + ")");
    }
}
