package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DiningBatchSummaryVO;
import ai.neargo.shop.food.dto.ListDiningBatchesQuery;
import ai.neargo.shop.food.foodclient.RemoteDiningBatchQueryService;
import ai.neargo.shop.food.spi.DiningBatchQueryInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RemoteDiningBatchQueryServiceTest {
    static final ListDiningBatchesQuery QUERY=new ListDiningBatchesQuery(1,20,"OLD","CURRENT","BOUND",
            LocalDate.of(2026,10,24),"POS");
    DiningBatchSummaryVO row(String store) {
        return new DiningBatchSummaryVO("BATCH",store,"OLD",List.of("CURRENT"),true,"BOUND","POS",QUERY.businessDate(),
                "DINE_IN","POSTPAY","ORIGINAL_QUOTE","hash",Instant.EPOCH,null,null,0,List.of());
    }
    @Test void typedRemoteQueryPreservesTrustedContextAndAllSourceFilters() {
        var api=mock(DiningBatchQueryInternalApi.class);
        when(api.list(any())).thenReturn(PageData.of(List.of(row("A")),1,1,20));
        var result=new RemoteDiningBatchQueryService(api).list("E","A","staff",QUERY);
        assertThat(result.records().getFirst().quoteNo()).isEqualTo("ORIGINAL_QUOTE");
        verify(api).list(new DiningBatchQueryInternalApi.Listing("E","A","staff",QUERY));
    }
    @Test void successfulEnvelopeFromAnotherStoreOrWrongPageIsRejected() {
        var api=mock(DiningBatchQueryInternalApi.class);
        when(api.list(any())).thenReturn(PageData.of(List.of(row("B")),1,1,20));
        assertThatThrownBy(() -> new RemoteDiningBatchQueryService(api).list("E","A","staff",QUERY))
                .isInstanceOf(IllegalStateException.class);
        when(api.list(any())).thenReturn(PageData.of(List.of(row("A")),1,2,20));
        assertThatThrownBy(() -> new RemoteDiningBatchQueryService(api).list("E","A","staff",QUERY))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void originalInternalHttpCarriesOneAuthenticatedCallWithoutPerBatchFundsEnrichment() throws Exception {
        var calls=new AtomicInteger(); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(DiningBatchQueryInternalApi.LIST_PATH,exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(request).contains("\"entityNo\":\"E\"","\"storeNo\":\"A\"","\"actorNo\":\"staff\"",
                    "\"checkNo\":\"CURRENT\"","\"originalCheckNo\":\"OLD\"");
            byte[] response=JsonMapper.builder().build().writeValueAsBytes(PageData.of(List.of(row("A")),1,1,20));
            exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var http=new InternalHttp(name -> Optional.of("http://127.0.0.1:"+server.getAddress().getPort()),"wire");
            var api=http.client(ServiceName.FOOD,DiningBatchQueryInternalApi.class,Duration.ofSeconds(2));
            var result=new RemoteDiningBatchQueryService(api).list("E","A","staff",QUERY);
            assertThat(result.total()).isOne(); assertThat(calls.get()).isOne();
        } finally { server.stop(0); }
    }
}
