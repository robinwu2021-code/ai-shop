package ai.neargo.shop.food.server.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.food.FoodLocalStockPort.Scope;
import ai.neargo.shop.spi.food.FoodStockReplenishmentInternalApi;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.Mapping;
import ai.neargo.shop.spi.product.LocalStockControlPort;
import ai.neargo.shop.spi.product.StockReplenishmentClaim;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FoodReplenishmentAdmissionContractTest {
    private static final Scope S=new Scope("E","S");
    private static final Mapping M=new Mapping("RAW","G",BigDecimal.ONE);
    private static final Outbound F=new Outbound("INV","OWNER","OUT",1,"I","G",BigDecimal.TEN,"S",20);

    @Test void oldHttpRegistrationCannotCreateANewUnclaimedOriginal(){
        var target=mock(FoodStockReplenishmentPort.class);var controller=new InternalFoodStockReplenishmentController(target);
        assertThatThrownBy(()->controller.register(new FoodStockReplenishmentInternalApi.Register(S,"A","key",F,M)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(()->controller.registerManual(new FoodStockReplenishmentInternalApi.ManualRegister(S,"A","key",F,M,"MOV")))
                .isInstanceOf(BizException.class);
        verify(target,never()).register(any(),any(),any(),any(),any());
        verify(target,never()).registerManual(any(),any(),any(),any(),any(),any());
    }

    @Test void signedHostClaimMustBindTheActualSourceMappingScopeAndFoodOwner(){
        var target=mock(FoodStockReplenishmentPort.class,CALLS_REAL_METHODS);
        var controller=new InternalFoodStockReplenishmentController(target);
        var scope=new LocalStockControlPort.Scope("MAIN","E","S");
        var claim=new StockReplenishmentClaim("SRP","FOOD_LOCAL",StockReplenishmentClaim.binding(scope,"FOOD_LOCAL",F,"RAW","G",BigDecimal.ONE));
        controller.registerClaimed(new FoodStockReplenishmentInternalApi.RegisterClaimed(S,"A","key",F,M,claim,null));
        verify(target).register(S,"A","key",F,M);
        assertThatThrownBy(()->controller.registerClaimed(new FoodStockReplenishmentInternalApi.RegisterClaimed(S,"A","other",F,
                new Mapping("DIFFERENT","G",BigDecimal.ONE),claim,null))).isInstanceOf(BizException.class);
        verify(target,times(1)).register(any(),any(),any(),any(),any());
    }

    @Test void missingClaimIsRejectedByTheExistingMvcValidator()throws Exception{
        var target=mock(FoodStockReplenishmentPort.class);
        var mvc=MockMvcBuilders.standaloneSetup(new InternalFoodStockReplenishmentController(target)).build();
        mvc.perform(post(FoodStockReplenishmentInternalApi.ROOT+"/register/claimed").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":{\"entityNo\":\"E\",\"storeNo\":\"S\"},\"actorNo\":\"A\",\"key\":\"K\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(target);
    }
}
