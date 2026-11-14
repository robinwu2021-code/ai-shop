package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.scenario.support.PaymentGatewayFaultServer;
import ai.neargo.shop.scenario.support.PaymentOperationProcessMain;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Financial recovery survives actual process death; only the independent third-party TEST server is
 * substituted.
 */
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class PaymentOperationProcessRestartNativeTest extends CheckoutFlowFixture {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void crashAfterSendingCommitBeforeNetworkNeverResendsOrAllocatesAnotherNumber()
            throws Exception {
        recoverAfterActualDeath(false);
    }

    @Test
    void crashAfterThirdPartyAcceptanceRecoversRealReceiptByOriginalKeyInAnotherJvm()
            throws Exception {
        recoverAfterActualDeath(true);
    }

    private void recoverAfterActualDeath(boolean accepted) throws Exception {
        assertThat(System.getenv("CHECKOUT_NATIVE_MYSQL_URL"))
                .startsWith("jdbc:mysql://127.0.0.1:");
        var placed = placed();
        String key = "original-" + UUID.randomUUID();
        Path own = Files.createTempDirectory("task57-financial-process-");
        Path before = Files.createDirectory(own.resolve("before"));
        Path after = Files.createDirectory(own.resolve("after"));
        Path logRoot =
                Files.createDirectories(Path.of("target", "task57-process-" + UUID.randomUUID()));
        Process first = null;
        Process second = null;
        try (var channel = new PaymentGatewayFaultServer()) {
            first =
                    launch(
                            placed,
                            key,
                            accepted ? "AFTER_ACCEPT" : "BEFORE_SEND",
                            before,
                            channel,
                            logRoot.resolve("first.log"));
            waitFile(first, before.resolve("sending.json"), Duration.ofSeconds(90));
            long firstPid = first.pid();
            var marker = json.readTree(Files.readString(before.resolve("sending.json")));
            assertThat(marker.path("pid").asLong()).isEqualTo(firstPid);
            String original = marker.path("outTradeNo").asString();
            String operation =
                    jdbc.queryForObject(
                            "SELECT payment_operation_no FROM ord_checkout_payment_request"
                                    + " WHERE operation_no=?",
                            String.class,
                            placed.operation().operationNo());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT phase FROM stl_payment_operation WHERE operation_no=?",
                                    String.class,
                                    operation))
                    .isEqualTo("SENDING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT out_trade_no FROM stl_payment WHERE order_no=?",
                                    String.class,
                                    placed.orderNo()))
                    .isEqualTo(original);
            if (accepted) {
                assertThat(channel.awaitAccepted(15)).isTrue();
            }
            terminateOwned(first);
            assertThat(first.isAlive()).isFalse();
            if (accepted) {
                channel.markPaid(original);
            }
            second = launch(placed, key, "RECOVER", after, channel, logRoot.resolve("second.log"));
            assertThat(second.pid()).isNotEqualTo(firstPid);
            assertThat(second.waitFor(90, TimeUnit.SECONDS))
                    .as("second JVM must complete original-key lookup")
                    .isTrue();
            assertThat(second.exitValue()).as("child logs: %s", logRoot).isZero();
            var recovered = json.readTree(Files.readString(after.resolve("result.json")));
            assertThat(recovered.path("pid").asLong()).isEqualTo(second.pid());
            assertThat(recovered.path("code").asInt()).isZero();
            assertThat(recovered.path("httpStatus").asInt()).isEqualTo(200);
            assertThat(recovered.path("paymentOperationNo").asString()).isEqualTo(operation);
            assertThat(recovered.path("outTradeNo").asString()).isEqualTo(original);
            assertThat(recovered.path("hasParameters").asBoolean()).isFalse();
            assertThat(channel.queries()).isPositive();
            assertThat(channel.queriedNumber()).isEqualTo(original);
            assertThat(channel.sends()).isEqualTo(accepted ? 1 : 0);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND"
                                            + " direction='PAY'",
                                    Long.class,
                                    placed.orderNo()))
                    .isEqualTo(1L);
            if (accepted) {
                assertThat(recovered.path("phase").asString()).isEqualTo("SUCCEEDED");
                assertThat(recovered.path("receiptNo").asString()).startsWith("RC");
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT status FROM ord_order WHERE order_no=?",
                                        String.class,
                                        placed.orderNo()))
                        .isEqualTo("PAID");
            } else {
                assertThat(recovered.path("phase").asString()).isIn("SENDING", "UNKNOWN");
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT status FROM stl_payment WHERE order_no=?",
                                        String.class,
                                        placed.orderNo()))
                        .isEqualTo("PENDING");
            }
            System.out.println(
                    "Financial process recovery verified: mode="
                            + (accepted ? "AFTER_ACCEPT" : "BEFORE_SEND")
                            + "; firstPid="
                            + firstPid
                            + "; secondPid="
                            + second.pid()
                            + "; sends="
                            + channel.sends()
                            + "; phase="
                            + recovered.path("phase").asString()
                            + "; logs="
                            + logRoot);
        } finally {
            terminateOwned(first);
            terminateOwned(second);
            try (var paths = Files.walk(own)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private Process launch(
            CheckoutResult placed,
            String key,
            String mode,
            Path control,
            PaymentGatewayFaultServer channel,
            Path log)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        var command =
                new ProcessBuilder(
                        java,
                        "-Xmx384m",
                        "-Dcheckout.test.native=true",
                        "-cp",
                        classes,
                        PaymentOperationProcessMain.class.getName());
        var env = command.environment();
        env.put("TASK57_PROCESS_CONTROL", control.toString());
        env.put("TASK57_PROCESS_MODE", mode);
        env.put("TASK57_CHECKOUT_OPERATION", placed.operation().operationNo());
        env.put("TASK57_BUYER", buyer);
        env.put("TASK57_ORIGINAL_KEY", key);
        env.put("TASK57_FAULT_BASE", channel.baseUrl());
        return command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void waitFile(Process child, Path file, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(file) && child.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(Files.exists(file))
                .as(
                        "owned child must reach durable SENDING; pid=%s alive=%s",
                        child.pid(), child.isAlive())
                .isTrue();
    }

    private static void terminateOwned(Process child) throws InterruptedException {
        if (child != null && child.isAlive()) {
            child.destroyForcibly();
            assertThat(child.waitFor(10, TimeUnit.SECONDS))
                    .as("owned child process cleanup")
                    .isTrue();
        }
    }

    private CheckoutResult placed() {
        String merchant = "QP-" + sku;
        jdbc.update("UPDATE mch_entity SET funds_mode='DIRECT' WHERE entity_no=?", entity);
        jdbc.update("UPDATE mch_store SET pay_merchant_no=? WHERE store_no=?", merchant, store);
        jdbc.update(
                "INSERT INTO"
                    + " mch_payment_merchant(entity_no,store_no,pay_merchant_no,pay_channel,"
                        + "apply_status,sub_mchid,created_at,updated_at)"
                    + " VALUES (?,?,?,'TEST','ACTIVE','test-owner',NOW(),NOW())",
                entity,
                store,
                merchant);
        var quote = asMerchant(() -> checkout.prepare(scope, request(null)));
        var confirmation = asBuyer(() -> confirm(quote));
        return asBuyer(() -> checkout.place(place(quote, confirmation)));
    }
}
