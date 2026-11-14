package ai.neargo.shop.paybridge;

import org.springframework.boot.test.context.TestConfiguration;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.auth.LoginUser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@EnabledIfEnvironmentVariable(named = "PAYMENT_SSE_JDBC_URL", matches = "jdbc:mysql://127.*")
class PaymentCollectionHttpMysqlTest {
    private final HttpClient http = HttpClient.newHttpClient();

    private record Frame(String event, String id, String data) {}

    private Frame frame(BufferedReader reader) throws Exception {
        var future =
                CompletableFuture.supplyAsync(
                        () -> {
                            String event = "", id = null, data = "", line;
                            try {
                                while ((line = reader.readLine()) != null) {
                                    if (line.isEmpty() && !event.isEmpty())
                                        return new Frame(event, id, data);
                                    if (line.startsWith("event:")) event = line.substring(6).trim();
                                    if (line.startsWith("id:")) id = line.substring(3).trim();
                                    if (line.startsWith("data:")) data = line.substring(5).trim();
                                }
                                throw new IllegalStateException("stream closed");
                            } catch (IOException failure) {
                                throw new UncheckedIOException(failure);
                            }
                        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private HttpRequest.Builder request(PaymentCollectionSandboxHost host, String path) {
        return HttpRequest.newBuilder(URI.create(host.origin + path))
                .header("Authorization", "Bearer " + host.fixture.token)
                .header("X-Store-No", "ST_SSE_SANDBOX")
                .header("X-Entity-No", "ENT_SSE_SANDBOX");
    }

    private HttpResponse<String> post(
            PaymentCollectionSandboxHost host, String raw, String environment) throws Exception {
        var f = host.fixture;
        String at = Long.toString(Instant.now().getEpochSecond()),
                nonce = UUID.randomUUID().toString();
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        host.origin
                                                + "/internal/payment-results/merchant-collections"))
                        .header("Content-Type", "application/json")
                        .header("X-Payment-Timestamp", at)
                        .header("X-Payment-Nonce", nonce)
                        .header("X-Payment-Environment", environment)
                        .header(
                                "X-Payment-Signature",
                                f.security.signature(at, nonce, environment, raw))
                        .POST(HttpRequest.BodyPublishers.ofString(raw))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    @org.springframework.context.annotation.Import({
        ai.neargo.shop.common.GlobalExceptionHandler.class,
        ai.neargo.shop.common.ApiResponseWrapper.class,
        PaymentCollectionExceptionHandler.class
    })
    static class ProtocolWeb {
        @org.springframework.context.annotation.Bean
        ai.neargo.shop.auth.PermChecker perm(org.springframework.context.ApplicationContext context) {
            return new ai.neargo.shop.auth.PermChecker(context.getBeanProvider(ai.neargo.shop.auth.LivePermResolver.class));
        }
        @org.springframework.context.annotation.Bean
        PaymentCollectionStream stream() { return org.mockito.Mockito.mock(PaymentCollectionStream.class); }
        @org.springframework.context.annotation.Bean
        ai.neargo.shop.portal.biz.BizPaymentEventsController events(PaymentCollectionStream stream) {
            return new ai.neargo.shop.portal.biz.BizPaymentEventsController(stream);
        }
    }

    @Test
    void realMethodSecurityAuthenticationAndIdentityPermissionFailuresKeepHttpStatus() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture();
                var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new org.springframework.mock.web.MockServletContext());
            context.register(ProtocolWeb.class);
            context.refresh();
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
            var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/biz/payment/events")
                    .header("Authorization", "Bearer rejected");
            try {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                assertEquals(401, mvc.perform(get).andReturn().getResponse().getStatus());
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(f.user, null, List.of()));
                ai.neargo.shop.auth.BizContext.clear();
                var denied = mvc.perform(get).andReturn();
                assertInstanceOf(org.springframework.security.access.AccessDeniedException.class, denied.getResolvedException());
                assertEquals(403, denied.getResponse().getStatus());
                var picker = LoginUser.merchantByUser("USR_PICKER", "picker");
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(picker, null, List.of()));
                ai.neargo.shop.auth.BizContext.set(f.identities.resolveStrict(picker, "ST_SSE_SANDBOX"));
                var missingPermission = mvc.perform(get).andReturn();
                assertInstanceOf(ai.neargo.shop.common.BizException.class, missingPermission.getResolvedException());
                assertEquals(403, missingPermission.getResponse().getStatus());
                org.mockito.Mockito.verifyNoInteractions(context.getBean(PaymentCollectionStream.class));
            } finally {
                ai.neargo.shop.auth.BizContext.clear();
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void realGlobalAdvicePreservesProtocolErrorsAndUnwrappedAck() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture();
                var host = new PaymentCollectionSandboxHost(f, 0)) {
            String raw = f.json.writeValueAsString(f.fact(801));
            String at = Long.toString(Instant.now().getEpochSecond());
            var badSignature = http.send(
                    HttpRequest.newBuilder(URI.create(host.origin
                            + "/internal/payment-results/merchant-collections"))
                            .header("Content-Type", "application/json")
                            .header("X-Payment-Timestamp", at)
                            .header("X-Payment-Nonce", UUID.randomUUID().toString())
                            .header("X-Payment-Environment", "SANDBOX")
                            .header("X-Payment-Signature", "0".repeat(64))
                            .POST(HttpRequest.BodyPublishers.ofString(raw)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, badSignature.statusCode(), badSignature.body());
            assertEquals(403, post(host, raw.replace("\"sourceId\":42", "\"sourceId\":999"), "SANDBOX").statusCode());
            assertEquals(422, post(host, raw.replace("\"paidMinor\":100", "\"paidMinor\":99"), "SANDBOX").statusCode());
            assertEquals(400, post(host, raw.replace("\"schemaVersion\":1", "\"schemaVersion\":0"), "SANDBOX").statusCode());
            assertEquals(400, post(host, "{", "SANDBOX").statusCode());
            var missingHeader = http.send(HttpRequest.newBuilder(URI.create(host.origin
                            + "/internal/payment-results/merchant-collections"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(raw)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, missingHeader.statusCode());
            for (String table : List.of("payment_collection_receipt", "sys_outbox",
                    "sys_outbox_operation", "sys_outbox_delivery", "notify_message", "payment_collection_stream"))
                assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
            var applied = post(host, raw, "SANDBOX");
            assertEquals(200, applied.statusCode(), applied.body());
            var ack = f.json.readTree(applied.body());
            assertEquals("APPLIED", ack.path("status").asString());
            assertEquals(f.fact(801).eventId(), ack.path("eventId").asString());
            assertEquals(PaymentCollectionSecurity.hash(raw), ack.path("payloadSha256").asString());
            assertFalse(ack.has("code"));
            assertFalse(ack.has("data"));
            assertEquals(409, post(host, raw.replace("\"paidMinor\":100", "\"paidMinor\":99"), "SANDBOX").statusCode());
            assertEquals(400, http.send(request(host, "/biz/payment/events")
                    .header("Last-Event-ID", "tampered").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            String pickerToken = f.tokens.issue(ai.neargo.shop.auth.TokenStore.SessionData.of(
                    LoginUser.merchantByUser("USR_PICKER", "picker")));
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(host.origin + "/biz/payment/events"))
                    .header("Authorization", "Bearer " + pickerToken)
                    .header("X-Store-No", "ST_SSE_SANDBOX").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void httpReceiverCommitsDurableMessageSseReadyReplayAndPermissionRevocation() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture();
                var host = new PaymentCollectionSandboxHost(f, 0)) {
            var response =
                    http.send(
                            request(host, "/biz/payment/events").build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (var body = response.body();
                    var reader = new BufferedReader(new InputStreamReader(body))) {
                Frame ready = frame(reader);
                assertEquals("ready", ready.event());
                assertNotNull(ready.id());
                assertEquals("live", f.json.readTree(ready.data()).path("resume").asString());
                assertFalse(f.json.readTree(ready.data()).has("code"));
                var fact = f.fact(1);
                String raw = f.json.writeValueAsString(fact);
                assertEquals(401, post(host, raw, "PRODUCTION").statusCode());
                var applied = post(host, raw, "SANDBOX");
                assertEquals(200, applied.statusCode(), applied.body());
                var ack = f.json.readTree(applied.body());
                assertEquals("APPLIED", ack.path("status").asString());
                assertFalse(ack.has("code"));
                assertEquals(
                        PaymentCollectionSecurity.hash(raw), ack.path("payloadSha256").asString());
                Frame live = frame(reader);
                assertEquals("payment.succeeded", live.event());
                var event = f.json.readTree(live.data());
                assertEquals(fact.eventId(), event.path("eventId").asString());
                assertEquals(fact.succeededAt(), event.path("paidAt").asLong());
                assertEquals("live", event.path("delivery").asString());
                assertEquals(
                        1,
                        f.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notify_message WHERE"
                                        + " receiver_no='USR_SSE_SANDBOX'",
                                Integer.class));
                assertEquals(
                        0,
                        f.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notify_message WHERE receiver_no IN"
                                        + " ('USR_PICKER','MCH_PICKER')",
                                Integer.class));
                assertEquals(
                        0,
                        f.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payment_collection_stream WHERE"
                                        + " store_no='ST_SSE_OTHER'",
                                Integer.class));
                assertEquals(9001L, fact.storeId());
                assertEquals(
                        101,
                        f.jdbc.queryForObject(
                                "SELECT id FROM mch_store WHERE store_no='ST_SSE_SANDBOX'",
                                Integer.class));
                var duplicate = post(host, raw, "SANDBOX");
                assertEquals(
                        "DUPLICATE", f.json.readTree(duplicate.body()).path("status").asString());
                assertEquals(
                        ack.path("sourceReceiptNo").asString(),
                        f.json.readTree(duplicate.body()).path("sourceReceiptNo").asString());
                assertEquals(
                        409,
                        post(
                                        host,
                                        raw.replace("\"amountMinor\":100", "\"amountMinor\":200"),
                                        "SANDBOX")
                                .statusCode());
                var unread =
                        http.send(
                                request(host, "/biz/message/unread-count").build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(1, f.json.readTree(unread.body()).path("data").asInt());
                var replay =
                        http.send(
                                request(host, "/biz/payment/events")
                                        .header("Last-Event-ID", ready.id())
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream());
                try (var replayBody = replay.body();
                        var replayReader = new BufferedReader(new InputStreamReader(replayBody))) {
                    assertEquals(
                            "replay",
                            f.json.readTree(frame(replayReader).data()).path("resume").asString());
                    assertEquals(
                            "replay",
                            f.json.readTree(frame(replayReader).data())
                                    .path("delivery")
                                    .asString());
                }
                var other =
                        http.send(
                                HttpRequest.newBuilder(
                                                URI.create(host.origin + "/biz/payment/events"))
                                        .header("Authorization", "Bearer " + f.token)
                                        .header("X-Store-No", "ST_SSE_OTHER")
                                        .header("Last-Event-ID", live.id())
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(403, other.statusCode());
                f.tokens.revoke(f.token);
                assertEquals("access_revoked", frame(reader).event());
            }
        }
    }

    @Test
    void receiptAndProjectionRollbackDedupConcurrentSourceChecksAndScopedCommitOrder()
            throws Exception {
        try (var f = new PaymentCollectionMysqlFixture()) {
            var tx = new TransactionTemplate(f.manager);
            tx.executeWithoutResult(
                    s -> {
                        f.receive(10);
                        s.setRollbackOnly();
                    });
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM payment_collection_receipt", Integer.class));
            assertEquals(
                    0, f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class));
            var original = f.fact(11);
            String raw = f.json.writeValueAsString(original);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(() -> f.service.receive(original, raw));
                var b = pool.submit(() -> f.service.receive(original, raw));
                assertEquals(
                        Set.of("APPLIED", "DUPLICATE"),
                        Set.of(
                                a.get(10, TimeUnit.SECONDS).status(),
                                b.get(10, TimeUnit.SECONDS).status()));
            } finally {
                pool.shutdownNow();
            }
            var receipt =
                    f.jdbc.queryForObject(
                            "SELECT receipt_no FROM payment_collection_receipt", String.class);
            tx.executeWithoutResult(
                    s -> {
                        f.projection.consume(f.work(receipt));
                        s.setRollbackOnly();
                    });
            assertEquals(
                    0, f.jdbc.queryForObject("SELECT COUNT(*) FROM notify_message", Integer.class));
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM payment_collection_stream", Integer.class));
            f.projection.consume(f.work(receipt));
            f.projection.consume(f.work(receipt));
            assertEquals(
                    4, f.jdbc.queryForObject("SELECT COUNT(*) FROM notify_message", Integer.class));
            var wrong =
                    f.json.writeValueAsString(f.fact(12))
                            .replace("\"storeId\":9001", "\"storeId\":101");
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            f.service.receive(
                                    f.json.readValue(wrong, PaymentCollectionFact.class), wrong));
            assertEquals(
                    1,
                    f.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM payment_collection_receipt", Integer.class));
            var r2 = f.receive(13);
            var r3 = f.receive(14);
            String scope =
                    PaymentCollectionProjection.scope(f.user, "ENT_SSE_SANDBOX", "ST_SSE_SANDBOX");
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first =
                        executor.submit(
                                () ->
                                        tx.executeWithoutResult(
                                                s -> {
                                                    f.projection.consume(
                                                            f.work(r2.sourceReceiptNo()));
                                                    locked.countDown();
                                                    try {
                                                        release.await(10, TimeUnit.SECONDS);
                                                    } catch (InterruptedException e) {
                                                        throw new IllegalStateException(e);
                                                    }
                                                }));
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var second =
                        executor.submit(() -> f.projection.consume(f.work(r3.sourceReceiptNo())));
                assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
                assertEquals(
                        1,
                        f.jdbc.queryForObject(
                                "SELECT MAX(seq) FROM payment_collection_stream WHERE scope_key=?",
                                Integer.class,
                                scope));
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
                assertEquals(
                        List.of("P11", "P13", "P14"),
                        f.jdbc.queryForList(
                                "SELECT pay_order_no FROM payment_collection_stream WHERE"
                                        + " scope_key=? ORDER BY seq",
                                String.class,
                                scope));
                f.projection.cleanup(System.currentTimeMillis() + 1);
                assertEquals(
                        3,
                        f.jdbc.queryForObject(
                                "SELECT floor_seq FROM payment_collection_scope WHERE scope_key=?",
                                Integer.class,
                                scope));
                assertEquals(
                        0,
                        f.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payment_collection_stream", Integer.class));
                assertEquals(
                        12,
                        f.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notify_message", Integer.class));
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void secondInstanceRecoversBizCollectionAndExpiredCursorsRequireExplicitResync()
            throws Exception {
        try (var f = new PaymentCollectionMysqlFixture();
                var second =
                        new PaymentCollectionStream(
                                f.ds,
                                f.properties,
                                f.security,
                                f.identities,
                                f.tokens,
                                f.projection,
                                Clock.systemUTC());
                var host = new PaymentCollectionSandboxHost(f, 0, second)) {
            String scope =
                    PaymentCollectionProjection.scope(f.user, "ENT_SSE_SANDBOX", "ST_SSE_SANDBOX");
            String baseline = f.security.cursor(scope, 0);
            var response =
                    http.send(
                            request(host, "/biz/payment/events")
                                    .header("Last-Event-ID", baseline)
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body();
                    var reader = new BufferedReader(new InputStreamReader(body))) {
                assertEquals("ready", frame(reader).event());
                String raw =
                        f.json.writeValueAsString(f.fact(21))
                                .replace("\"sourceType\":\"CHECKOUT\"", "\"sourceType\":\"BIZ\"")
                                .replace(
                                        "\"bizType\":\"CHECKOUT_QR\"",
                                        "\"bizType\":\"BIZ_COLLECT\"");
                assertEquals(200, post(host, raw, "SANDBOX").statusCode());
                assertEquals(
                        "P21", f.json.readTree(frame(reader).data()).path("payOrderNo").asString());
                String checkoutCollect =
                        f.json.writeValueAsString(f.fact(22))
                                .replace(
                                        "\"bizType\":\"CHECKOUT_QR\"",
                                        "\"bizType\":\"BIZ_COLLECT\"");
                assertEquals(200, post(host, checkoutCollect, "SANDBOX").statusCode());
                assertEquals(
                        "P22", f.json.readTree(frame(reader).data()).path("payOrderNo").asString());
                assertEquals(
                        "CHECKOUT",
                        f.json.readTree(
                                        f.jdbc.queryForObject(
                                                "SELECT payload FROM payment_collection_receipt"
                                                        + " WHERE pay_order_no='P22'",
                                                String.class))
                                .path("sourceType")
                                .asString());
                var detail =
                        http.send(
                                request(host, "/biz/payment/orders/P21").build(),
                                HttpResponse.BodyHandlers.ofString());
                var row = f.json.readTree(detail.body()).path("data").path("row");
                assertEquals("SUCCEEDED", row.path("status").asString());
                assertEquals("ST_SSE_SANDBOX", row.path("storeNo").asString());
                assertTrue(row.path("succeededAt").asLong() > 0);
                assertEquals(
                        "PAYMENT",
                        f.jdbc.queryForObject(
                                "SELECT biz_type FROM notify_message WHERE"
                                        + " receiver_no='USR_SSE_SANDBOX' AND biz_no='P21'",
                                String.class));
                assertEquals(
                        "TWD 1.00",
                        f.jdbc.queryForObject(
                                "SELECT body FROM notify_message WHERE"
                                        + " receiver_no='USR_SSE_SANDBOX' AND biz_no='P21'",
                                String.class));
            }
            var old =
                    new PaymentCollectionSecurity(
                                    f.properties,
                                    Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)))
                            .cursor(scope, 0);
            var reset =
                    http.send(
                            request(host, "/biz/payment/events")
                                    .header("Last-Event-ID", old)
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reset.statusCode());
            assertTrue(reset.body().contains("event:resync_required"));
            assertEquals(
                    400,
                    http.send(
                                    request(host, "/biz/payment/events")
                                            .header("Last-Event-ID", baseline.substring(1))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            String other =
                    f.security.cursor(
                            PaymentCollectionProjection.scope(
                                    LoginUser.merchantByUser("USR_CLERK", ""),
                                    "ENT_SSE_SANDBOX",
                                    "ST_SSE_SANDBOX"),
                            0);
            assertEquals(
                    403,
                    http.send(
                                    request(host, "/biz/payment/events")
                                            .header("Last-Event-ID", other)
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
        }
    }

    @Test
    void revokedStorePaymentHistoryIsHiddenAcrossListReadReadAllAndGlobalUnread() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture()) {
            f.jdbc.update(
                    "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " VALUES ('MCH_CLERK','ST_SSE_OTHER','CLERK',now(),now())");
            f.jdbc.update(
                    "INSERT INTO"
                        + " payment_collection_source(environment,pay_app_id,source_type,source_id,payment_store_id,store_no,entity_no)"
                        + " VALUES"
                        + " ('SANDBOX',42,'CHECKOUT',42,9002,'ST_SSE_OTHER','ENT_SSE_SANDBOX')");
            var a = f.receive(31);
            f.projection.consume(f.work(a.sourceReceiptNo()));
            String raw =
                    f.json.writeValueAsString(f.fact(32))
                            .replace("\"storeId\":9001", "\"storeId\":9002");
            var b = f.service.receive(f.json.readValue(raw, PaymentCollectionFact.class), raw);
            f.projection.consume(f.work(b.sourceReceiptNo()));
            f.messages.pushTo(
                    "STAFF",
                    "USR_CLERK",
                    "SYSTEM",
                    "generic",
                    "keep generic",
                    null,
                    "generic-history-test");
            String aMessage =
                    f.jdbc.queryForObject(
                            "SELECT message_no FROM notify_message WHERE receiver_no='USR_CLERK'"
                                + " AND biz_no='P31'",
                            String.class);
            String bMessage =
                    f.jdbc.queryForObject(
                            "SELECT message_no FROM notify_message WHERE receiver_no='USR_CLERK'"
                                + " AND biz_no='P32'",
                            String.class);
            String staffToken =
                    f.tokens.issue(
                            ai.neargo.shop.auth.TokenStore.SessionData.of(
                                    LoginUser.merchantByUser("USR_CLERK", "clerk")));
            try (var host = new PaymentCollectionSandboxHost(f, 0)) {
                java.util.function.Function<String, HttpRequest.Builder> req =
                        path ->
                                HttpRequest.newBuilder(URI.create(host.origin + path))
                                        .header("Authorization", "Bearer " + staffToken)
                                        .header("X-Store-No", "ST_SSE_OTHER")
                                        .header("X-Entity-No", "ENT_SSE_SANDBOX");
                assertEquals(
                        3,
                        f.json.readTree(
                                        http.send(
                                                        req.apply("/biz/message/unread-count")
                                                                .build(),
                                                        HttpResponse.BodyHandlers.ofString())
                                                .body())
                                .path("data")
                                .asInt());
                f.jdbc.update(
                        "DELETE FROM mch_store_role WHERE mch_account_no='MCH_CLERK' AND"
                            + " store_no='ST_SSE_SANDBOX' AND role='CLERK'");
                f.jdbc.update(
                        "INSERT INTO"
                            + " mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                            + " VALUES ('MCH_CLERK','ST_SSE_SANDBOX','PICKER',now(),now())");
                var visible =
                        http.send(
                                req.apply("/biz/message").build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(200, visible.statusCode());
                assertFalse(visible.body().contains("P31"));
                assertFalse(visible.body().contains("ST_SSE_SANDBOX"));
                assertTrue(visible.body().contains("P32"));
                assertTrue(visible.body().contains("keep generic"));
                assertEquals(
                        2,
                        f.json.readTree(
                                        http.send(
                                                        req.apply("/biz/message/unread-count")
                                                                .build(),
                                                        HttpResponse.BodyHandlers.ofString())
                                                .body())
                                .path("data")
                                .asInt());
                for (String path :
                        List.of(
                                "/biz/message/" + aMessage + "/read",
                                "/biz/message/" + bMessage + "/read",
                                "/biz/message/read-all")) {
                    var result =
                            http.send(
                                    req.apply(path)
                                            .POST(HttpRequest.BodyPublishers.noBody())
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, result.statusCode());
                    assertFalse(result.body().contains("P31"));
                    assertFalse(result.body().contains("ST_SSE_SANDBOX"));
                    assertTrue(result.body().contains("P32"));
                    assertTrue(result.body().contains("keep generic"));
                }
                assertEquals(
                        0,
                        f.json.readTree(
                                        http.send(
                                                        req.apply("/biz/message/unread-count")
                                                                .build(),
                                                        HttpResponse.BodyHandlers.ofString())
                                                .body())
                                .path("data")
                                .asInt());
            }
        }
    }

    @Test
    void healthyReplayDrainsMoreThanOnePollBatchWithSmallBoundedQueue() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture()) {
            f.properties.setSendQueueCapacity(4);
            String scope =
                    PaymentCollectionProjection.scope(f.user, "ENT_SSE_SANDBOX", "ST_SSE_SANDBOX");
            new TransactionTemplate(f.manager)
                    .executeWithoutResult(
                            status -> {
                                f.jdbc.update(
                                        "INSERT INTO payment_collection_scope(scope_key,seq) VALUES"
                                            + " (?,120)",
                                        scope);
                                // This transport scenario seeds committed projections;
                                // financial/notification writes
                                // are covered by the separate real receiver scenarios above.
                                for (int n = 1; n <= 120; n++)
                                    f.jdbc.update(
                                            "INSERT INTO"
                                                + " payment_collection_stream(scope_key,seq,event_id,message_no,pay_order_no,store_no,paid_at,created_at)"
                                                + " VALUES (?,?,?,?,?,?,?,?)",
                                            scope,
                                            n,
                                            "BACKLOG_EVENT_" + n,
                                            "BACKLOG_MSG_" + n,
                                            "BACKLOG" + n,
                                            "ST_SSE_SANDBOX",
                                            System.currentTimeMillis(),
                                            System.currentTimeMillis());
                            });
            try (var host = new PaymentCollectionSandboxHost(f, 0)) {
                var response =
                        http.send(
                                request(host, "/biz/payment/events")
                                        .header("Last-Event-ID", f.security.cursor(scope, 0))
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body();
                        var reader = new BufferedReader(new InputStreamReader(body))) {
                    assertEquals("ready", frame(reader).event());
                    for (int n = 1; n <= 120; n++) {
                        Frame event = frame(reader);
                        assertEquals("payment.succeeded", event.event());
                        assertEquals(
                                "BACKLOG" + n,
                                f.json.readTree(event.data()).path("payOrderNo").asString());
                        assertEquals(
                                "replay",
                                f.json.readTree(event.data()).path("delivery").asString());
                    }
                    f.tokens.revoke(f.token);
                    assertEquals("access_revoked", frame(reader).event());
                }
            }
        }
    }
}
