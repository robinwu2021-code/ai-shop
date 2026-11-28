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

    static final SubscriptionVO HOT =
            new SubscriptionVO("SUB1", "E", "A", "DEV1", "staff", List.of("HOT"), "2026-10-25T12:00:00Z", "ACTIVE", 1);

    @Test
    void grantBindsStationsAndTheEventReplayEndpointIsGone() {
        runner.run(
                c -> {
                    var owner = c.getBean(FoodKdsService.class);
                    var grant = new GrantReq("DEV1", "bridge", List.of("HOT"), 600);
                    when(owner.grant("E", "A", "staff", grant, "grant-key")).thenReturn(HOT);
                    var mvc = OperationsHttpAcceptanceTest.mvc(c);
                    String token = OperationsHttpAcceptanceTest.biz(c, "staff");
                    mvc.perform(
                                    post("/biz/x/food/kitchen-subscriptions")
                                            .header("Authorization", token)
                                            .header("Idempotency-Key", "grant-key")
                                            .contentType("application/json")
                                            .content(
                                                    "{\"deviceNo\":\"DEV1\",\"deviceActorNo\":\"bridge\",\"stationNos\":[\"HOT\"],\"lifetimeSeconds\":600}"))
                            .andExpect(jsonPath("$.data.subscriptionNo").value("SUB1"))
                            .andExpect(jsonPath("$.data.stationNos[0]").value("HOT"));
                    // 按序号拉事件已停用：屏改为收刷新后拉后厨任务列表
                    var gone =
                            mvc.perform(
                                            get("/biz/x/food/kitchen-events")
                                                    .header("Authorization", token)
                                                    .param("subscriptionNo", "SUB1"))
                                    .andReturn()
                                    .getResponse();
                    assertThat(gone.getContentAsString()).doesNotContain("nextCursor");
                    verify(owner).grant("E", "A", "staff", grant, "grant-key");
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
                                            .param("subscriptionNo", "SUB1"))
                            .andExpect(jsonPath("$.code").value(70006));
                    mvc.perform(
                                    get("/biz/x/food/kitchen-stream")
                                            .header("Authorization", OperationsHttpAcceptanceTest.biz(c, "staff"))
                                            .header("X-Store-No", "B")
                                            .param("subscriptionNo", "SUB1"))
                            .andExpect(jsonPath("$.code").value(10403));
                    verifyNoInteractions(c.getBean(FoodKdsService.class));
                });
    }

    @Test
    void streamPushesRefreshForItsStoreAndStationsAndClosesWhenTheGrantIsRevoked() {
        runner.run(
                c -> {
                    var owner = c.getBean(FoodKdsService.class);
                    var revoked = new CountDownLatch(1);
                    var reauthorized = new java.util.concurrent.atomic.AtomicBoolean();
                    when(owner.authorize("E", "A", "staff", "SUB1"))
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
                                                    .param("subscriptionNo", "SUB1"))
                                    .andExpect(status().isOk())
                                    .andExpect(request().asyncStarted())
                                    .andReturn();
                    // 连上先推一次全量刷新
                    assertThat(result.getResponse().getContentAsString())
                            .contains("event:refresh", "\"kind\":\"ALL\"", "\"stationNos\":[\"HOT\"]");
                    // 别的门店、别的工位不推；本店整店或涉及 HOT 的推
                    refresh.signal(new Signal("E", "B", FoodDeviceRefresh.KITCHEN, List.of("HOT")));
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.KITCHEN, List.of("COLD")));
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.STATION, List.of("HOT", "COLD")));
                    refresh.signal(new Signal("E", "A", FoodDeviceRefresh.QUEUE, List.of()));
                    String pushed = result.getResponse().getContentAsString();
                    assertThat(pushed.split("event:refresh", -1)).hasSize(4);
                    assertThat(pushed)
                            .contains("\"kind\":\"STATION\",\"stationNos\":[\"HOT\"]")
                            .contains("\"kind\":\"QUEUE\",\"stationNos\":[\"HOT\"]")
                            .doesNotContain("COLD");
                    // 兜底时重新核验；订阅被吊销即断开
                    assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(result.getAsyncResult(5000)).isInstanceOf(BizException.class);
                });
    }

    @Test
    void revokedInitialGrantFailsBeforeTheStreamStarts() {
        runner.run(
                c -> {
                    var owner = c.getBean(FoodKdsService.class);
                    when(owner.authorize("E", "A", "staff", "SUB1"))
                            .thenThrow(BizException.of(ErrorCode.FORBIDDEN));
                    OperationsHttpAcceptanceTest.mvc(c)
                            .perform(
                                    get("/biz/x/food/kitchen-stream")
                                            .header("Authorization", OperationsHttpAcceptanceTest.biz(c, "staff"))
                                            .param("subscriptionNo", "SUB1"))
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
