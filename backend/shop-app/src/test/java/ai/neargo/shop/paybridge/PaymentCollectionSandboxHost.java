package ai.neargo.shop.paybridge;

import static org.mockito.Mockito.mock;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.message.api.biz.BizMessageController;
import ai.neargo.shop.portal.biz.BizPaymentEventsController;
import ai.neargo.shop.portal.internal.InternalPaymentCollectionController;
import ai.neargo.shop.spi.trade.AfterSalePendingQueryPort;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import jakarta.validation.Validation;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * localhost-only executable test host. Uses the production HTTP receiver, message service, Outbox
 * and SSE.
 */
public final class PaymentCollectionSandboxHost implements AutoCloseable {
    @TestConfiguration
    @EnableWebMvc
    static class Web {}

    final PaymentCollectionMysqlFixture fixture;
    final org.springframework.boot.web.server.WebServer server;
    final AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
    final String origin;

    PaymentCollectionSandboxHost(PaymentCollectionMysqlFixture fixture, int port) throws Exception {
        this(fixture, port, fixture.stream);
    }

    PaymentCollectionSandboxHost(
            PaymentCollectionMysqlFixture fixture, int port, PaymentCollectionStream stream)
            throws Exception {
        this.fixture = fixture;
        web.register(Web.class);
        web.addBeanFactoryPostProcessor(
                beans -> {
                    beans.registerSingleton(
                            "receiver",
                            new InternalPaymentCollectionController(
                                    fixture.security,
                                    fixture.service,
                                    fixture.json,
                                    Validation.buildDefaultValidatorFactory().getValidator()));
                    beans.registerSingleton("stream", new BizPaymentEventsController(stream));
                    beans.registerSingleton("queries", new Queries(fixture));
                    beans.registerSingleton("apiAdvice", new ApiResponseWrapper());
                    beans.registerSingleton("globalErrors", new GlobalExceptionHandler());
                    beans.registerSingleton("paymentProtocolErrors", new PaymentCollectionExceptionHandler());
                    beans.registerSingleton(
                            "messages",
                            new BizMessageController(
                                    fixture.messages,
                                    mock(AfterSalePendingQueryPort.class),
                                    fixture.identities));
                });
        var factory = new TomcatServletWebServerFactory(port);
        factory.setAddress(java.net.InetAddress.getByName("127.0.0.1"));
        server =
                factory.getWebServer(
                        context -> {
                            var servlet =
                                    context.addServlet("dispatcher", new DispatcherServlet(web));
                            servlet.setLoadOnStartup(1);
                            servlet.addMapping("/");
                            servlet.setAsyncSupported(true);
                            var filter =
                                    context.addFilter(
                                            "test-identities",
                                            new Filter() {
                                                @Override
                                                public void doFilter(
                                                        ServletRequest request,
                                                        ServletResponse response,
                                                        FilterChain chain)
                                                        throws IOException, ServletException {
                                                    var req = (HttpServletRequest) request;
                                                    var res = (HttpServletResponse) response;
                                                    if (!req.getRequestURI().startsWith("/biz/")) {
                                                        chain.doFilter(request, response);
                                                        return;
                                                    }
                                                    String authorization =
                                                            req.getHeader("Authorization");
                                                    var session =
                                                            authorization != null
                                                                            && authorization
                                                                                    .startsWith(
                                                                                            "Bearer"
                                                                                                + " ")
                                                                    ? fixture.tokens.get(
                                                                            authorization.substring(
                                                                                    7))
                                                                    : Optional
                                                                            .<TokenStore
                                                                                            .SessionData>
                                                                                    empty();
                                                    if (session.isEmpty()) {
                                                        res.sendError(401);
                                                        return;
                                                    }
                                                    String store = req.getHeader("X-Store-No");
                                                    var ctx =
                                                            fixture.identities.resolveStrict(
                                                                    session.get().user(), store);
                                                    String entity = req.getHeader("X-Entity-No");
                                                    if (!ctx.hasStoreAccess(store)
                                                            || (entity != null
                                                                    && !entity.equals(
                                                                            ctx.merchantNo()))) {
                                                        res.sendError(403);
                                                        return;
                                                    }
                                                    BizContext.set(ctx);
                                                    SecurityContextHolder.getContext()
                                                            .setAuthentication(
                                                                    new UsernamePasswordAuthenticationToken(
                                                                            session.get().user(),
                                                                            null,
                                                                            List.of()));
                                                    try {
                                                        chain.doFilter(request, response);
                                                    } finally {
                                                        BizContext.clear();
                                                        SecurityContextHolder.clearContext();
                                                    }
                                                }
                                            });
                            filter.setAsyncSupported(true);
                            filter.addMappingForUrlPatterns(
                                    EnumSet.of(DispatcherType.REQUEST), false, "/*");
                        });
        server.start();
        origin = "http://127.0.0.1:" + server.getPort();
        fixture.runner.start();
    }

