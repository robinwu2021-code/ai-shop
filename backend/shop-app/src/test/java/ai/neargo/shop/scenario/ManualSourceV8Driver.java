package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.member.port.MemberCustomerIdentityPortImpl;
import ai.neargo.shop.member.port.MemberGuestRegistrationPortImpl;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.trade.port.CheckoutPortImpl;
import ai.neargo.shop.trade.port.ManualServiceAdmissionPortImpl;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit old/current JVM protocol. Historical beauty classes are loaded only from the immutable
 * snapshot; common checkout/member/inventory providers remain actual current HOST owners.
 */
public final class ManualSourceV8Driver {
    private static final CountDownLatch OWNER_RETURNED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_OWNER = new CountDownLatch(1);
    private static final ThreadLocal<Boolean> PAUSE = ThreadLocal.withInitial(() -> false);
    private static volatile String pausedOperation;
    private static volatile CheckoutPort.CheckoutSourceFacts oldFacts;
    private static volatile boolean manualUnavailable;
    private static final CountDownLatch FIRST_MANUAL_REPLY = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_MANUAL_REPLY = new CountDownLatch(1);
    private static final ThreadLocal<Boolean> PAUSE_MANUAL = ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean MANUAL_PAUSED_ONCE = new AtomicBoolean();

    private ManualSourceV8Driver() {}

    /** Entry is exclusively a finite native runner child, never a second application deployment. */
    public static void main(String[] args) throws Exception {
        assertThat(args).hasSize(2);
        Path protocol = Path.of(args[1]).toRealPath();
        assertThat(protocol.toString()).startsWith("/private/tmp/codex-manual-source-");
        assertThat(protocol.getFileName().toString()).isEqualTo("upgrade-protocol");
        assertThat(System.getenv("MANUAL_UPGRADE_MYSQL_URL")).startsWith("jdbc:mysql://127.0.0.1:");
        boolean historical = "old".equals(args[0]);
        assertThat(historical || "current".equals(args[0])).isTrue();
        assertOrigin(historical);
        try (var context =
                new SpringApplicationBuilder(
                                ShopApplication.class,
                                CheckoutFlowFixture.ClockConfig.class,
                                BeautySourceCheckoutIT.TestClockSelection.class,
                                DriverConfiguration.class)
                        .profiles("test")
                        .run(
                                "--server.port=0",
                                "--spring.datasource.url="
                                        + System.getenv("MANUAL_UPGRADE_MYSQL_URL"),
                                "--shop.member-code.enabled=true",
                                "--shop.catalog.publication.archive-only=true",
                                "--shop.member.guest.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                                "--shop.beauty.enabled=true",
                                "--shop.beauty.deployment=embedded",
                                "--shop.beauty.migration.mode=migrate",
                                "--shop.beauty.publication.archive-only=true",
                                "--shop.inventory.enabled=true",
                                "--shop.inventory.flyway-enabled=false",
                                "--shop.inventory.datasource.url="
                                        + System.getenv("MANUAL_INVENTORY_MYSQL_URL"),
                                "--shop.inventory.datasource.username=root",
                                "--shop.inventory.datasource.password=${MANUAL_SERVICE_TEST_PASSWORD}",
                                "--shop.inventory.stock-authority=INVENTORY")) {
            var host = host(context);
            if (historical) runOld(host, protocol);
            else runCurrent(host, protocol);
        } finally {
            RELEASE_OWNER.countDown();
            RELEASE_MANUAL_REPLY.countDown();
        }
    }

