package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.entity.*;
import ai.neargo.shop.promotion.mapper.PromotionMappers.*;
import ai.neargo.shop.promotion.service.MarketingEffectProjector;
import ai.neargo.shop.spi.marketing.PosActivityPort.Line;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PosActivityQuoteTest {
    @Test void authorizedPriceBenefitUsesOnlyMatchingGoodsAndRejectsOverstatedClientDiscount() {
        var heads=mock(ActivityMapper.class);
        var applies=mock(ApplyMapper.class);
        var scopes=mock(ActivityGoodsMapper.class);
        var service=new ActivityPricingServiceImpl(heads,mock(ActivityAudienceMapper.class),scopes,mock(MemberQueryPort.class),applies);
        var markets=mock(MarketPort.class);
        var merchant=mock(MerchantQueryPort.class);
        when(merchant.marketOf("E1")).thenReturn("TW");
        when(markets.find("TW")).thenReturn(Optional.of(new MarketPort.MarketBrief("TW","台灣","TWD",0,"Asia/Taipei",1,true)));
        ReflectionTestUtils.setField(service,"posMarkets",markets);
        ReflectionTestUtils.setField(service,"posMerchants",merchant);
        ReflectionTestUtils.setField(service,"counterJson",JsonMapper.builder().build());
        ReflectionTestUtils.setField(service,"posEffects",mock(MarketingEffectProjector.class));
        service.setRuleMapper(mock(ActivityRuleMapper.class));
        var activity=new PmtActivity();
        activity.setActivityNo("A1");activity.setEntityNo("E1");activity.setStoreNo("S1");
        activity.setOwner("MERCHANT");activity.setStatus("RUNNING");
        activity.setTriggerType("GOODS");activity.setBenefitType("PRICE");activity.setBenefitAmountMinor(60L);
        var goods=new PmtActivityGoods();goods.setScopeType("GOODS");goods.setRefNo("G1");
        when(scopes.selectList(any())).thenReturn(List.of(goods));
        when(heads.selectOne(any())).thenReturn(activity);
        when(heads.updateById(activity)).thenReturn(1);
        when(applies.insert(any(PmtApply.class))).thenReturn(1);
        var cart=List.of(new Line("G1","C1",2,100),new Line("G2","C1",1,180));
        assertThatThrownBy(()->service.apply("MAIN","E1","S1","O1","SO1",null,"TWD","A1",81,cart)).isInstanceOf(BizException.class);
        assertThatThrownBy(()->service.apply("MAIN","E1","S2","O1","SO1",null,"TWD","A1",80,cart)).isInstanceOf(BizException.class);
        verifyNoInteractions(applies);
        var use=service.apply("MAIN","E1","S1","O1","SO1",null,"TWD","A1",80,cart);
        assertThat(use.weights()).containsExactly(80,0);
        var saved=ArgumentCaptor.forClass(PmtApply.class);verify(applies).insert(saved.capture());
        assertThat(saved.getValue().getActivityVersionNo()).isEqualTo(use.versionNo()).hasSize(64);
        assertThat(saved.getValue().getPosRuleSnapshot()).contains("PRICE","G1","TWD");
        assertThat(activity.getBudgetUsedMinor()).isEqualTo(80);
    }
}
