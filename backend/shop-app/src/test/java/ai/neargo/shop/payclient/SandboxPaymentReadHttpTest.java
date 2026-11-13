package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.*;
import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.payclient.impl.RemoteBizPaymentAppService;
import ai.neargo.shop.portal.biz.pay.BizPaymentController;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceInterceptor;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class SandboxPaymentReadHttpTest {
    record Seen(String method,String path,String caller,String credential,String token,String body,
            int callerHeaders) {}

    @Test void actualHttpHeadersDtosAndReadOnlyIntentRemainSeparateFromTheDefaultClient() throws Exception {
        var fixture=new PaymentReadRouterTest();fixture.setup();
        var seen=new CopyOnWriteArrayList<Seen>();var json=new ObjectMapper();
        var row=new PaymentRow("ORDER1","QR_CODE","NEWEBPAY_TEST","CARD","TWD",12300,
                12300,0,"SUCCEEDED","masked",null,null,null,1700000000000L,1699999999000L);
        var detail=new Detail(row,new Amounts(12300,0,12300,12,false,10L,12288),
                null,null,new Refs("ORDER1","OUT1","TRADE1","MID1"),null,null,
                List.of(),List.of(new Event(1700000000000L,"PAID",12300L,"TRADE1")),"note");
        var summary=new Summary("TWD",LocalDate.of(2026,11,14),LocalDate.of(2026,11,14),
                new Count(1,12300),new Count(0,0),12300,List.of(new ChannelCount("NEWEBPAY_TEST",1,12300)),12,false,null);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String caller=exchange.getRequestHeaders().getFirst("X-Internal-Caller");
            String path=exchange.getRequestURI().getPath();
            seen.add(new Seen(exchange.getRequestMethod(),path,caller,
                    exchange.getRequestHeaders().getFirst("X-Internal-Credential"),
                    exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                    new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().get("X-Internal-Caller").size()));
            Object data=path.endsWith("/search")?new PaymentPage(List.of(row),"NEXT")
                    :path.endsWith("/summary")?summary:path.endsWith("/lookup")?new Lookup(List.of(row),List.of())
                    :path.endsWith("/collect-status")?new CollectStatus(true,List.of(caller)):detail;
            byte[] response=json.writeValueAsBytes(PortResult.ok(data));
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var env=new MockEnvironment().withProperty("shop.pay.internal-caller","AI_SHOP_UME")
                    .withProperty("shop.pay.internal-caller-credentials.AI_SHOP_UME","prod-test-credential");
            var beans=new StaticListableBeanFactory(Map.of("defaultPayCaller",new PayClientConfig().payCallerHeaders(env)));
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var http=new InternalHttp(service->Optional.of(base),"shared-test-token",
                    beans.getBeanProvider(InternalServiceInterceptor.class));
            var normal=new RemoteBizPaymentAppService(http,fixture.audit);
            var router=new PaymentReadRouter(normal,fixture.configured,http,fixture.jdbc.getDataSource(),fixture.identities,fixture.audit);
            var mvc=MockMvcBuilders.standaloneSetup(new BizPaymentController(normal,router)).build();
            mvc.perform(get("/biz/payment/collect-status").header(PaymentReadRouter.HEADER,"SANDBOX"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.channels[0]").value("AI_SHOP_UME_SANDBOX"));
            mvc.perform(get("/biz/payment/orders").header(PaymentReadRouter.HEADER,"SANDBOX")
                    .param("source","QR_CODE").param("channel","NEWEBPAY_TEST").param("status","SUCCEEDED")
                    .param("cursor","EXACT").param("size","20"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].paidMinor").value(12300))
                    .andExpect(jsonPath("$.nextCursor").value("NEXT"));
            mvc.perform(get("/biz/payment/orders/summary").header(PaymentReadRouter.HEADER,"SANDBOX"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.collected.amountMinor").value(12300));
            mvc.perform(get("/biz/payment/orders/lookup").header(PaymentReadRouter.HEADER,"SANDBOX")
                    .param("amountMinor","12300").param("within","10m"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.matches[0].payOrderNo").value("ORDER1"));
            mvc.perform(get("/biz/payment/orders/ORDER1").header(PaymentReadRouter.HEADER,"SANDBOX"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.row.succeededAt").value(1700000000000L))
                    .andExpect(jsonPath("$.amounts.paidMinor").value(12300)).andExpect(jsonPath("$.refs.channelTradeNo").value("TRADE1"));
            assertThat(seen).hasSize(5);
            for(var request:seen){
                assertThat(request.caller()).isEqualTo("AI_SHOP_UME_SANDBOX");
                assertThat(request.credential()).isEqualTo("private-test-credential");
                assertThat(request.token()).isEqualTo("shared-test-token");assertThat(request.callerHeaders()).isEqualTo(1);
            }
            assertThat(seen.stream().filter(r->r.method().equals("POST")).map(Seen::path).toList())
                    .containsExactly("/internal/pay/v1/biz/stores/S1/payment-orders/search");
            var search=json.readTree(seen.get(1).body());
            assertThat(search.path("operatorNo").asString()).isEqualTo("OP1");
            assertThat(search.path("query").path("cursor").asString()).isEqualTo("EXACT");
            assertThat(search.path("query").path("channels").get(0).asString()).isEqualTo("NEWEBPAY_TEST");
            mvc.perform(get("/biz/payment/collect-status")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.channels[0]").value("AI_SHOP_UME"));
            mvc.perform(get("/biz/payment/collect-status").header(PaymentReadRouter.HEADER,"PRODUCTION"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.channels[0]").value("AI_SHOP_UME"));
            assertThat(seen.getLast().credential()).isEqualTo("prod-test-credential");
            assertThat(http.client("PAY",PayBizInternalApi.class,Duration.ofSeconds(2))
                    .collectStatus("S1","OP1").value().channels()).containsExactly("AI_SHOP_UME");
        } finally {server.stop(0);fixture.clear();}
    }
}
