package ai.neargo.shop.food.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.food.service.impl.FoodStockSyncDispatcher;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class FoodStockSyncDispatchScopeTest {
    @Test void outerSignedScopeCannotDispatchAnIntentFromAnotherStore() {
        var tx=mock(PlatformTransactionManager.class);
        var service=new FoodStockSyncDispatcher(null,null,null,null,null,null,JsonMapper.builder().build(),tx,null);
        var envelope=new OutboxEnvelope("EV","PLATFORM","EV","FOOD_STOCK_SYNC","SYNC",
                "FOOD_STOCK_SYNC_REQUESTED",1,"S",1,
                "{\"syncNo\":\"SYNC\",\"entityNo\":\"E\",\"storeNo\":\"FOREIGN\",\"generation\":1,\"action\":\"ACTIVATE\",\"payloadHash\":\"H\"}",Instant.now());
        assertThatThrownBy(()->service.dispatch("E","SIGNED",envelope)).isInstanceOfSatisfying(BizException.class,
                failure->assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(tx);
    }
}
