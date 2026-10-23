package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO;
import ai.neargo.shop.food.dto.SalesRestrictionQuery;
import ai.neargo.shop.food.dto.SalesRestrictionReq;
import ai.neargo.shop.food.dto.SalesRestrictionVO;
import ai.neargo.shop.food.foodclient.RemoteSalesRestrictionService;
import ai.neargo.shop.food.spi.SalesRestrictionInternalApi.PageReceipt;
import ai.neargo.shop.food.spi.SalesRestrictionInternalApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RemoteSalesRestrictionServiceTest {
    @Test void unsupportedScopesFailBeforeRemoteEvaluation() {
        var api=mock(SalesRestrictionInternalApi.class);
        var service=new RemoteSalesRestrictionService(api);
        var time=Instant.parse("2026-10-21T12:00:00Z");
        when(api.evaluate(any())).thenReturn(new SalesRestrictionEvaluationVO(false,null,false,time,List.of()));
        for (var scope:List.of(List.of("WEB","PICKUP"),List.of("H5","PICK_UP"))) {
            assertThatThrownBy(() -> service.evaluate("E","A","S",scope.get(0),scope.get(1)))
                    .isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(api);
    }
    @Test void unsupportedScopesInSuccessfulRemoteListReceiptsAreRejected() {
        var api=mock(SalesRestrictionInternalApi.class);
        var service=new RemoteSalesRestrictionService(api);
        var time=Instant.parse("2026-10-21T12:00:00Z");
        var invalid=new SalesRestrictionVO("R","A","S","SOLD_OUT",null,null,BigDecimal.ZERO,BigDecimal.ZERO,
                time,null,"ACTIVE","x","H5","PICK_UP",0L);
        when(api.list(any())).thenReturn(new PageReceipt(List.of(invalid),1L,1L,20L));
        assertThatThrownBy(() -> service.list("E","A",new SalesRestrictionQuery(1,20,null,null)))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void strictPublicCommandRejectsCoercionAndUnknownOwnership() {
        var json = new ObjectMapper();
        for (String body : new String[] {"{\"skuNo\":1,\"mode\":\"SOLD_OUT\",\"reason\":\"x\"}",
                "{\"skuNo\":\"S\",\"mode\":\"QUOTA\",\"quota\":\"2\",\"reason\":\"x\"}",
                "{\"skuNo\":\"S\",\"mode\":\"SOLD_OUT\",\"reason\":\"x\",\"entityNo\":\"OTHER\"}"}) {
            assertThatThrownBy(() -> json.readValue(body,
                    SalesRestrictionReq.class)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void timestampsAreStringUtcInstantsWithMillisecondPrecision() {
        var json = new ObjectMapper();
        var value = json.readValue("{\"skuNo\":\"S\",\"mode\":\"SOLD_OUT\",\"reason\":\"x\","
                + "\"startsAt\":\"2026-10-21T12:00:00Z\"}", SalesRestrictionReq.class);
        assertThat(value.startsAt().toString()).isEqualTo("2026-10-21T12:00:00Z");
        assertThat(json.writeValueAsString(value)).contains("\"startsAt\":\"2026-10-21T12:00:00Z\"");
        for (String token : new String[] {"123", "[2026,10,21,12,0]", "\"2026-10-21T12:00:00\"",
                "\"2026-10-21T12:00:00.000001Z\""}) {
            assertThatThrownBy(() -> json.readValue("{\"skuNo\":\"S\",\"mode\":\"SOLD_OUT\","
                    + "\"reason\":\"x\",\"startsAt\":" + token + "}", SalesRestrictionReq.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }
    @Test void rejectsForeignOrAlteredSuccessfulReceipt() {
        var api = mock(SalesRestrictionInternalApi.class); var service = new RemoteSalesRestrictionService(api);
        var req = new SalesRestrictionReq("S", "SOLD_OUT", null, null, null, null, null, null, "x", null, null);
        var time = Instant.parse("2026-10-21T12:00:00Z");
        when(api.manage(any())).thenReturn(new SalesRestrictionVO("R", "A", "S", "SOLD_OUT", null,
                null, BigDecimal.ZERO, BigDecimal.ZERO, time, null, "ACTIVE", "x", null, null, 0L));
        assertThat(service.manage("E", "A", "U", req, "k").restrictionNo()).isEqualTo("R");
        when(api.manage(any())).thenReturn(new SalesRestrictionVO("R", "OTHER", "S", "SOLD_OUT", null,
                null, BigDecimal.ZERO, BigDecimal.ZERO, time, null, "ACTIVE", "x", null, null, 0L));
        assertThatThrownBy(() -> service.manage("E", "A", "U", req, "k")).isInstanceOf(IllegalStateException.class);
    }
}
