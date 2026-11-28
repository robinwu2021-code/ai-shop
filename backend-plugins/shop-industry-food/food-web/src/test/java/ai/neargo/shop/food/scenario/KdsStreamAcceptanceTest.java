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
import ai.neargo.shop.food.api.stream.FoodKdsStreamTransportImpl;
import ai.neargo.shop.food.dto.FoodKdsContracts.*;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.spi.FoodDeviceRefresh;
import ai.neargo.shop.food.spi.FoodDeviceRefresh.Signal;
import ai.neargo.shop.food.spi.InProcessFoodDeviceRefresh;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 真实 HTTP + 真实 SSE 流；订阅的归属方是 mock。流只推「来刷新」（TDD-餐饮域迁移新库 §2.8 D-3）。
 */
class KdsStreamAcceptanceTest {
    final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(OperationsHttpAcceptanceTest.Host.class, KdsHost.class)
                    .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api,ops");

    static final StoreDeviceVO HOT =
            new StoreDeviceVO("DEV1", "E", "A", "KDS", "热菜厨显", null, "ACTIVE", 1, List.of("HOT"), 1);
    static final StoreDeviceVO CALL =
            new StoreDeviceVO("DEV2", "E", "A", "CALL_SCREEN", "门口叫号屏", null, "ACTIVE", 0, List.of(), 0);

    @Test
    void registerAndAssignStationsAndTheOldEndpointsAreGone() {
        runner.run(
                c -> {
                    var owner = c.getBean(FoodKdsService.class);
                    var register = new RegisterDeviceReq("KDS", "热菜厨显", null);
                    when(owner.register("E", "A", "staff", register, "reg-key")).thenReturn(HOT);
                    var stations = new DeviceStationsReq(0, List.of("HOT"));
                    when(owner.assignStations("E", "A", "staff", "DEV1", stations)).thenReturn(HOT);
                    var mvc = OperationsHttpAcceptanceTest.mvc(c);
                    String token = OperationsHttpAcceptanceTest.biz(c, "staff");
                    mvc.perform(
                                    post("/biz/x/food/devices")
                                            .header("Authorization", token)
                                            .header("Idempotency-Key", "reg-key")
                                            .contentType("application/json")
                                            .content("{\"deviceType\":\"KDS\",\"displayName\":\"热菜厨显\"}"))
                            .andExpect(jsonPath("$.data.deviceNo").value("DEV1"))
                            .andExpect(jsonPath("$.data.deviceType").value("KDS"));
                    mvc.perform(
                                    put("/biz/x/food/devices/DEV1/stations")
                                            .header("Authorization", token)
                                            .contentType("application/json")
                                            .content("{\"version\":0,\"stationNos\":[\"HOT\"]}"))
                            .andExpect(jsonPath("$.data.stationNos[0]").value("HOT"));
                    // 订阅开通、吊销与按序号拉事件都已停用
                    for (var gone : List.of(
                            post("/biz/x/food/kitchen-subscriptions").header("Authorization", token)
                                    .header("Idempotency-Key", "k").contentType("application/json").content("{}"),
                            get("/biz/x/food/kitchen-events").header("Authorization", token)
                                    .param("deviceNo", "DEV1"))) {
                        assertThat(mvc.perform(gone).andReturn().getResponse().getContentAsString())
                                .doesNotContain("subscriptionNo", "nextCursor");
                    }
                    verify(owner).register("E", "A", "staff", register, "reg-key");
                    verify(owner).assignStations("E", "A", "staff", "DEV1", stations);
                    verifyNoMoreInteractions(owner);
                });
    }

    @Test
    void streamRejectsWrongRoleAndStoreBeforeTouchingTheOwner() {
        runner.run(
                c -> {
                    var mvc = OperationsHttpAcceptanceTest.mvc(c);
                    mvc.perform(
                                    get("/biz/x/food/kitchen-stream")
                                            .header(
                                                    "Authorization",
                                                    OperationsHttpAcceptanceTest.biz(c, "cashier"))
                                            .param("deviceNo", "DEV1"))
                            .andExpect(jsonPath("$.code").value(70006));
                    mvc.perform(
                                    get("/biz/x/food/kitchen-stream")
                                            .header("Authorization", OperationsHttpAcceptanceTest.biz(c, "staff"))
                                            .header("X-Store-No", "B")
                                            .param("deviceNo", "DEV1"))
                            .andExpect(jsonPath("$.code").value(10403));
                    verifyNoInteractions(c.getBean(FoodKdsService.class));
                });
    }

