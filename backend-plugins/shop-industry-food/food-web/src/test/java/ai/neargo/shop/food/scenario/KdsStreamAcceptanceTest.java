package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizFoodKdsController;
import ai.neargo.shop.food.dto.FoodKdsContracts.*;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.impl.FoodKdsStreamServiceImpl;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Actual HTTP + actual SSE streamer; durable replay/grant owner is a mock, not a DB. */
class KdsStreamAcceptanceTest {
    final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(OperationsHttpAcceptanceTest.Host.class, KdsHost.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api,ops");

    @Test void grantAndReplayBindOriginalStationsCursorLimitAndTrustedActor() {
        runner.run(c -> {
            var owner=c.getBean(FoodKdsService.class);
            var grant=new GrantReq("DEV1","bridge",List.of("HOT"),600);
            when(owner.grant("E","A","staff",grant,"grant-key"))
                    .thenReturn(new SubscriptionVO("SUB1","E","A","DEV1","bridge",List.of("HOT"),"2026-10-25T12:00:00Z","ACTIVE",1));
            when(owner.replay("E","A","staff","SUB1",7,2)).thenReturn(page(8));
            var mvc=OperationsHttpAcceptanceTest.mvc(c); String token=OperationsHttpAcceptanceTest.biz(c,"staff");
            mvc.perform(post("/biz/x/food/kitchen-subscriptions").header("Authorization",token).header("Idempotency-Key","grant-key")
                            .contentType("application/json").content("{\"deviceNo\":\"DEV1\",\"deviceActorNo\":\"bridge\",\"stationNos\":[\"HOT\"],\"lifetimeSeconds\":600}"))
                    .andExpect(jsonPath("$.data.subscriptionNo").value("SUB1")).andExpect(jsonPath("$.data.stationNos[0]").value("HOT"));
            mvc.perform(get("/biz/x/food/kitchen-events").header("Authorization",token).param("subscriptionNo","SUB1").param("after","7").param("limit","2"))
                    .andExpect(jsonPath("$.data.events[0].eventNo").value("EV8")).andExpect(jsonPath("$.data.events[0].stationNo").value("HOT"))
                    .andExpect(jsonPath("$.data.nextCursor").value(8));
            verify(owner).grant("E","A","staff",grant,"grant-key"); verify(owner).replay("E","A","staff","SUB1",7,2);
        });
    }

    @Test void replayRejectsInvalidPagingRoleAndStoreBeforeReplayOwner() {
        runner.run(c -> {
            var mvc=OperationsHttpAcceptanceTest.mvc(c); String token=OperationsHttpAcceptanceTest.biz(c,"staff");
            mvc.perform(get("/biz/x/food/kitchen-events").header("Authorization",token).param("subscriptionNo","SUB1").param("after","-1"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get("/biz/x/food/kitchen-events").header("Authorization",token).param("subscriptionNo","SUB1").param("limit","101"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get("/biz/x/food/kitchen-events").header("Authorization",OperationsHttpAcceptanceTest.biz(c,"cashier")).param("subscriptionNo","SUB1"))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get("/biz/x/food/kitchen-events").header("Authorization",token).header("X-Store-No","B").param("subscriptionNo","SUB1"))
                    .andExpect(jsonPath("$.code").value(10403));
            verifyNoInteractions(c.getBean(FoodKdsService.class));
        });
    }

    @Test void lastEventIdResumesActualPersistedEventProjectionAndRevocationClosesStream() {
        runner.run(c -> {
            var owner=c.getBean(FoodKdsService.class);
            var revoked=new CountDownLatch(1); var calls=new AtomicInteger();
            when(owner.replay(eq("E"),eq("A"),eq("staff"),eq("SUB1"),anyLong(),eq(100))).thenAnswer(invocation -> {
                if(calls.getAndIncrement()==0) {
                    assertThat(invocation.<Long>getArgument(4)).isEqualTo(41L);
                    return page(42);
                }
                assertThat(invocation.<Long>getArgument(4)).isEqualTo(42L);
                revoked.countDown();
                throw BizException.of(ErrorCode.FORBIDDEN);
            });
            var result=OperationsHttpAcceptanceTest.mvc(c).perform(get("/biz/x/food/kitchen-stream")
                            .header("Authorization",OperationsHttpAcceptanceTest.biz(c,"staff"))
                            .header("Last-Event-ID","41").param("subscriptionNo","SUB1").param("after","3"))
                    .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
            String initial=result.getResponse().getContentAsString();
            assertThat(initial).contains("id:42", "event:KITCHEN_CHANGE", "EV42", "event:cursor").doesNotContain("EV41");
            // Await the production scheduler's first reauthorization attempt, not an arbitrary sleep.
            assertThat(revoked.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(result.getAsyncResult(5000)).isInstanceOf(BizException.class);
            assertThat(calls).hasValue(2);
            assertThat(result.getResponse().getContentAsString()).isEqualTo(initial);
        });
    }

    @Test void malformedOrNegativeLastEventIdFailsBeforeStreamAndRevokedInitialGrantFailsClosed() {
        runner.run(c -> {
            var mvc=OperationsHttpAcceptanceTest.mvc(c); String token=OperationsHttpAcceptanceTest.biz(c,"staff");
            for(String last:List.of("not-a-cursor","-1","9223372036854775808")) {
                mvc.perform(get("/biz/x/food/kitchen-stream").header("Authorization",token).header("Last-Event-ID",last).param("subscriptionNo","SUB1"))
                        .andExpect(jsonPath("$.code").value(10400)).andExpect(request().asyncNotStarted());
            }
            var owner=c.getBean(FoodKdsService.class); verifyNoInteractions(owner);
            when(owner.replay("E","A","staff","SUB1",0,100)).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
            mvc.perform(get("/biz/x/food/kitchen-stream").header("Authorization",token).param("subscriptionNo","SUB1"))
                    .andExpect(jsonPath("$.code").value(10403)).andExpect(request().asyncNotStarted());
        });
    }

    static ReplayVO page(long cursor) {
        return new ReplayVO("SUB1","E","A",List.of(new EventVO(cursor,"EV"+cursor,"HOT","KITCHEN_CHANGE","B1",7,"{\"quantity\":2}","2026-10-25T10:00:00Z")),cursor,cursor,false);
    }
    @Configuration(proxyBeanMethods=false)
    static class KdsHost {
        @Bean FoodKdsService kds() { return mock(FoodKdsService.class); }
        @Bean(destroyMethod="shutdown") FoodKdsStreamServiceImpl streams(FoodKdsService s) { return new FoodKdsStreamServiceImpl(s); }
        @Bean BizFoodKdsController kdsController(FoodKdsService s,FoodKdsStreamServiceImpl stream,StoreDirectoryPort d,FoodRequestScopeValidator v) { return new BizFoodKdsController(s,stream,d,v); }
    }
}
