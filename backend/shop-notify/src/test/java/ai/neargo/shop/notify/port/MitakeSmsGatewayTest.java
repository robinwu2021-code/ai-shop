package ai.neargo.shop.notify.port;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.spi.notify.SmsPort.SmsException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class MitakeSmsGatewayTest {
    @TempDir Path temp;
    private HttpServer server;
    private String endpoint;
    private Path config;
    private final AtomicReference<String> response = new AtomicReference<>("[1]\r\nmsgid=#00000013\r\nstatuscode=1\r\nAccountPoint=126");
    private final AtomicInteger httpStatus = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Map<String, String>> form = new AtomicReference<>();

    @BeforeEach void startLocalEndpoint() throws Exception {
        config = temp.resolve("credentials.json");
        Files.writeString(config, "{\"username\":\"local-user\",\"password\":\"local-secret\"}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/SmSend", exchange -> {
            calls.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("CharsetURL=UTF-8", exchange.getRequestURI().getRawQuery());
            assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/x-www-form-urlencoded"));
            var values = new HashMap<String, String>();
            for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                var kv = pair.split("=", 2);
                values.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            form.set(values);
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/SmSend?CharsetURL=UTF-8";
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }
    private MitakeSmsGateway gateway() { return new MitakeSmsGateway(config.toString(), endpoint, new MockEnvironment()); }

    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "+886912345678", "886912345678", "+8860912345678", "+886 912-345.678"})
    void taiwanNumbersBecomeLocalMobileAndUseUtf8Form(String phone) {
        assertEquals("#00000013", gateway().sendOtp(phone, "123456").providerMsgId());
        assertEquals("0912345678", form.get().get("dstaddr"));
        assertEquals("local-user", form.get().get("username"));
        assertEquals("local-secret", form.get().get("password"));
        assertTrue(form.get().get("smbody").contains("123456"));
        assertTrue(form.get().get("smbody").contains("驗證碼"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "091234567", "09123456789", "+8613800138000", "+886212345678", "abc0912345678", "++886912345678"})
    void invalidOrForeignMobileNeverReachesTransport(String phone) {
        assertFalse(assertThrows(SmsException.class, () -> gateway().sendOtp(phone, "123456")).retryable());
        assertEquals(0, calls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"0", "1", "2", "4"})
    void acceptedStatusesRequireProviderReceipt(String status) {
        response.set("[1]\nstatuscode=" + status + "\nmsgid=#00000013\n");
        assertEquals("#00000013", gateway().sendOtp("0912345678", "123456").providerMsgId());
    }

    @ParameterizedTest @ValueSource(strings = {"e", "k", "v", "5", "6", "7", "8", "9", "*", "?"})
    void rejectedStatusesAreNotSuccessAndDoNotExposeUpstreamText(String status) {
        response.set("statuscode=" + status + "\nError=local-secret 0912345678 123456");
        var ex = assertThrows(SmsException.class, () -> gateway().sendOtp("0912345678", "123456"));
        assertFalse(ex.retryable());
        assertFalse(ex.getMessage().contains("local-secret"));
        assertFalse(ex.getMessage().contains("0912345678"));
        assertFalse(ex.getMessage().contains("123456"));
    }

    @ParameterizedTest @ValueSource(strings = {"a", "b", "l", "r"})
    void explicitTemporaryProviderRejectionCanBeRetried(String status) {
        response.set("statuscode=" + status);
        assertTrue(assertThrows(SmsException.class, () -> gateway().sendOtp("0912345678", "123456")).retryable());
        assertEquals(1, calls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"", "<html>error</html>", "statuscode=1", "msgid=#1", "msgid=\nstatuscode=1", "msgid=#1\nstatuscode=1\nstatuscode=e", "[1]\nmsgid=#1\nstatuscode=1\n[2]\nmsgid=#2\nstatuscode=1"})
    void malformedOrMultipleReceiptsFailClosed(String body) {
        response.set(body);
        assertFalse(assertThrows(SmsException.class, () -> gateway().sendOtp("0912345678", "123456")).retryable());
    }

    @Test void httpFailureDoesNotBecomeSuccessAndDoesNotAutomaticallyResend() {
        httpStatus.set(503);
        assertFalse(assertThrows(SmsException.class, () -> gateway().sendOtp("0912345678", "123456")).retryable());
        assertEquals(1, calls.get());
    }

    @Test void redirectNeverForwardsCredentials() {
        httpStatus.set(302);
        assertThrows(SmsException.class, () -> gateway().sendOtp("0912345678", "123456"));
        assertEquals(1, calls.get());
    }

    @Test void disconnectedTransportFailsWithoutResendOrSensitiveExceptionText() {
        var gateway = gateway();
        server.stop(0);
        var ex = assertThrows(SmsException.class, () -> gateway.sendOtp("0912345678", "123456"));
        assertEquals("mitake_transport_outcome_unknown", ex.getMessage());
        assertFalse(ex.retryable());
        assertNull(ex.getCause());
    }

    @Test void nonLoopbackHttpAndCredentialBearingUrlsAreRejectedBeforeSending() {
        for (String url : new String[]{"http://smsapi.mitake.com.tw/api/mtk/SmSend?CharsetURL=UTF-8",
                "https://user:secret@example.com/SmSend?CharsetURL=UTF-8", "https://example.com/SmSend?password=secret"}) {
            assertThrows(IllegalStateException.class, () -> new MitakeSmsGateway(config.toString(), url, new MockEnvironment()));
        }
        assertEquals(0, calls.get());
    }

    @Test void missingOrMalformedCredentialsFailWithoutExposingFileContents() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MitakeSmsGateway("", endpoint, new MockEnvironment()));
        Files.writeString(config, "{\"password\":\"secret-in-broken-json");
        var ex = assertThrows(IllegalStateException.class, this::gateway);
        assertFalse(ex.getMessage().contains("secret-in-broken-json"));
        assertNull(ex.getCause());
    }

    @Test void unconfiguredNotificationNeverCallsTransport() {
        assertThrows(SmsException.class, () -> gateway().sendOrderPaid("0912345678", "order1", "100"));
        assertThrows(SmsException.class, () -> gateway().sendAfterSaleApplied("0912345678", "refund1"));
        assertThrows(SmsException.class, () -> gateway().sendShipToRecipient("0912345678", "name", "https://example.com"));
        assertEquals(0, calls.get());
    }

    @Test void everySmsOtpSceneHasItsOwnPurposeAndUnknownSceneIsRejected() {
        var gateway = gateway();
        for (String scene : new String[]{"LOGIN", "PASSWORD_RESET", "PHONE_CHANGE", "ORDER_PICKUP_CONFIRM"}) {
            gateway.sendOtpForScene("0912345678", "123456", scene, "OTP", null);
            assertTrue(form.get().get("smbody").contains("123456"));
        }
        assertThrows(SmsException.class, () -> gateway.requireOtpScene("EMAIL_CHANGE"));
        assertThrows(SmsException.class, () -> gateway.requireOtpScene("UNKNOWN"));
    }

    @Test void selectionPreservesDefaultStubAndChoosesOnlyMitakeWhenConfigured() {
        var runner = new ApplicationContextRunner().withUserConfiguration(StubSmsGateway.class, AliSmsGateway.class, MitakeSmsGateway.class);
        runner.run(c -> assertInstanceOf(StubSmsGateway.class, c.getBean("smsGateway")));
        runner.withPropertyValues("shop.sms.stub=false", "shop.sms.provider=mitake",
                "shop.sms.mitake.config-file=" + config, "shop.sms.mitake.endpoint=" + endpoint)
                .run(c -> { assertNull(c.getStartupFailure()); assertInstanceOf(MitakeSmsGateway.class, c.getBean("smsGateway")); });
        runner.withPropertyValues("shop.sms.stub=false", "shop.sms.provider=mitake")
                .run(c -> assertNotNull(c.getStartupFailure()));
        runner.withPropertyValues("shop.sms.stub=false", "shop.sms.ali.access-key-id=local-ak",
                "shop.sms.ali.access-key-secret=local-sk", "shop.sms.ali.sign=Local",
                "shop.sms.ali.templates.otp=LOCAL_OTP")
                .run(c -> { assertNull(c.getStartupFailure()); assertInstanceOf(AliSmsGateway.class, c.getBean("smsGateway")); });
        runner.withPropertyValues("shop.sms.stub=false", "shop.sms.provider=unknown")
                .run(c -> assertFalse(c.containsBean("smsGateway")));
    }

    @Test void privateUmeCredentialsCanBeUsedOnlyAgainstLoopbackWhenExplicitlyRequested() {
        String privateFile = System.getProperty("mitake.local.credentials");
        org.junit.jupiter.api.Assumptions.assumeTrue(privateFile != null);
        assertEquals("127.0.0.1", java.net.URI.create(endpoint).getHost());
        var gateway = new MitakeSmsGateway(privateFile, endpoint, new MockEnvironment());
        assertNotNull(gateway.sendOtp("0912345678", "123456").providerMsgId());
        assertFalse(form.get().get("username").isBlank());
        assertFalse(form.get().get("password").isBlank());
    }
}
