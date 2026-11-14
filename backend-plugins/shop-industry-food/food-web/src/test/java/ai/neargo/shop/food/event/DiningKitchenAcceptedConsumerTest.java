package ai.neargo.shop.food.event;

import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.spi.food.DiningOrderAccepted;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Control-flow tests: enqueue acknowledgement is never a claim of hardware print success. */
class DiningKitchenAcceptedConsumerTest {
    private final KitchenTaskService kitchen=mock(KitchenTaskService.class);
    private final PrintPort print=mock(PrintPort.class);
    private final FoodPrintService documents=mock(FoodPrintService.class);
    private final JsonMapper json=JsonMapper.builder().build();
    private final PrintPort.Scope scope=new PrintPort.Scope("E1","S1","SYSTEM");
    private final DiningKitchenAcceptedConsumer consumer=
            new DiningKitchenAcceptedConsumer(kitchen,json,() -> print,() -> documents);

    @Test void onlyOriginalTicketStationsAndUnstationedOrderRoutesAreEnqueuedOncePerDocument() {
        dispatched("HOT");
        configured(route("R1","KITCHEN","HOT"),route("R2","KITCHEN","COLD"),
                route("R3","KITCHEN","HOT"),route("R4","ORDER",null),
                route("R5","ORDER","HOT"),route("R6","PREBILL",null));
        consumer.handle(event());
        var order=inOrder(kitchen,print,documents);
        order.verify(kitchen).accepted("E1","S1","B1","EV1");
        order.verify(print).configuration(scope);
        order.verify(documents).enqueue(scope,new PrintDocumentReq("KITCHEN","B1","HOT"),
                "AUTO_PRINT_V1:B1:KITCHEN:HOT");
        order.verify(documents).enqueue(scope,new PrintDocumentReq("ORDER","B1",null),
                "AUTO_PRINT_V1:B1:ORDER:");
        verifyNoMoreInteractions(documents);
    }

    @Test void noMatchingRouteIsLegitimateKdsOnly() {
        dispatched("HOT"); configured(route("R2","KITCHEN","COLD"));
        consumer.handle(event());
        verify(print).configuration(scope);
        verifyNoInteractions(documents);
    }

    @Test void emptyStoreConfigurationNeedsNoPrinter() {
        dispatched("HOT"); configured();
        consumer.handle(event());
        verifyNoInteractions(documents);
    }

    @Test void failedKitchenDispatchNeverReadsPrintingOrEnqueues() {
        when(kitchen.accepted(anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalStateException("dispatch did not persist"));
        assertThatThrownBy(() -> consumer.handle(event())).isInstanceOf(IllegalStateException.class)
                .hasMessage("dispatch did not persist");
        verifyNoInteractions(print,documents);
    }

    @Test void uncertainEnqueueRetriesTheSameOriginalIdentityWithoutAnotherInitialDocument() {
        dispatched("HOT"); configured(route("R1","ORDER",null),route("R2","KITCHEN","HOT"));
        var first=new AtomicBoolean(true);
        var calls=new ArrayList<String>();
        // Model a print owner accepting the original idempotency key before its reply is lost.
        // This is an identity/control-flow model, not a device or paper-success simulation.
        var acceptedIdentities=new LinkedHashSet<String>();
        when(documents.enqueue(eq(scope),any(),anyString())).thenAnswer(call -> {
            var request=call.getArgument(1,PrintDocumentReq.class);
            String key=call.getArgument(2,String.class);
            calls.add(key); acceptedIdentities.add(request.scene()+":"+request.sourceNo()+":"+request.stationNo());
            if("KITCHEN".equals(request.scene()) && first.getAndSet(false)) throw transport();
            return List.of();
        });
        assertThatThrownBy(() -> consumer.handle(event())).isInstanceOf(RetryableOutboxException.class);
        consumer.handle(event());
        assertThat(calls).containsExactly("AUTO_PRINT_V1:B1:ORDER:","AUTO_PRINT_V1:B1:KITCHEN:HOT",
                "AUTO_PRINT_V1:B1:ORDER:","AUTO_PRINT_V1:B1:KITCHEN:HOT");
        assertThat(acceptedIdentities).containsExactly("ORDER:B1:null","KITCHEN:B1:HOT");
        verify(kitchen,times(2)).accepted("E1","S1","B1","EV1");
        verifyNoMoreInteractions(kitchen);
    }

    @Test void configurationTransportFailureIsRetryableBeforeAnyEnqueue() {
        dispatched("HOT"); when(print.configuration(scope)).thenThrow(transport());
        assertThatThrownBy(() -> consumer.handle(event())).isInstanceOf(RetryableOutboxException.class);
        verifyNoInteractions(documents);
    }

    @Test void oldConstructorCannotSilentlyClaimPrintingWorkflowCompletedWithoutOwners() {
        dispatched("HOT");
        var missing=new DiningKitchenAcceptedConsumer(kitchen,json);
        assertThatThrownBy(() -> missing.handle(event())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Kitchen printing owner unavailable");
    }

    private void dispatched(String station) {
        var task=new TaskVO("TASK1","TICKET1","B1","LINE1",null,station,"MAKE",1,
                BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,
                "QUEUED",null,null,0,"MAKE",null);
        when(kitchen.accepted("E1","S1","B1","EV1"))
                .thenReturn(new TicketVO("TICKET1","B1","MAKE",null,"QUEUED",List.of(task)));
    }
    private void configured(PrintPort.RouteReq... routes) {
        when(print.configuration(scope)).thenReturn(new PrintPort.Configuration(List.of(),List.of(),List.of(routes)));
    }
    private static PrintPort.RouteReq route(String no,String scene,String station) {
        return new PrintPort.RouteReq(no,scene,station,"DEVICE1","TEMPLATE1",1);
    }
    private static ServiceCallException transport() {
        return new ServiceCallException(CallOutcome.TIMEOUT,"printing",0,"Print owner reply unavailable");
    }
    private OutboxEnvelope event() {
        var instant=Instant.parse("2026-10-24T00:00:00Z");
        var accepted=new DiningOrderAccepted("B1","E1","S1","C1","O1","SUB1",
                LocalDate.of(2026,10,24),"DINE_IN",instant);
        return new OutboxEnvelope("EV1","PLATFORM","EV1","FOOD_BATCH","B1",
                "DINING_ORDER_ACCEPTED",1,"B1",1,json.writeValueAsString(accepted),instant);
    }
}