    /**
     * Parent test checks exact current CP, then owns both bounded children through finally cleanup.
     */
    static void verifyUpgrade(ObjectMapper json) throws Exception {
        Path protocol = Path.of(System.getenv("MANUAL_UPGRADE_PROTOCOL")).toRealPath();
        Path evidence = Path.of(System.getenv("MANUAL_UPGRADE_EVIDENCE")).toRealPath();
        var runtime =
                json.readTree(
                        Files.readString(
                                Path.of(System.getenv("MANUAL_UPGRADE_RUNTIME_MANIFEST"))));
        String[] current =
                json.readValue(runtime.path("currentClasspath").toString(), String[].class);
        String[] historical =
                json.readValue(runtime.path("historicalClasspath").toString(), String[].class);
        var actual =
                Arrays.stream(
                                System.getProperty("surefire.test.class.path")
                                        .split(File.pathSeparator))
                        .map(
                                path -> {
                                    try {
                                        return Path.of(path).toRealPath().toString();
                                    } catch (IOException failure) {
                                        throw new IllegalStateException(failure);
                                    }
                                })
                        .toList();
        assertThat(actual).containsExactly(current);
        Process old = null;
        Process upgraded = null;
        var children = new CopyOnWriteArrayList<Process>();
        Thread shutdown =
                new Thread(
                        () -> {
                            for (var process : children) {
                                try {
                                    stop(process);
                                } catch (Exception | AssertionError failure) {
                                    process.destroyForcibly();
                                }
                            }
                        },
                        "manual-upgrade-child-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        Throwable primaryFailure = null;
        try {
            old = child("old", historical, protocol);
            children.add(old);
            write(json, protocol, "child-pids.json", Map.of("old", old.pid()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!Files.exists(protocol.resolve("old-ready.json"))
                    && old.isAlive()
                    && System.nanoTime() < deadline) Thread.sleep(50);
            assertThat(protocol.resolve("old-ready.json"))
                    .as("actual old V2 and in-flight owner ticket")
                    .exists();
            upgraded = child("current", current, protocol);
            children.add(upgraded);
            write(
                    json,
                    protocol,
                    "child-pids.json",
                    Map.of("old", old.pid(), "current", upgraded.pid()));
            assertThat(upgraded.waitFor(210, TimeUnit.SECONDS))
                    .as("bounded current JVM lifetime")
                    .isTrue();
            assertThat(upgraded.exitValue())
                    .as("current JVM exit; inspect current-child.log")
                    .isZero();
            assertThat(old.waitFor(30, TimeUnit.SECONDS))
                    .as("bounded resumed old JVM lifetime")
                    .isTrue();
            assertThat(old.exitValue()).as("old JVM exit; inspect old-child.log").isZero();
            var result = json.readTree(Files.readString(protocol.resolve("current-complete.json")));
            var oldResult = json.readTree(Files.readString(protocol.resolve("old-complete.json")));
            for (String field :
                    List.of(
                            "samePhysicalRow",
                            "oldV2BytesUnchanged",
                            "separateManualReady",
                            "upgradedStaleUnchanged"))
                assertThat(result.path(field).asBoolean()).as(field).isTrue();
            for (String field :
                    List.of(
                            "lateCompletionRejected",
                            "exactOldMapperRejected",
                            "legacyInsertAndCompletionReady"))
                assertThat(oldResult.path(field).asBoolean()).as(field).isTrue();
            System.out.println("TASK67_UPGRADE_VERIFIED " + result);
        } catch (Exception | AssertionError failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            var cleanupFailures = new ArrayList<Throwable>();
            for (Process child : children) {
                try {
                    stop(child);
                } catch (Exception | AssertionError failure) {
                    cleanupFailures.add(failure);
                }
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException shuttingDown) {
                // The shutdown hook remains the bounded owner if the JVM is already terminating.
            }
            try {
                String password = System.getenv("MANUAL_SERVICE_TEST_PASSWORD");
                try (var files = Files.list(protocol)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        String text = Files.readString(file);
                        Files.writeString(
                                evidence.resolve(file.getFileName()),
                                text.replace(password, "[REDACTED]"));
                    }
                }
            } catch (Exception failure) {
                cleanupFailures.add(failure);
            }
            try {
                Files.writeString(
                        evidence.resolve("child-cleanup.json"),
                        json.writeValueAsString(
                                Map.of(
                                        "oldChildAbsent", old == null || !old.isAlive(),
                                        "currentChildAbsent",
                                                upgraded == null || !upgraded.isAlive())));
            } catch (Exception failure) {
                cleanupFailures.add(failure);
            }
            if (primaryFailure != null) {
                for (var failure : cleanupFailures) primaryFailure.addSuppressed(failure);
            } else if (!cleanupFailures.isEmpty())
                throw new IllegalStateException("Child cleanup failed", cleanupFailures.getFirst());
        }
    }

    private static Process child(String mode, String[] classpath, Path protocol) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(
                        javaExecutable,
                        "-Xmx512m",
                        "-cp",
                        String.join(File.pathSeparator, classpath),
                        ManualSourceV8Driver.class.getName(),
                        mode,
                        protocol.toString())
                .redirectErrorStream(true)
                .redirectOutput(protocol.resolve(mode + "-child.log").toFile())
                .start();
    }

    private static void stop(Process process) throws Exception {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(process.isAlive()).isFalse();
    }

    private static Host host(ConfigurableApplicationContext context) {
        var host = new Host();
        context.getAutowireCapableBeanFactory().autowireBean(host);
        // MockitoSpyBean is a JUnit override, intentionally not used by the standalone child
        // context.
        host.customers = context.getBean(CustomerIdentityPort.class);
        host.guests = context.getBean(GuestRegistrationPort.class);
        host.serviceCatalog = context.getBean(ServiceCatalogFactsPort.class);
        host.manual = context.getBean(ManualServiceAdmissionPort.class);
        assertThat(host.customers).isInstanceOf(MemberCustomerIdentityPortImpl.class);
        assertThat(host.guests).isInstanceOf(MemberGuestRegistrationPortImpl.class);
        assertThat(
                        host.checkout
                                .getClass()
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toString())
                .contains("/backend/shop-core/target/classes/");
        return host;
    }