    @Test
    void kdsReceivesKitchenAndStationRefreshesForItsStationsAndClosesWhenSuspended() {
        runner.run(
                c -> {
                    var owner = c.getBean(FoodKdsService.class);
                    var revoked = new CountDownLatch(1);
                    var reauthorized = new java.util.concurrent.atomic.AtomicBoolean();
                    when(owner.authorize("E", "A", "staff", "DEV1"))
                            .thenAnswer(
                                    invocation -> {
                                        if (!reauthorized.getAndSet(true)) return HOT;
                                        revoked.countDown();
                                        throw BizException.of(ErrorCode.FORBIDDEN);
                                    });
                    var refresh = c.getBean(FoodDeviceRefresh.class);
                    var result =
                            OperationsHttpAcceptanceTest.mvc(c)
                                    .perform(
                                            get("/biz/x/food/kitchen-stream")
                                                    .header(
                                                            "Authorization",
                                                            OperationsHttpAcceptanceTest.biz(c, "staff"))
                                                    .param("deviceNo", "DEV1"))
                                    .andExpect(status().isOk())
                                    .andExpect(request().asyncStarted())
                                    .andReturn();
                    // 连上先推一次全量刷新
                    assertThat(result.getResponse().getContentAsString())
                            .contains("event:refresh", "\"kind\":\"ALL\"", "\"stationNos\":[\"HOT\"]");
                    // 别的门店、别的工位、叫号的变化不推；本店整店或涉及 HOT 的后厨、工位变化推
                    refresh.signal(new Signal("E", "B", FoodDeviceRefresh.KITCHEN, List.of("HOT")));
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.KITCHEN, List.of("COLD")));
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.STATION, List.of("HOT", "COLD")));
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.QUEUE, List.of()));
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.KITCHEN, List.of()));
                    String pushed = result.getResponse().getContentAsString();
                    assertThat(pushed.split("event:refresh", -1)).hasSize(4);
                    assertThat(pushed)
                            .contains("\"kind\":\"STATION\",\"stationNos\":[\"HOT\"]")
                            .contains("\"kind\":\"KITCHEN\",\"stationNos\":[\"HOT\"]")
                            .doesNotContain("COLD", "QUEUE");
                    // 兜底时重新核验；设备被停用即断开
                    assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(result.getAsyncResult(5000)).isInstanceOf(BizException.class);
                });
    }

    @Test
    void callScreenReceivesOnlyQueueRefreshes() {
        runner.run(
                c -> {
                    when(c.getBean(FoodKdsService.class).authorize("E", "A", "staff", "DEV2")).thenReturn(CALL);
                    var refresh = c.getBean(FoodDeviceRefresh.class);
                    var result =
                            OperationsHttpAcceptanceTest.mvc(c)
                                    .perform(
                                            get("/biz/x/food/kitchen-stream")
                                                    .header("Authorization", OperationsHttpAcceptanceTest.biz(c, "staff"))
                                                    .param("deviceNo", "DEV2"))
                                    .andExpect(request().asyncStarted())
                                    .andReturn();
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.KITCHEN, List.of()));
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.STATION, List.of("HOT")));
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.QUEUE, List.of()));
                    String pushed = result.getResponse().getContentAsString();
                    assertThat(pushed.split("event:refresh", -1)).hasSize(3);
                    assertThat(pushed).contains("\"kind\":\"QUEUE\"").doesNotContain("KITCHEN", "STATION");
                });
    }

    @Test
    void suspendedDeviceFailsBeforeTheStreamStarts() {
        runner.run(
                c -> {
                    var owner = c.getBean(FoodKdsService.class);
                    when(owner.authorize("E", "A", "staff", "DEV1"))
                            .thenThrow(BizException.of(ErrorCode.FORBIDDEN));
                    OperationsHttpAcceptanceTest.mvc(c)
                            .perform(
                                    get("/biz/x/food/kitchen-stream")
                                            .header("Authorization", OperationsHttpAcceptanceTest.biz(c, "staff"))
                                            .param("deviceNo", "DEV1"))
                            .andExpect(jsonPath("$.code").value(10403))
                            .andExpect(request().asyncNotStarted());
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class KdsHost {
        @Bean
        FoodKdsService kds() {
            return mock(FoodKdsService.class);
        }

        @Bean
        FoodDeviceRefresh refresh() {
            return new InProcessFoodDeviceRefresh();
        }

        @Bean(destroyMethod = "shutdown")
        FoodKdsStreamTransportImpl streams(FoodKdsService s, FoodDeviceRefresh r) {
            return new FoodKdsStreamTransportImpl(s, r, 1);
        }

        @Bean
        BizFoodKdsController kdsController(
                FoodKdsService s,
                FoodKdsStreamTransportImpl stream,
                StoreDirectoryPort d,
                FoodRequestScopeValidator v) {
            return new BizFoodKdsController(s, stream, d, v);
        }
    }
}