    @TestComponent
    @RestController
    static class Queries {
        final PaymentCollectionMysqlFixture f;

        Queries(PaymentCollectionMysqlFixture f) {
            this.f = f;
        }

        @GetMapping("/biz/payment/orders/{payOrderNo}")
        public Object detail(@PathVariable String payOrderNo) {
            if (!BizContext.current().can(BizPerms.PAY_ORDER_VIEW))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            var rows =
                    f.jdbc.queryForList(
                            "SELECT payload,store_no FROM payment_collection_receipt WHERE"
                                    + " pay_order_no=? AND entity_no=? AND store_no=?",
                            payOrderNo,
                            BizContext.current().merchantNo(),
                            BizContext.current().currentStoreNo());
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var fact =
                    f.json.readValue(
                            (String) rows.getFirst().get("payload"), PaymentCollectionFact.class);
            return Map.of(
                    "row",
                    row(fact, (String) rows.getFirst().get("store_no")),
                    "amounts",
                    Map.of("paidMinor", fact.paidMinor(), "refundedMinor", 0),
                    "source",
                    Map.of("source", "CHECKOUT_QR".equals(fact.bizType()) ? "QR_CODE" : "COLLECT"));
        }

        @GetMapping("/biz/payment/orders")
        public Object orders() {
            if (!BizContext.current().can(BizPerms.PAY_ORDER_VIEW))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            var rows =
                    f.jdbc.queryForList(
                            "SELECT payload,store_no FROM payment_collection_receipt WHERE"
                                    + " entity_no=? AND store_no=? ORDER BY created_at DESC",
                            BizContext.current().merchantNo(),
                            BizContext.current().currentStoreNo());
            return Map.of(
                    "items",
                    rows.stream()
                            .map(
                                    r ->
                                            row(
                                                    f.json.readValue(
                                                            (String) r.get("payload"),
                                                            PaymentCollectionFact.class),
                                                    (String) r.get("store_no")))
                            .toList());
        }

        private Map<String, Object> row(PaymentCollectionFact fact, String store) {
            return Map.ofEntries(
                    Map.entry("payOrderNo", fact.payOrderNo()),
                    Map.entry("storeNo", store),
                    Map.entry("status", "SUCCEEDED"),
                    Map.entry(
                            "source", "CHECKOUT_QR".equals(fact.bizType()) ? "QR_CODE" : "COLLECT"),
                    Map.entry("channel", fact.payChannel()),
                    Map.entry("currency", fact.currency()),
                    Map.entry("amountMinor", fact.amountMinor()),
                    Map.entry("paidMinor", fact.paidMinor()),
                    Map.entry("succeededAt", fact.succeededAt()),
                    Map.entry("refundedMinor", 0));
        }
    }

    @Override
    public void close() {
        server.stop();
        web.close();
    }

    public static void main(String[] args) throws Exception {
        var fixture = new PaymentCollectionMysqlFixture();
        var host =
                new PaymentCollectionSandboxHost(
                        fixture,
                        Integer.parseInt(
                                PaymentCollectionMysqlFixture.env("PAYMENT_SSE_PORT", "0")));
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    host.close();
                                    fixture.close();
                                }));
        Path ready = Path.of(Objects.requireNonNull(System.getenv("PAYMENT_SSE_READY_FILE")));
        Files.writeString(
                ready,
                fixture.json.writeValueAsString(
                        Map.of(
                                "origin",
                                host.origin,
                                "schema",
                                fixture.schema,
                                "pid",
                                ProcessHandle.current().pid())));
        new CountDownLatch(1).await();
    }
}
