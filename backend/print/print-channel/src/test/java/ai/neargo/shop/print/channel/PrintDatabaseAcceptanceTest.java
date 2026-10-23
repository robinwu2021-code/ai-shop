package ai.neargo.shop.print.channel;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.event.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.dispatch.*;
import ai.neargo.shop.print.export.*;
import ai.neargo.shop.print.operation.*;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.simulation.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.PrintSceneRegistry;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import javax.sql.DataSource;

class PrintDatabaseAcceptanceTest {
    static AnnotationConfigApplicationContext context;

    @Configuration
    @EnableTransactionManagement
    @MapperScan(
            basePackageClasses = {PrintMappers.class, SysOutboxMapper.class},
            markerInterface = BaseMapper.class)
    @ComponentScan(
            basePackages = {
                "ai.neargo.shop.print.service",
                "ai.neargo.shop.print.operation",
                "ai.neargo.shop.print.audit",
                "ai.neargo.shop.print.support",
                "ai.neargo.shop.print.template",
                "ai.neargo.shop.print.source",
                "ai.neargo.shop.print.layout",
                "ai.neargo.shop.print.dispatch",
                "ai.neargo.shop.print.export",
                "ai.neargo.shop.print.simulation",
                "ai.neargo.shop.print.monitor",
                "ai.neargo.shop.print.port",
                "ai.neargo.shop.print.channel"
            })
    static class Config {
        @Bean
        FakeSource fakeSource() {
            return new FakeSource();
        }

        @Bean
        FakeCloud fakeCloud() {
            return new FakeCloud();
        }

        @Bean
        DataSource dataSource() {
            var d =
                    new DriverManagerDataSource(
                            System.getProperty(
                                    "print.acceptance.jdbc-url",
                                    "jdbc:h2:mem:printAcceptance;MODE=MySQL;DB_CLOSE_DELAY=-1"),
                            System.getProperty("print.acceptance.jdbc-user", "sa"),
                            System.getProperty("print.acceptance.jdbc-password", ""));
            if (!System.getProperty("print.acceptance.jdbc-url", "jdbc:h2:")
                    .startsWith("jdbc:mysql:"))
                new ResourceDatabasePopulator(new ClassPathResource("print-acceptance-schema.sql"))
                        .execute(d);
            return d;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource d) {
            return new DataSourceTransactionManager(d);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource d) throws Exception {
            var f = new MybatisSqlSessionFactoryBean();
            f.setDataSource(d);
            var c = new com.baomidou.mybatisplus.core.MybatisConfiguration();
            c.setMapUnderscoreToCamelCase(true);
            f.setConfiguration(c);
            return f.getObject();
        }