    private static void assertOrigin(boolean historical) throws Exception {
        String location =
                Class.forName("ai.neargo.shop.beauty.service.impl.ServiceSourceObservation")
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toString();
        String expected =
                historical
                        ? System.getenv("MANUAL_UPGRADE_SNAPSHOT")
                                + "/backend-plugins/"
                                + "shop-industry-beauty/beauty-core/target/classes/"
                        : System.getenv("MANUAL_UPGRADE_CURRENT_ROOT")
                                + "/backend-plugins/"
                                + "shop-industry-beauty/beauty-core/target/classes/";
        assertThat(Path.of(URI.create(location))).isEqualTo(Path.of(expected));
        var resources =
                ClassLoader.getSystemResources(
                        "db/industry/beauty/V8__beauty_source_observation.sql");
        int count = 0;
        while (resources.hasMoreElements()) {
            assertThat(resources.nextElement().toString()).startsWith("file:" + expected);
            count++;
        }
        assertThat(count).isOne();
        assertThat(
                        ClassLoader.getSystemResource(
                                        "db/industry/beauty/V9__beauty_manual_source_observation.sql")
                                != null)
                .isEqualTo(!historical);
        System.out.println("TASK67_UPGRADE_RUNTIME " + (historical ? "V8 " : "V9 ") + location);
    }

