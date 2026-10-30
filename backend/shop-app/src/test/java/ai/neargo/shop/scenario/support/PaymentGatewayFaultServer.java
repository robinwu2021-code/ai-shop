package ai.neargo.shop.scenario.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated third-party TEST channel; its memory survives application child-process death. */
public final class PaymentGatewayFaultServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ChannelPayment> accepted = new ConcurrentHashMap<>();
    private final CountDownLatch acceptedRequest = new CountDownLatch(1);
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicInteger queries = new AtomicInteger();
    private volatile String queriedNumber;

    /**
     * Bind only an ephemeral IPv4 loopback port; no production channel credentials are involved.
     */
    public PaymentGatewayFaultServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/prepay", this::prepay);
        server.createContext("/query", this::query);
        server.start();
    }

    /** Return the test-only loopback service coordinate. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Wait for actual network acceptance, not a mocked application method invocation. */
    public boolean awaitAccepted(long seconds) throws InterruptedException {
        return acceptedRequest.await(seconds, TimeUnit.SECONDS);
    }

    /** Complete the accepted third-party payment after the original application process is dead. */
    public void markPaid(String number) {
        var current = accepted.get(number);
        if (current == null) {
            throw new IllegalStateException("TEST original number was never accepted");
        }
        accepted.put(number, new ChannelPayment(current.amount(), true));
    }

    /** Count actual accepted HTTP dispatches. */
    public int sends() {
        return sends.get();
    }

    /** Count original-number HTTP observations. */
    public int queries() {
        return queries.get();
    }

    /** Expose only the last original business identity, never a reusable token or key. */
    public String queriedNumber() {
        return queriedNumber;
    }

    private void prepay(HttpExchange exchange) throws IOException {
        var body = json.readTree(exchange.getRequestBody().readAllBytes());
        String number = body.path("outTradeNo").asString();
        long amount = body.path("amountMinor").asLong();
        if (number.isBlank() || amount <= 0) {
            throw new IllegalStateException("invalid TEST instruction");
        }
        sends.incrementAndGet();
        accepted.put(number, new ChannelPayment(amount, false));
        acceptedRequest.countDown();
        try {
            if (!releaseResponse.await(180, TimeUnit.SECONDS)) {
                throw new IOException("TEST response gate timeout");
            }
            respond(exchange, Map.of("accepted", true));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exchange.close();
        }
    }

    private void query(HttpExchange exchange) throws IOException {
        var body = json.readTree(exchange.getRequestBody().readAllBytes());
        String number = body.path("outTradeNo").asString();
        queriedNumber = number;
        queries.incrementAndGet();
        var payment = accepted.get(number);
        respond(
                exchange,
                payment == null
                        ? Map.of("found", false)
                        : Map.of(
                                "found",
                                true,
                                "paid",
                                payment.paid(),
                                "outTradeNo",
                                number,
                                "amountMinor",
                                payment.amount(),
                                "currency",
                                "CNY",
                                "tradeNo",
                                "TEST-RESTART-" + number));
    }

    private void respond(HttpExchange exchange, Object value) throws IOException {
        byte[] data = json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    @Override
    public void close() {
        releaseResponse.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private record ChannelPayment(long amount, boolean paid) {}
}