        @Bean
        OutboxEventBus events(SysOutboxMapper mapper, ObjectProvider<OutboxPump> pumps) {
            return new OutboxEventBus(mapper, JsonMapper.builder().build(), pumps);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        System.setProperty(
                "shop.print.credentials.key", Base64.getEncoder().encodeToString(new byte[32]));
        System.setProperty(
                "shop.print.artifacts.root",
                Files.createTempDirectory("print-acceptance-private").toString());
        System.setProperty(
                "shop.print.artifacts.signing-key",
                Base64.getEncoder().encodeToString(new byte[32]));
        System.setProperty(
                "shop.print.agent.lease-key", Base64.getEncoder().encodeToString(new byte[32]));
        context = new AnnotationConfigApplicationContext(Config.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    PrintActor actor(String key) {
        return new PrintActor(
                "T1",
                "E1",
                "S1",
                "MERCHANT",
                "U1",
                key,
                Set.of(
                        "SOURCE_ORDER_READ",
                        "biz:print:read",
                        "biz:print:simulate",
                        "biz:print:export"));
    }

    static class FakeSource implements PrintSourceFactsPort {
        PrintFact fact;

        public Set<String> scenes() {
            return Set.of("ORDER_PAID");
        }

        public Set<String> sourceTypes() {
            return Set.of("SUB_ORDER");
        }

        public PrintFactRef resolveReference(PrintSourceReference r, PrintScope scope) {
            return fact.ref();
        }

        public PrintFact snapshot(PrintFactRef ref) {
            assertEquals(fact.ref(), ref);
            return fact;
        }

        public PrintFactPage scan(PrintFactCursor cursor, int limit) {
            return new PrintFactPage(List.of(fact.ref()), cursor, false);
        }

        public PrintExecutionGuard executionGuard(PrintFactRef ref) {
            return new PrintExecutionGuard(
                    true, GuardDecision.ALLOW_HISTORICAL_RECEIPT, ref.bizRevision(), false, null);
        }
    }

    static class FakeCloud implements PrintDispatchPort {
        int submissions;
        PrintDispatch last;

        public String adapterCode() {
            return "ACCEPTANCE_CLOUD";
        }

        public PrintAdapterCapabilities capabilities() {
            return new PrintAdapterCapabilities(
                    adapterCode(),
                    false,
                    false,
                    true,
                    false,
                    Set.of(58, 80),
                    Set.of(PrintEvidenceLevel.PROVIDER_CONFIRMED));
        }

        public DispatchResult submit(PrintDispatch d) {
            submissions++;
            last = d;
            return new DispatchResult(
                    DispatchOutcome.ACCEPTED,
                    "VENDOR1",
                    new PrintJsonCodec()
                            .freeze(Map.of("queueTerminated", false, "partialOutput", false)),
                    null);
        }

        public QueryResult query(PrintAttemptRef r) {
            return new QueryResult(
                    ObservedPrintStatus.CONFIRMED,
                    PrintEvidenceLevel.PROVIDER_CONFIRMED,
                    true,
                    false,
                    new PrintJsonCodec().freeze(Map.of()));
        }

        public CancelResult cancel(PrintAttemptRef r) {
            throw new AssertionError("NO_QUEUE_WIDE_CANCEL");
        }
    }

    PrintActor flowActor(String key) {
        return new PrintActor(
                "T2",
                "E2",
                "S2",
                "MERCHANT",
                "U2",
                key,
                Set.of(
                        "SOURCE_ORDER_READ",
                        "biz:print:read",
                        "biz:print:submit",
                        "biz:print:reprint",
                        "biz:print:extra",
                        "biz:print:resolve",
                        "biz:print:export"));
    }

    @Test
    void productionFlowRequiresReceiptEvidenceAndPreservesOriginalAcrossReprint() {
        var codec = context.getBean(PrintJsonCodec.class);
        var device = context.getBean(PrintDeviceService.class);
        var cloud = context.getBean(FakeCloud.class);
        int priorSubmissions = cloud.submissions;
        String printer =
                device.createPrinter(
                                new CreatePrinterReq(
                                        "S2",
                                        "Acceptance printer",
                                        58,
                                        new Connection(
                                                "CLOUD",
                                                cloud.adapterCode(),
                                                "testuser",
                                                "TESTSN001",
                                                null,
                                                Map.of(),
                                                null,
                                                Map.of("user", "testuser", "ukey", "testkey"))),
                                flowActor("device-create"))
                        .targetNo();
        var detail = device.getPrinter(printer, flowActor("read"));
        device.changeStatus(
                printer,
                new ChangeStatusReq(detail.printer().revision(), "ACTIVE", "software acceptance"),
                flowActor("device-active"));
        var routes = context.getBean(PrintRouteService.class);
        String route =
                routes.createRoute(
                                new CreateRouteReq("S2", "ORDER_PAID"), flowActor("route-create"))
                        .targetNo();
        String version =
                routes.createRouteVersion(
                                route,
                                new CreateRouteVersionReq(
                                        0L,
                                        List.of(
                                                new RouteRule(
                                                        "default",
                                                        0,
                                                        new RoutePredicate(List.of()),
                                                        List.of(
                                                                new RouteTarget(
                                                                        "main",
                                                                        printer,
                                                                        1,
                                                                        true,
                                                                        new TemplateSelection(
                                                                                TemplateSelectionMode
                                                                                        .SCENE_DEFAULT,
                                                                                "STANDARD_58",
                                                                                null)))))),
                                flowActor("route-version"))
                        .targetNo();
        routes.publishRoute(version, new PublishRouteReq(0L, 1L), flowActor("route-publish"));
        var policy = context.getBean(PrintScenePolicyService.class);
        var definition = context.getBean(PrintSceneRegistry.class).require("ORDER_PAID");
        var triggers = new HashSet<String>();
        definition.definition().path("triggers").forEach(t -> triggers.add(t.asText()));
        var payload = codec.freeze(definition.example());
        var ref =
                new PrintFactRef(
                        "T2",
                        "E2",
                        "S2",
                        "SUB_ORDER",
                        "FACT1",
                        "1",
                        "ORDER_PAID",
                        payload.sha256());
        context.getBean(FakeSource.class).fact =
                new PrintFact(
                        ref,
                        "ORDER1",
                        "SUB1",
                        triggers.iterator().next(),
                        "PAY1",
                        Instant.now(),
                        1,
                        payload,
                        Printability.READY,
                        null,
                        List.of());
        var facade = context.getBean(PrintFacade.class);
        var skipped = facade.onFact(ref);
        assertTrue(skipped.jobNos().isEmpty());
        policy.saveScenePolicy(
                "ORDER_PAID",
                new SaveScenePolicyReq(
                        "S2",
                        0L,
                        new Policy(
                                true,
                                false,
                                Set.of(),
                                null,
                                15,
                                120,
                                3,
                                60,
                                600,
                                Map.of(
                                        "onIncident",
                                        true,
                                        "onBatchResolved",
                                        true,
                                        "channels",
                                        List.of("IN_APP"),
                                        "recipientRoleKeys",
                                        List.of("MANAGER")),
                                new PrintOutputOptions(false, PdfOutputProfile.ORIGINAL_ROLL))),
                flowActor("policy"));
        assertTrue(
                facade.onFact(ref).jobNos().isEmpty(),
                "Enabling a policy must not automatically backfill a skipped fact");
        var first =
                facade.submit(
                        new PrintIntent(ref, PrintMode.EXISTING_FACT, null), flowActor("submit"));
        assertEquals(1, first.jobNos().size());
        assertEquals(
                first.requestNo(),
                facade.submit(
                                new PrintIntent(ref, PrintMode.EXISTING_FACT, null),
                                flowActor("submit-again"))
                        .requestNo());
        var jobs = context.getBean(PrnJobMapper.class);
        var receipt = context.getBean(PrintReceiptService.class);
        var dispatch = context.getBean(PrintDispatchService.class);
        var budget = new PrintWorkerBudget(100, Duration.ofSeconds(20), "acceptance");
        dispatch.dispatchCloud(budget);
        receipt.applyPending(budget);
        var job =
                jobs.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PrnJob>()
                                .eq("job_no", first.jobNos().getFirst()));
        assertEquals("ACCEPTED", job.getStatus());
        assertNull(job.getConfirmedAt());
        assertEquals(priorSubmissions + 1, cloud.submissions);
        var at =
                context.getBean(PrnAttemptMapper.class)
                        .selectOne(
                                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                                                PrnAttempt>()
                                        .eq("attempt_no", job.getCurrentAttemptNo()));
        dispatch.reconcileAttempt(
                new PrintAttemptRef(
                        new PrintScope("T2", "E2", "S2"),
                        at.getAttemptNo(),
                        at.getProviderScope(),
                        at.getProviderJobId(),
                        at.getDispatchKey(),
                        at.getCredentialRef(),
                        codec.freeze(at.getConnectionSnapshot())));
        receipt.applyPending(budget);
        job = jobs.selectById(job.getId());
        assertEquals("COMPLETED", job.getStatus());
        assertNotNull(job.getConfirmedAt());
        var autoPayload = (tools.jackson.databind.node.ObjectNode) definition.example().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) autoPayload.path("source"))
                .put("factNo", "FACT2");
        var frozenAuto = codec.freeze(autoPayload);
        var autoRef =
                new PrintFactRef(
                        "T2",
                        "E2",
                        "S2",
                        "SUB_ORDER",
                        "FACT2",
                        "1",
                        "ORDER_PAID",
                        frozenAuto.sha256());
        var source = context.getBean(FakeSource.class);
        var savedFact = source.fact;
        source.fact =
                new PrintFact(
                        autoRef,
                        "ORDER2",
                        "SUB2",
                        triggers.iterator().next(),
                        "PAY2",
                        Instant.now(),
                        1,
                        frozenAuto,
                        Printability.READY,
                        null,
                        List.of());
        assertTrue(
                facade.onFact(autoRef).jobNos().isEmpty(),
                "Excluded automatic triggers must not create jobs");
        source.fact = savedFact;
        String originalHash = job.getDocumentHash();
        var originalLayout = codec.read(job.getLayoutSnapshot(), PrintLayout.class);
        var copy =
                context.getBean(PrintReprintService.class)
                        .reprint(
                                job.getJobNo(),
                                new ReprintReq(job.getRevision(), "补交顾客", null, false),
                                flowActor("reprint"));
        assertEquals(PrintOperationStatus.SUCCEEDED, copy.status());
        var child =
                jobs.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PrnJob>()
                                .eq("parent_job_no", job.getJobNo()));
        assertEquals("REPRINT", child.getJobKind());
        assertEquals("COMPLETED", jobs.selectById(job.getId()).getStatus());
        assertEquals(originalHash, jobs.selectById(job.getId()).getDocumentHash());
        assertEquals(
                businessDrawings(originalLayout),
                businessDrawings(codec.read(child.getLayoutSnapshot(), PrintLayout.class)));
    }

    private List<PrintDrawing> businessDrawings(PrintLayout layout) {
        var page = layout.pages().getFirst();
        if (layout.profileVersion().endsWith("_COMPACT_V2")) {
            var codec = context.getBean(PrintJsonCodec.class);
            int start =
                    codec.tree(page.systemSlots().getFirst().geometry()).path("lineCount").asInt()
                            + 1;
            int end = codec.tree(page.systemSlots().get(1).geometry()).path("startIndex").asInt();
            return page.drawings().subList(start, end);
        }
        return page.drawings().subList(8, page.drawings().size());
    }

    @Test
    void idempotencyIsDurableConflictsAreRejectedAndFailedCommandsRollBack() {
        var service = context.getBean(PrintOperationService.class);
        var codec = context.getBean(PrintJsonCodec.class);
        var actor = actor("idempotency-1");
        int[] writes = {0};
        var one =
                service.execute(
                        actor,
                        "ACCEPTANCE",
                        PrintHashes.sha256("same"),
                        () -> {
                            writes[0]++;
                            return PrintSupport.succeeded(
                                    "ACCEPTANCE", "JOB", "J1", codec.freeze(Map.of("jobNo", "J1")));
                        });
        var two =
                service.execute(
                        actor,
                        "ACCEPTANCE",
                        PrintHashes.sha256("same"),
                        () -> {
                            throw new AssertionError("MUST_NOT_REPEAT");
                        });
        assertEquals(one, two);
        assertEquals(1, writes[0]);
        assertThrows(
                PrintFailure.class,
                () ->
                        service.execute(
                                actor, "ACCEPTANCE", PrintHashes.sha256("changed"), () -> one));
        var mapper = context.getBean(PrnOperationMapper.class);
        long count = mapper.selectCount(null);
        assertThrows(
                PrintFailure.class,
                () ->
                        service.execute(
                                actor("rollback-1"),
                                "ACCEPTANCE",
                                PrintHashes.sha256("x"),
                                () -> {
                                    throw new PrintFailure("print.expectedFailure");
                                }));
        assertEquals(count, mapper.selectCount(null));
    }

    @Test
    void sampleSimulationAndPdfExportNeverCreateProductionJobs() {
        var simulator = context.getBean(PrintSimulationFacade.class);
        var jobs = context.getBean(PrnJobMapper.class);
        long before = jobs.selectCount(null);
        for (var fixture : SimulationFixtureCode.values()) {
            var result =
                    simulator.create(
                            new SimulationCommand(
                                    new PrintScope("T1", "E1", "S1"),
                                    "ORDER_PAID",
                                    new SimulationSource(
                                            SimulationSourceType.SAMPLE, null, null, null),
                                    "STANDARD_58",
                                    fixture,
                                    new TemplateSelection(
                                            TemplateSelectionMode.SCENE_DEFAULT,
                                            "STANDARD_58",
                                            null),
                                    new PrintOutputOptions(true, PdfOutputProfile.ORIGINAL_ROLL)),
                            actor("sim-" + fixture));
            assertNotNull(result.targetNo());
        }
        var report =
                context.getBean(SimulationRunner.class)
                        .runNext(new PrintWorkerBudget(100, Duration.ofSeconds(30), "acceptance"));
        assertEquals(0, report.failed());
        assertEquals(SimulationFixtureCode.values().length, report.succeeded());
        int generated = 0;
        for (int tick = 0;
                tick < SimulationFixtureCode.values().length
                        && generated < SimulationFixtureCode.values().length;
                tick++) {
            var exported =
                    context.getBean(PrintExportWorker.class)
                            .generateNext(
                                    new PrintWorkerBudget(
                                            100, Duration.ofSeconds(30), "acceptance"));
            assertEquals(0, exported.failed());
            generated += exported.succeeded();
        }
        assertEquals(SimulationFixtureCode.values().length, generated);
        assertEquals(before, jobs.selectCount(null));
        assertTrue(
                context
                        .getBean(PrnExportTaskMapper.class)
                        .selectList(
                                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                                                PrnExportTask>()
                                        .eq("tenant_no", "T1"))
                        .stream()
                        .allMatch(e -> "READY".equals(e.getStatus())));
    }

    @Test
    void cloudPrinterBeyondFirstPageStillGetsScheduled() {
        var device = context.getBean(PrintDeviceService.class);
        String last = null;
        for (int i = 0; i < 26; i++) {
            last =
                    device.createPrinter(
                                    new CreatePrinterReq(
                                            "S2",
                                            "Fairness " + i,
                                            58,
                                            new Connection(
                                                    "CLOUD",
                                                    "ACCEPTANCE_CLOUD",
                                                    "testuser",
                                                    "FAIRSN" + i,
                                                    null,
                                                    Map.of(),
                                                    null,
                                                    Map.of("user", "testuser", "ukey", "testkey"))),
                                    flowActor("fair-create-" + i))
                            .targetNo();
            var d = device.getPrinter(last, flowActor("read"));
            device.changeStatus(
                    last,
                    new ChangeStatusReq(d.printer().revision(), "ACTIVE", "acceptance"),
                    flowActor("fair-active-" + i));
        }
        var target = device.getPrinter(last, flowActor("read"));
        device.testPrint(
                last,
                new TestPrintReq(target.printer().revision(), "STANDARD"),
                flowActor("fair-test"));
        context.getBean(PrintDispatchService.class)
                .dispatchCloud(new PrintWorkerBudget(25, Duration.ofSeconds(20), "fairness"));
        assertTrue(
                context.getBean(PrnAttemptMapper.class)
                                .selectCount(
                                        new com.baomidou.mybatisplus.core.conditions.query
                                                                .QueryWrapper<
                                                        PrnAttempt>()
                                                .eq("printer_no", last))
                        > 0,
                "The printer after 25 idle devices must receive an attempt");
    }

    @Test
    void cleanupAdvancesBeyondAlreadyDeletedArtifacts() {
        var rows = context.getBean(PrnExportTaskMapper.class);
        var store = context.getBean(PrintArtifactStorePort.class);
        var scope = new PrintScope("TCLEAN", "ECLEAN", "SCLEAN");
        var keys = new ArrayList<String>();
        for (int i = 0; i < 21; i++) {
            String no = "CLEAN" + i, key = "TCLEAN/ECLEAN/SCLEAN/" + no + ".pdf";
            keys.add(key);
            byte[] bytes = "%PDF-fixture".getBytes();
            store.put(
                    new PrivateArtifact(key, bytes, PrintHashes.sha256(bytes), "application/pdf"));
            var e = PrintSupport.init(new PrnExportTask(), scope);
            e.setExportNo(no);
            e.setStoreNo(scope.storeNo());
            e.setOperationNo("OP" + no);
            e.setExportKey(PrintHashes.sha256(no));
            e.setSourceType("SIMULATION");
            e.setSourceNo(no);
            e.setSourceRevision(0L);
            e.setSourceSnapshot("{}");
            e.setOutputProfile("ORIGINAL_ROLL");
            e.setArtifactKind("SIMULATION_EXPORT");
            e.setDocumentHash(PrintHashes.sha256(no));
            e.setRendererVersion("1");
            e.setFontBundleVersion("NOTO_SC_V1");
            e.setRequiredDataScopes("[]");
            e.setStatus("READY");
            e.setObjectKey(key);
            e.setExpiresAt(PrintSupport.now().minusDays(1));
            e.setCreatedBy("acceptance");
            rows.insert(e);
        }
        var cleanup = context.getBean(PrintExportCleanupJob.class);
        var budget = new PrintWorkerBudget(20, Duration.ofSeconds(20), "cleanup");
        cleanup.expire(budget);
        cleanup.expire(budget);
        assertTrue(
                keys.stream().noneMatch(store::exists),
                "Already cleaned metadata must not starve the next expired artifact");
    }

    @Test
    void stableOutboxInsertReturnsDatabaseIdentity() {
        var row = new SysOutbox();
        row.setEventNo("STABLEPRINT1");
        row.setAggregateType("PRINT");
        row.setAggregateId("PRINT1");
        row.setEventType("PRINT_TEST");
        row.setPayload("{}");
        row.setStatus("PENDING");
        row.setRetryCount(0);
        row.setCreatedAt(java.time.LocalDateTime.now());
        assertEquals(1, context.getBean(SysOutboxMapper.class).insertIfAbsent(row));
        assertNotNull(
                row.getId(),
                "Stable publication must provide the identity used by the after-commit pump");
    }

    String unseal(String sealed, java.security.PrivateKey key) throws Exception {
        var c = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        c.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                key,
                new javax.crypto.spec.OAEPParameterSpec(
                        "SHA-256",
                        "MGF1",
                        java.security.spec.MGF1ParameterSpec.SHA256,
                        javax.crypto.spec.PSource.PSpecified.DEFAULT));
        return new String(
                c.doFinal(Base64.getDecoder().decode(sealed)),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void agentEnrollmentSessionFenceAndClaimAreDurable() throws Exception {
        var management = context.getBean(PrintAgentService.class);
        var protocol = context.getBean(PrintAgentProtocolService.class);
        var auth = context.getBean(PrintAgentAuthenticationService.class);
        var codec = context.getBean(PrintJsonCodec.class);
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String agent =
                management
                        .createAgent(
                                new CreateAgentReq("S2", "Software agent", "WINDOWS"),
                                flowActor("agent-create"))
                        .targetNo();
        var issued =
                management.issueEnrollment(
                        agent, new IssueEnrollmentReq(0L, publicKey), flowActor("agent-pair"));
        String code =
                unseal(
                        codec.tree(issued.result()).path("sealedEnrollment").asText(),
                        pair.getPrivate());
        var enrolled =
                protocol.enroll(
                        new DeviceEnrollReq(
                                agent, code, publicKey, "ENROLL1", "WINDOWS", "acceptance"));
        String token = unseal(enrolled.sealedCredential(), pair.getPrivate());
        var identity =
                auth.authenticate(
                        new OpaqueAgentCredential(token), AgentRequestPurpose.OPEN_SESSION);
        var opened =
                protocol.openSession(
                        new DeviceOpenSessionReq("SESSION1", "acceptance", null), identity);
        identity =
                auth.authenticate(new OpaqueAgentCredential(token), AgentRequestPurpose.HEARTBEAT);
        protocol.heartbeat(
                new DeviceHeartbeatReq(
                        "HB1",
                        opened.sessionEpoch(),
                        List.of(),
                        0,
                        List.of(new DiscoveredPrinter("SERIAL001", "QA_QUEUE"))),
                identity);
        var device = context.getBean(PrintDeviceService.class);
        String printer =
                device.createPrinter(
                                new CreatePrinterReq(
                                        "S2",
                                        "Local QA",
                                        58,
                                        new Connection(
                                                "LOCAL",
                                                "LOCAL_SYSTEM_QUEUE",
                                                null,
                                                null,
                                                agent,
                                                Map.of("queueName", "QA_QUEUE"),
                                                "SERIAL001",
                                                Map.of())),
                                flowActor("local-create"))
                        .targetNo();
        var detail = device.getPrinter(printer, flowActor("read"));
        device.changeStatus(
                printer,
                new ChangeStatusReq(detail.printer().revision(), "ACTIVE", "acceptance"),
                flowActor("local-active"));
        detail = device.getPrinter(printer, flowActor("read"));
        device.testPrint(
                printer,
                new TestPrintReq(detail.printer().revision(), "STANDARD"),
                flowActor("local-test"));
        var req = new DeviceClaimReq("CLAIM1", opened.sessionEpoch(), List.of(printer), 1, 0);
        var claim = protocol.claim(req, identity);
        assertEquals(1, claim.dispatches().size());
        assertEquals(claim, protocol.claim(req, identity));
        var dispatch = claim.dispatches().getFirst();
        var accepted =
                protocol.acceptDispatch(
                        dispatch.dispatchKey(),
                        new DeviceAcceptDispatchReq(
                                "ACCEPT1",
                                opened.sessionEpoch(),
                                dispatch.fenceEpoch(),
                                dispatch.leaseToken(),
                                dispatch.documentHash()),
                        identity);
        assertTrue(accepted.accepted());
        assertNotEquals(
                "COMPLETED",
                context.getBean(PrnJobMapper.class)
                        .selectOne(
                                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                                                PrnJob>()
                                        .eq("job_no", dispatch.jobNo()))
                        .getStatus());
        var next =
                protocol.openSession(
                        new DeviceOpenSessionReq("SESSION2", "acceptance", opened.sessionEpoch()),
                        identity);
        assertTrue(next.sessionEpoch() > opened.sessionEpoch());
        var staleIdentity = identity;
        assertThrows(
                PrintFailure.class,
                () ->
                        protocol.claim(
                                new DeviceClaimReq(
                                        "STALE", opened.sessionEpoch(), List.of(printer), 1, 0),
                                staleIdentity));
        var agentDetail = management.getAgent(agent, flowActor("read"));
        management.revokeAgent(
                agent,
                new RevokeAgentReq(agentDetail.agent().revision(), "acceptance"),
                flowActor("agent-revoke"));
        assertThrows(
                PrintFailure.class,
                () ->
                        auth.authenticate(
                                new OpaqueAgentCredential(token),
                                AgentRequestPurpose.REPORT_EVIDENCE));
    }

    @Test
    void negativePaginationCannotDisableQueryBounds() {
        var devices = context.getBean(PrintDeviceService.class);
        assertThrows(
                PrintFailure.class,
                () ->
                        devices.listPrinters(
                                new ListPrintersQuery("S2", null, null, null, 1, -1),
                                flowActor("read")));
        assertThrows(
                PrintFailure.class,
                () ->
                        devices.listPrinters(
                                new ListPrintersQuery("S2", null, null, null, 0, 20),
                                flowActor("read")));
    }
}
