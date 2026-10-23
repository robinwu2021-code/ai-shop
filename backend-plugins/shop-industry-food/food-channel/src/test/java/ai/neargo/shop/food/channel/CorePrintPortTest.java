package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.channel.config.FoodPrintingClientConfiguration;
import ai.neargo.shop.spi.printing.PrintInternalApi;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A lost claim response must never become a second hardware delivery. */
class CorePrintPortTest {
    @Test void realTypedTransportDistinguishesExplicitIdleFromMissingOrForeignClaimWithoutRetry() throws Exception {
        var response=new AtomicReference<>("{\"claim\":null}");
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(PrintInternalApi.ROOT+"/claim", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8))
                    .contains("\"entityNo\":\"E\"","\"storeNo\":\"S\"","\"actorNo\":\"BRIDGE\"","\"deviceNo\":\"D\"");
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodPrintingClientConfiguration.class)
                    .withBean(InternalHttp.class, () -> new InternalHttp(
                            service -> java.util.Optional.of("http://127.0.0.1:"+server.getAddress().getPort()),"wire"))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var port=context.getBean(PrintPort.class); var scope=new PrintPort.Scope("E","S","BRIDGE");
                        assertThat(port.claim(scope,"D")).isNull();
                        response.set("{}");
                        assertThatThrownBy(() -> port.claim(scope,"D")).isInstanceOf(RuntimeException.class);
                        response.set("{\"claim\":{\"jobNo\":\"J\",\"deviceNo\":\"FOREIGN\",\"attemptNo\":\"A\",\"leaseToken\":\"T\",\"leaseUntil\":\"2026-10-24T10:00:00\",\"renderedContent\":\"ticket\"}}");
                        assertThatThrownBy(() -> port.claim(scope,"D")).isInstanceOf(RuntimeException.class);
                    });
            assertThat(calls).hasValue(3);
        } finally { server.stop(0); }
    }
    private static final String TOKEN="12345678-1234-4234-9234-123456789abc";
    private static final PrintPort.Scope SCOPE=new PrintPort.Scope("E","S","BRIDGE");
    private static final LocalDateTime PAST=LocalDateTime.of(2000,1,1,10,0);
    @Test void realClaimTransportRejectsMalformedOwnerIdentityAndLeaseButDoesNotInventClockDeadline() throws Exception {
        wire("claim",w -> {
            var valid=new PrintPort.Claim("JOB","D","A1",TOKEN,PAST,"ticket");
            w.claim(valid); assertThat(w.port.claim(SCOPE,"D")).isEqualTo(valid);
            var bad=List.of(
                    new PrintPort.Claim(" ","D","A1",TOKEN,PAST,"ticket"),
                    new PrintPort.Claim("J".repeat(33),"D","A1",TOKEN,PAST,"ticket"),
                    new PrintPort.Claim("JOB","D"," ",TOKEN,PAST,"ticket"),
                    new PrintPort.Claim("JOB","D","A".repeat(33),TOKEN,PAST,"ticket"),
                    new PrintPort.Claim("JOB","D","A1","T",PAST,"ticket"),
                    new PrintPort.Claim("JOB","D","A1",TOKEN,null,"ticket"),
                    new PrintPort.Claim("JOB","D","A1",TOKEN,PAST," "),
                    new PrintPort.Claim("JOB","FOREIGN","A1",TOKEN,PAST,"ticket"));
            for(var malformed:bad) {
                w.claim(malformed);
                assertThatThrownBy(() -> w.port.claim(SCOPE,"D")).isInstanceOf(RuntimeException.class);
            }
            w.claim(valid);
            var malformed=w.json.readTree(w.response.get());
            ((tools.jackson.databind.node.ObjectNode)malformed.get("claim")).put("leaseUntil","not-a-date");
            w.response.set(malformed.toString());
            assertThatThrownBy(() -> w.port.claim(SCOPE,"D")).isInstanceOf(RuntimeException.class);
            assertThat(w.calls).hasValue(10);
        });
    }
    @Test void realAcknowledgementTransportRequiresLatestAttemptAndConsistentNormalizedReceipt() throws Exception {
        wire("acknowledge",w -> {
            var request=new PrintPort.Acknowledge("A1",TOKEN,"SUCCESS","CHANNEL","PRINTED");
            w.job("SUCCESS",attempt("A1",1,"SUCCESS","PRINTED"));
            assertThat(w.port.acknowledge(SCOPE,"D",request).status()).isEqualTo("SUCCESS");
            // A stale request appearing anywhere in history cannot prove current-lease acceptance.
            w.job("SUCCESS",attempt("A1",1,"SUCCESS","PRINTED"),attempt("A2",2,"SUCCESS","PRINTED"));
            rejectAck(w,request);
            w.job("SUCCESS",attempt("A2",2,"SUCCESS","PRINTED"),attempt("A1",1,"SUCCESS","PRINTED"));
            rejectAck(w,request);
            w.job("SUCCESS",attempt("A1",1,"UNKNOWN","PRINTED")); rejectAck(w,request);
            w.job("UNKNOWN",attempt("A1",1,"SUCCESS","PRINTED")); rejectAck(w,request);
            w.job("SUCCESS",attempt("A1",1,"SUCCESS","OTHER_RESPONSE")); rejectAck(w,request);
            w.job("SUCCESS"); rejectAck(w,request);
            w.job("SUCCESS",attempt("A1",1,"SUCCESS","PRINTED"));
            var foreign=w.json.readTree(w.response.get());
            ((tools.jackson.databind.node.ObjectNode)foreign).put("deviceNo","FOREIGN");
            w.response.set(foreign.toString()); rejectAck(w,request);
            var uncertain=new PrintPort.Acknowledge("A1",TOKEN,"FAILED",null,"NETWORK_TIMEOUT");
            w.job("FAILED",attempt("A1",1,"FAILED","NETWORK_TIMEOUT")); rejectAck(w,uncertain);
            w.job("UNKNOWN",attempt("A1",1,"UNKNOWN","NETWORK_TIMEOUT"));
            assertThat(w.port.acknowledge(SCOPE,"D",uncertain).status()).isEqualTo("UNKNOWN");
            assertThat(w.calls).hasValue(10);
        });
    }
    @Test void realAcknowledgementTransportPreservesLateAndDuplicateOriginalReceipts() throws Exception {
        wire("acknowledge",w -> {
            var success=new PrintPort.Acknowledge("A1",TOKEN,"SUCCESS","CHANNEL","PRINTED");
            // Owner accepts a late SUCCESS after timeout UNKNOWN and returns the newly confirmed receipt.
            w.job("SUCCESS",attempt("A1",1,"SUCCESS","PRINTED"));
            assertThat(w.port.acknowledge(SCOPE,"D",success).status()).isEqualTo("SUCCESS");
            assertThat(w.port.acknowledge(SCOPE,"D",success).status()).isEqualTo("SUCCESS");
            var failed=new PrintPort.Acknowledge("A1",TOKEN,"FAILED",null,"NOT_PRINTED");
            w.job("FAILED",attempt("A1",1,"FAILED","NOT_PRINTED"));
            assertThat(w.port.acknowledge(SCOPE,"D",failed).status()).isEqualTo("FAILED");
            // Retry queued a fresh delivery, but has not claimed its next lease yet.
            w.job("PENDING",attempt("A1",1,"FAILED","NOT_PRINTED"));
            assertThat(w.port.acknowledge(SCOPE,"D",failed).status()).isEqualTo("PENDING");
            var unknown=new PrintPort.Acknowledge("A1",TOKEN,"UNKNOWN",null,"RECEIPT_TIMEOUT");
            w.job("UNKNOWN",attempt("A1",1,"UNKNOWN","RECEIPT_TIMEOUT"));
            assertThat(w.port.acknowledge(SCOPE,"D",unknown).status()).isEqualTo("UNKNOWN");
            assertThat(w.calls).hasValue(5);
        });
    }
    private static void rejectAck(Wire wire,PrintPort.Acknowledge request) {
        assertThatThrownBy(() -> wire.port.acknowledge(SCOPE,"D",request)).isInstanceOf(RuntimeException.class);
    }
    private static PrintPort.AttemptVO attempt(String no,int sequence,String status,String response) {
        return new PrintPort.AttemptVO(no,sequence,status,response,PAST,PAST.plusMinutes(1));
    }
    private static class Wire {
        final AtomicReference<String> response=new AtomicReference<>(); final AtomicInteger calls=new AtomicInteger();
        final ObjectMapper json=new ObjectMapper(); PrintPort port;
        void claim(PrintPort.Claim claim) { response.set(json.writeValueAsString(new PrintInternalApi.ClaimResult(claim))); }
        void job(String status,PrintPort.AttemptVO... attempts) {
            var document=new PrintPort.Document(1,"ORDER","B","Order",null,
                    List.of(new PrintPort.Line("SKU","1",null,null)),"Order facts");
            response.set(json.writeValueAsString(new PrintPort.JobVO("JOB","S","EVENT","ORDER","D","PART",1,
                    "TPL",status,null,null,"BRIDGE",document,"ticket",List.of(attempts))));
        }
    }
    private static void wire(String operation,Consumer<Wire> check) throws Exception {
        var wire=new Wire(); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(PrintInternalApi.ROOT+"/"+operation,exchange -> {
            wire.calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8))
                    .contains("\"entityNo\":\"E\"","\"storeNo\":\"S\"","\"actorNo\":\"BRIDGE\"","\"deviceNo\":\"D\"");
            byte[] bytes=wire.response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodPrintingClientConfiguration.class)
                    .withBean(InternalHttp.class,() -> new InternalHttp(service -> java.util.Optional.of(
                            "http://127.0.0.1:"+server.getAddress().getPort()),"wire"))
                    .run(context -> { assertThat(context).hasNotFailed(); wire.port=context.getBean(PrintPort.class); check.accept(wire); });
        } finally { server.stop(0); }
    }

}