    @SuppressWarnings("unchecked")
    private static void runOld(Host host, Path protocol) throws Exception {
        host.fixture();
        assertThat(
                        host.jdbc.queryForObject(
                                "SELECT MAX(CAST(version AS UNSIGNED)) FROM bty_flyway_history",
                                Integer.class))
                .isEqualTo(8);
        String work = host.open(Map.of("customerRef", host.realConsumerReference()), "v8-open");
        var original = host.admittedM1(work, "v8-original");
        var request = new HashMap<String, Object>(host.existingRequest(work, original.source()));
        var source = new HashMap<String, Object>((Map<String, Object>) request.get("source"));
        source.put("mode", "EXISTING");
        request.put("source", source);
        var submission =
                host.successful(
                        host.http(
                                "POST",
                                "/biz/x/beauty/work-orders/" + work + "/service-submissions",
                                request,
                                "old-existing-" + host.sku,
                                false));
        assertThat(submission.path("status").asText()).isNotEqualTo("BOUND");
        String number = submission.path("submissionNo").asText();
        assertThat(number).isNotBlank();
        Map<String, Object> intent =
                host.jdbc.queryForMap(
                        "SELECT * FROM bty_service_submission WHERE " + "submission_no=?", number);
        String frozen = (String) intent.get("frozen_command");
        assertThat(host.json.readTree(frozen).path("schemaVersion").asInt()).isEqualTo(2);
        var first = host.observationRow(original.source().subOrderNo());
        assertEmptyFinancial(first);
        var canonical = canonical(host, original.source());
        pausedOperation = original.source().operation().operationNo();
        // The actual old observer commits its ticket, obtains genuine common UNKNOWN facts, then
        // waits outside TX.
        var executor = Executors.newSingleThreadExecutor();
        var pending =
                executor.submit(
                        () -> {
                            PAUSE.set(true);
                            try {
                                return host.asMerchant(
                                        () ->
                                                ManualSourceHostFixture.call(
                                                        host.observationOwner(),
                                                        "observe",
                                                        canonical,
                                                        host.actor));
                            } finally {
                                PAUSE.remove();
                            }
                        });
        try {
            assertThat(OWNER_RETURNED.await(30, TimeUnit.SECONDS)).isTrue();
            var active = host.observationRow(original.source().subOrderNo());
            assertEmptyFinancial(active);
            assertThat(((Number) active.get("observation_epoch")).longValue())
                    .isGreaterThan(((Number) first.get("observation_epoch")).longValue());
            var ready = new HashMap<String, Object>();
            ready.putAll(
                    Map.of(
                            "entity",
                            host.entity,
                            "store",
                            host.store,
                            "actor",
                            host.actor,
                            "buyer",
                            host.buyer,
                            "sku",
                            host.sku,
                            "goods",
                            host.goods,
                            "member",
                            host.member));
            ready.put("work", work);
            ready.put("submission", number);
            ready.put("source", original.source());
            ready.put("financial", active);
            ready.put("intent", intent);
            ready.put("oldFacts", oldFacts);
            ready.put("frozenBytes", frozen);
            ready.put("frozenHash", IdempotencyScope.requestHash(frozen));
            write(host.json, protocol, "old-ready.json", ready);
            await(protocol.resolve("resume-old"), 150);
            RELEASE_OWNER.countDown();
            assertThatThrownBy(() -> pending.get(20, TimeUnit.SECONDS))
                    .hasStackTraceContaining("Immutable beauty manual source domain");
            Object mapper =
                    host.web.getBean(
                            ManualSourceHostFixture.type(
                                    "mapper.BeautyMappers$SourceObservationMapper"));
            Object latest =
                    host.asMerchant(
                            () ->
                                    ManualSourceHostFixture.call(
                                            mapper,
                                            "selectCanonical",
                                            host.entity,
                                            host.store,
                                            original.source().subOrderNo()));
            long version =
                    ((Number) ManualSourceHostFixture.call(latest, "getVersion")).longValue();
            ManualSourceHostFixture.call(latest, "setFailureCode", "OLD_WORKER_ATTEMPT");
            assertThatThrownBy(
                            () ->
                                    host.asMerchant(
                                            () ->
                                                    ManualSourceHostFixture.call(
                                                            mapper,
                                                            "completeObservation",
                                                            latest,
                                                            version)))
                    .hasStackTraceContaining("Immutable beauty manual source domain");
            String fundedWork =
                    host.open(
                            Map.of("customerRef", host.realConsumerReference()), "old-funded-open");
            var funded = fundedOriginal(host, fundedWork);
            Object fundedCanonical =
                    ManualSourceHostFixture.type("service.impl.ServiceSourceObservation$Canonical")
                            .getConstructors()[0]
                            .newInstance(
                                    host.entity,
                                    host.store,
                                    host.buyer,
                                    funded.operation().operationNo(),
                                    funded.orderNo(),
                                    funded.subOrderNo());
            var fundedObserved =
                    host.asMerchant(
                            () ->
                                    ManualSourceHostFixture.call(
                                            host.observationOwner(),
                                            "observe",
                                            fundedCanonical,
                                            host.actor));
            assertThat(ManualSourceHostFixture.call(fundedObserved, "ready")).isEqualTo(true);
            var fundedRow = host.observationRow(funded.subOrderNo());
            assertThat(fundedRow)
                    .containsEntry("admission_kind", "LEGACY_FUNDED")
                    .containsEntry("state", "READY");
            assertThat(fundedRow.get("manual_epoch")).isNull();
            var fundedBound =
                    host.successful(
                            host.http(
                                    "POST",
                                    "/biz/x/beauty/work-orders/"
                                            + fundedWork
                                            + "/service-submissions",
                                    fundedRequest(host, fundedWork, funded),
                                    "old-funded-bind-" + host.sku,
                                    false));
            assertThat(fundedBound.path("status").asText()).isEqualTo("BOUND");
            assertThat(
                            host.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM bty_trade_binding WHERE entity_no=? AND "
                                            + "source_no=?",
                                    Integer.class,
                                    host.entity,
                                    funded.subOrderNo()))
                    .isOne();
            write(
                    host.json,
                    protocol,
                    "old-complete.json",
                    Map.of(
                            "lateCompletionRejected",
                            true,
                            "exactOldMapperRejected",
                            true,
                            "legacyInsertAndCompletionReady",
                            true,
                            "fundedSource",
                            funded));
        } finally {
            RELEASE_OWNER.countDown();
            pending.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void runCurrent(Host host, Path protocol) throws Exception {
        var ready = host.json.readTree(Files.readString(protocol.resolve("old-ready.json")));
        host.entity = ready.path("entity").asText();
        host.store = ready.path("store").asText();
        host.actor = ready.path("actor").asText();
        host.buyer = ready.path("buyer").asText();
        host.sku = ready.path("sku").asText();
        host.goods = ready.path("goods").asText();
        host.member = ready.path("member").asText();
        host.scope = new CheckoutPort.CheckoutScope("MAIN", host.entity, host.store, host.actor);
        host.testClock.at = Instant.now();
        var source =
                host.json.readValue(
                        ready.path("source").toString(),
                        ManualServiceAdmissionPort.ManualSourceFacts.class);
        var before = host.observationRow(source.subOrderNo());
        assertThat(before).containsEntry("admission_kind", "LEGACY_FUNDED");
        for (var field : ready.path("financial").properties()) {
            assertThat(protocolValue(host.json, before.get(field.getKey())))
                    .as(field.getKey())
                    .isEqualTo(field.getValue());
        }
        String number = ready.path("submission").asText();
        assertFrozen(host, ready, number);
        var currentFacts =
                host.asMerchant(
                        () ->
                                host.manual.source(
                                        host.scope,
                                        new CheckoutPort.SourceLookup(
                                                source.operation(), source.subOrderNo())));
        assertThat(currentFacts.party().identityRevision()).isEqualTo(1L);
        Object manualCanonical = host.manualCanonical(currentFacts);
        manualUnavailable = true;
        try {
            var unavailable =
                    host.asMerchant(
                            () ->
                                    ManualSourceHostFixture.call(
                                            host.observationOwner(),
                                            "observeManual",
                                            manualCanonical,
                                            host.actor));
            Object unavailableRow = ManualSourceHostFixture.call(unavailable, "row");
            assertThat(
                            ManualSourceHostFixture.call(
                                    host.observationOwner(),
                                    "manualProjection",
                                    unavailableRow,
                                    false))
                    .isNull();
            assertThat(host.observationRow(source.subOrderNo()).get("admission_kind"))
                    .isEqualTo("LEGACY_FUNDED");
        } finally {
            manualUnavailable = false;
        }
        before = host.observationRow(source.subOrderNo());
        var adopters = Executors.newSingleThreadExecutor();
        var firstAdopter =
                adopters.submit(
                        () -> {
                            PAUSE_MANUAL.set(true);
                            try {
                                return host.asMerchant(
                                        () ->
                                                ManualSourceHostFixture.call(
                                                        host.observationOwner(),
                                                        "observeManual",
                                                        manualCanonical,
                                                        host.actor));
                            } finally {
                                PAUSE_MANUAL.remove();
                            }
                        });
        try {
            assertThat(FIRST_MANUAL_REPLY.await(20, TimeUnit.SECONDS)).isTrue();
            var winner =
                    host.asMerchant(
                            () ->
                                    ManualSourceHostFixture.call(
                                            host.observationOwner(),
                                            "observeManual",
                                            manualCanonical,
                                            host.actor));
            assertThat(ManualSourceHostFixture.call(winner, "manualReady")).isEqualTo(true);
            RELEASE_MANUAL_REPLY.countDown();
            var loser = firstAdopter.get(20, TimeUnit.SECONDS);
            assertThat(ManualSourceHostFixture.call(loser, "manualReady")).isEqualTo(true);
            assertThat(((Number) ManualSourceHostFixture.call(loser, "ticket")).longValue())
                    .isGreaterThan(
                            ((Number) ManualSourceHostFixture.call(winner, "ticket")).longValue());
        } finally {
            RELEASE_MANUAL_REPLY.countDown();
            firstAdopter.cancel(true);
            adopters.shutdownNow();
            assertThat(adopters.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        var bound =
                host.bind(
                        new Host.ManualOriginal(ready.path("work").asText(), currentFacts),
                        "v9-adopt");
        assertThat(bound.path("status").asText()).isEqualTo("BOUND");
        var adopted = host.observationRow(source.subOrderNo());
        assertThat(adopted.get("id")).isEqualTo(before.get("id"));
        assertThat(adopted)
                .containsEntry("admission_kind", "MANUAL_OFFLINE")
                .containsEntry("manual_state", "READY");
        for (String field : financialFields())
            assertThat(adopted.get(field)).as(field).isEqualTo(before.get(field));
        assertThat(((Number) adopted.get("observation_epoch")).longValue())
                .isEqualTo(((Number) before.get("observation_epoch")).longValue() + 3);
        assertThat(
                        host.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_source_observation WHERE entity_no=? "
                                        + "AND source_no=?",
                                Integer.class,
                                host.entity,
                                source.subOrderNo()))
                .isOne();
        assertFrozen(host, ready, number);
        var exact = host.json.valueToTree(adopted);
        Files.createFile(protocol.resolve("resume-old"));
        await(protocol.resolve("old-complete.json"), 75);
        assertThat((JsonNode) host.json.valueToTree(host.observationRow(source.subOrderNo())))
                .isEqualTo(exact);
        // An upgraded stale completion checks domain before classifying or touching the stale
        // UNKNOWN branch.
        Object observer = host.observationOwner();
        var complete =
                observer.getClass()
                        .getDeclaredMethod(
                                "complete",
                                ManualSourceHostFixture.type(
                                        "service.impl.ServiceSourceObservation$Canonical"),
                                String.class,
                                long.class,
                                CheckoutPort.CheckoutSourceFacts.class);
        complete.setAccessible(true);
        Object canonical = canonical(host, source);
        var oldFinancial =
                host.json.readValue(
                        ready.path("oldFacts").toString(), CheckoutPort.CheckoutSourceFacts.class);
        var tx =
                new TransactionTemplate(
                        host.web.getBean("transactionManager", PlatformTransactionManager.class));
        tx.executeWithoutResult(
                status -> {
                    try {
                        complete.invoke(
                                observer,
                                canonical,
                                host.actor,
                                ready.path("financial").path("observation_epoch").asLong(),
                                oldFinancial);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
        assertThat((JsonNode) host.json.valueToTree(host.observationRow(source.subOrderNo())))
                .isEqualTo(exact);
        var oldRecovery =
                host.asMerchant(
                        () ->
                                ManualSourceHostFixture.call(
                                        host.bean("ServiceSubmissionService"),
                                        "recoverPending",
                                        host.entity,
                                        host.store,
                                        host.actor,
                                        number));
        assertThat(
                        host.json
                                .readTree(host.json.writeValueAsString(oldRecovery))
                                .path("blockingReasons")
                                .toString())
                .contains("SOURCE_IDENTITY_MISMATCH");
        assertThat((JsonNode) host.json.valueToTree(host.observationRow(source.subOrderNo())))
                .isEqualTo(exact);
        String originalLine = source.lines().getFirst().sourceLineNo();
        assertThat(
                        host.jdbc.queryForMap(
                                "SELECT source_kind,source_no,source_line_ref,allocated_qty FROM"
                                    + " bty_source_allocation_guard WHERE entity_no=? AND"
                                    + " source_no=?",
                                host.entity,
                                source.subOrderNo()))
                .containsEntry("source_kind", "STORE_ORDER")
                .containsEntry("source_no", source.subOrderNo())
                .containsEntry("source_line_ref", originalLine);
        assertThat(
                        host.jdbc.queryForObject(
                                "SELECT allocated_qty FROM bty_source_allocation_guard WHERE "
                                        + "entity_no=? AND source_no=?",
                                BigDecimal.class,
                                host.entity,
                                source.subOrderNo()))
                .isEqualByComparingTo("1");
        var funded =
                host.json
                        .readValue(
                                Files.readString(protocol.resolve("old-complete.json")),
                                JsonNode.class)
                        .path("fundedSource");
        var fundedFacts =
                host.json.readValue(funded.toString(), CheckoutPort.CheckoutSourceFacts.class);
        Object forged =
                ManualSourceHostFixture.type(
                                "service.impl.ServiceSourceObservation$ManualCanonical")
                        .getConstructors()[0]
                        .newInstance(
                                host.entity,
                                host.store,
                                new ManualServiceAdmissionPort.CustomerParty(
                                        "USER", host.buyer, null, 1L),
                                fundedFacts.operation().operationNo(),
                                fundedFacts.orderNo(),
                                fundedFacts.subOrderNo());
        var fundedReady = host.json.valueToTree(host.observationRow(fundedFacts.subOrderNo()));
        assertThatThrownBy(
                        () ->
                                host.asMerchant(
                                        () ->
                                                ManualSourceHostFixture.call(
                                                        observer,
                                                        "observeManual",
                                                        forged,
                                                        host.actor)))
                .isInstanceOf(BizException.class);
        assertThat((JsonNode) host.json.valueToTree(host.observationRow(fundedFacts.subOrderNo())))
                .isEqualTo(fundedReady);
        host.asMerchant(
                () ->
                        ManualSourceHostFixture.call(
                                observer,
                                "invalidateKnown",
                                host.entity,
                                host.store,
                                host.buyer,
                                fundedFacts.operation().operationNo(),
                                host.actor));
        var unknownFundedRow = host.observationRow(fundedFacts.subOrderNo());
        assertThat(unknownFundedRow).containsEntry("state", "UNKNOWN");
        assertThat(unknownFundedRow.get("source_revision")).isNotNull();
        assertThat(unknownFundedRow.get("verified_revision")).isNotNull();
        var unknownFunded = host.json.valueToTree(unknownFundedRow);
        assertThatThrownBy(
                        () ->
                                host.asMerchant(
                                        () ->
                                                ManualSourceHostFixture.call(
                                                        observer,
                                                        "observeManual",
                                                        forged,
                                                        host.actor)))
                .isInstanceOf(BizException.class);
        assertThat((JsonNode) host.json.valueToTree(host.observationRow(fundedFacts.subOrderNo())))
                .isEqualTo(unknownFunded);
        var bill =
                host.successful(
                        host.http(
                                "GET",
                                "/biz/x/beauty/work-orders/"
                                        + ready.path("work").asText()
                                        + "/bill",
                                null,
                                null,
                                false));
        assertThat(bill.path("sources").get(0).path("manualReceipt").path("provenance").asText())
                .isEqualTo("MANUAL_DECLARATION");
        assertThat(bill.path("sources").get(0).path("observation").path("canStart").asBoolean(true))
                .isFalse();
        assertFrozen(host, ready, number);
        write(
                host.json,
                protocol,
                "current-complete.json",
                Map.of(
                        "samePhysicalRow",
                        true,
                        "oldV2BytesUnchanged",
                        true,
                        "separateManualReady",
                        true,
                        "upgradedStaleUnchanged",
                        true,
                        "originalSource",
                        source.subOrderNo(),
                        "rowId",
                        adopted.get("id")));
        System.out.println(
                "TASK67_UPGRADE_SAME_ROW " + source.subOrderNo() + ' ' + adopted.get("id"));
    }

    /**
     * Transport comparisons use JSON values; JDBC Long and parsed Int must share protocol
     * semantics.
     */
    static JsonNode protocolValue(ObjectMapper json, Object value) {
        return json.readTree(json.writeValueAsString(value));
    }

    private static void assertFrozen(Host host, JsonNode ready, String number) {
        String bytes =
                host.jdbc.queryForObject(
                        "SELECT frozen_command FROM bty_service_submission WHERE "
                                + "submission_no=?",
                        String.class,
                        number);
        assertThat(bytes).isEqualTo(ready.path("frozenBytes").asText());
        assertThat(IdempotencyScope.requestHash(bytes))
                .isEqualTo(ready.path("frozenHash").asText());
        var intent =
                host.jdbc.queryForMap(
                        "SELECT id,payload_hash,request_key_hash FROM "
                                + "bty_service_submission WHERE submission_no=?",
                        number);
        for (String field : List.of("id", "payload_hash", "request_key_hash"))
            assertThat(protocolValue(host.json, intent.get(field)))
                    .isEqualTo(ready.path("intent").path(field));
    }

    private static Object canonical(Host host, ManualServiceAdmissionPort.ManualSourceFacts source)
            throws Exception {
        return ManualSourceHostFixture.type("service.impl.ServiceSourceObservation$Canonical")
                .getConstructors()[0]
                .newInstance(
                        host.entity,
                        host.store,
                        host.buyer,
                        source.operation().operationNo(),
                        source.orderNo(),
                        source.subOrderNo());
    }

    private static List<String> financialFields() {
        return List.of(
                "tenant_no",
                "entity_no",
                "store_no",
                "source_kind",
                "source_no",
                "checkout_caller",
                "checkout_operation_no",
                "order_no",
                "buyer_no",
                "state",
                "source_revision",
                "source_hash",
                "verified_revision",
                "verified_hash",
                "verified_lines",
                "verified_at",
                "observed_at",
                "failure_code",
                "created_at",
                "created_by",
                "deleted");
    }

    private static void assertEmptyFinancial(Map<String, Object> row) {
        assertThat(row).containsEntry("state", "UNKNOWN");
        for (String field :
                List.of(
                        "source_revision",
                        "source_hash",
                        "verified_revision",
                        "verified_hash",
                        "verified_lines",
                        "verified_at")) assertThat(row.get(field)).as(field).isNull();
    }

    private static Map<String, Object> fundedRequest(
            Host host, String work, CheckoutPort.CheckoutSourceFacts source) {
        var root =
                host.jdbc.queryForMap(
                        "SELECT version,scope_revision FROM bty_work_order WHERE entity_no=? "
                                + "AND store_no=? AND work_order_no=?",
                        host.entity,
                        host.store,
                        work);
        var task =
                host.jdbc.queryForMap(
                        "SELECT task_no,version,binding_revision FROM bty_service_task WHERE "
                                + "entity_no=? AND work_order_no=?",
                        host.entity,
                        work);
        return Map.of(
                "expectedRevision",
                root.get("version"),
                "expectedScopeRevision",
                root.get("scope_revision"),
                "tasks",
                List.of(
                        Map.of(
                                "taskNo",
                                task.get("task_no"),
                                "expectedRevision",
                                task.get("version"),
                                "expectedBindingRevision",
                                task.get("binding_revision"))),
                "additions",
                List.of(),
                "source",
                Map.of(
                        "mode",
                        "EXISTING",
                        "activityChoices",
                        Map.of(),
                        "checkoutOperationNo",
                        source.operation().operationNo(),
                        "subOrderNo",
                        source.subOrderNo(),
                        "sourceRevision",
                        source.sourceRevision(),
                        "sourceHash",
                        source.sourceHash(),
                        "lines",
                        List.of(
                                Map.of(
                                        "selectionRef",
                                        task.get("task_no"),
                                        "sourceLineNo",
                                        source.lines().getFirst().sourceLineNo()))));
    }

    private static CheckoutPort.CheckoutSourceFacts fundedOriginal(Host host, String work) {
        host.testClock.at = Instant.now();
        host.jdbc.update("UPDATE mch_store SET offline_pay_enabled=1 WHERE store_no=?", host.store);
        host.jdbc.update(
                "UPDATE prd_goods SET pay_modes='[\"ONLINE\",\"OFFLINE\"]' WHERE goods_no=?",
                host.goods);
        var op = new CheckoutPort.OperationRef("BEAUTY", "legacy-funded-" + host.sku);
        String scopeHash =
                host.json
                        .readTree(
                                host.jdbc.queryForObject(
                                        "SELECT frozen_scope FROM bty_work_order_context"
                                                + " WHERE work_order_no=?",
                                        String.class,
                                        work))
                        .path("scopeHash")
                        .asText();
        var quote =
                host.asMerchant(
                        () ->
                                host.checkout.prepare(
                                        host.scope,
                                        new CheckoutPort.PrepareCheckoutReq(
                                                op,
                                                "funded-prepare-" + host.sku,
                                                host.realConsumerReference(),
                                                1,
                                                scopeHash,
                                                List.of(
                                                        new CheckoutPort.CheckoutLineReq(
                                                                "legacy-funded-line", host.sku, 1)),
                                                "OFFLINE",
                                                null,
                                                Map.of(),
                                                null)));
        var consent =
                host.asBuyer(
                        () ->
                                host.checkout.confirm(
                                        new CheckoutPort.ConfirmCheckoutReq(
                                                op,
                                                quote.quoteNo(),
                                                quote.quoteRevision(),
                                                quote.quoteHash(),
                                                "funded-confirm-" + host.sku)));
        var created =
                host.asBuyer(
                        () ->
                                host.checkout.place(
                                        new CheckoutPort.PlaceCheckoutReq(
                                                op,
                                                consent.confirmationNo(),
                                                "funded-place-" + host.sku)));
        assertThat(created.phase()).isEqualTo("CREATED");
        host.successful(
                host.http(
                        "POST",
                        "/biz/checkout/" + op.operationNo() + "/offline-receipts",
                        Map.of(
                                "method",
                                "CASH",
                                "amountMinor",
                                101L,
                                "currency",
                                "CNY",
                                "receivedAt",
                                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                                "note",
                                "Actual old runtime cash"),
                        "funded-cash-" + host.sku,
                        false));
        return host.asMerchant(
                () ->
                        host.checkout.source(
                                host.scope,
                                new CheckoutPort.SourceLookup(op, created.subOrderNo())));
    }

    private static void await(Path file, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(file).exists();
    }

    private static void write(ObjectMapper json, Path directory, String name, Object value)
            throws Exception {
        Path temporary = directory.resolve(name + ".tmp");
        Files.writeString(temporary, json.writeValueAsString(value));
        Files.move(
                temporary,
                directory.resolve(name),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    static final class Host extends ManualSourceHostFixture {}

    @TestConfiguration(proxyBeanMethods = false)
    static class DriverConfiguration {
        @Bean
        static BeanPostProcessor actualCheckoutReplyBarrier() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof ManualServiceAdmissionPortImpl) {
                        var actual = spy((ManualServiceAdmissionPort) bean);
                        doAnswer(
                                        invocation -> {
                                            if (manualUnavailable)
                                                throw BizException.of(
                                                        ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
                                            Object facts = invocation.callRealMethod();
                                            if (PAUSE_MANUAL.get()
                                                    && MANUAL_PAUSED_ONCE.compareAndSet(
                                                            false, true)) {
                                                FIRST_MANUAL_REPLY.countDown();
                                                assertThat(
                                                                RELEASE_MANUAL_REPLY.await(
                                                                        30, TimeUnit.SECONDS))
                                                        .isTrue();
                                            }
                                            return facts;
                                        })
                                .when(actual)
                                .source(any(), any());
                        return actual;
                    }
                    if (!(bean instanceof CheckoutPortImpl)) return bean;
                    var actual = spy((CheckoutPort) bean);
                    doAnswer(
                                    invocation -> {
                                        var facts =
                                                (CheckoutPort.CheckoutSourceFacts)
                                                        invocation.callRealMethod();
                                        var lookup =
                                                invocation.getArgument(
                                                        1, CheckoutPort.SourceLookup.class);
                                        if (PAUSE.get()
                                                && lookup.operation()
                                                        .operationNo()
                                                        .equals(pausedOperation)) {
                                            oldFacts = facts;
                                            OWNER_RETURNED.countDown();
                                            assertThat(RELEASE_OWNER.await(180, TimeUnit.SECONDS))
                                                    .isTrue();
                                        }
                                        return facts;
                                    })
                            .when(actual)
                            .source(any(), any());
                    return actual;
                }
            };
        }
    }
}
