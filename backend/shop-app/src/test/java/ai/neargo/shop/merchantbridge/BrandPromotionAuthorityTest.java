package ai.neargo.shop.merchantbridge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrandPromotionAuthorityTest {
    final Actor actor = new Actor("MAIN", "MCH:A1");
    final ManagementRepository db = mock(ManagementRepository.class);
    final ManagementScopeService grants = mock(ManagementScopeService.class);
    final BrandPromotionScopePort facts = mock(BrandPromotionScopePort.class);
    final BrandPromotionAuthority authority = new BrandPromotionAuthority(db,grants,facts);
    void setup() {
        when(db.one(anyString(),any(),any(),any())).thenReturn(Map.of("legal_entity_no","HQ"));
        when(facts.find("MAIN","M1","B1","HQ")).thenReturn(new BrandPromotionScopePort.Brand("M1","B1","HQ",2,3,
                List.of(new BrandPromotionScopePort.Store("S1","E2",1,"BD1",1,"CN"))));
    }
    @Test void operationalBrandAuthorityNeverImpliesEitherEntityFundingAuthority() {
        setup();
        assertThat(authority.resolve(actor,"M1","B1","promotion.activity.publish").entityNo()).isEqualTo("HQ");
        assertThatThrownBy(() -> authority.authorizePublication(actor,"M1","B1",List.of("S1"),"R1"))
                .isInstanceOf(BizException.class);
        verify(grants).require(actor,"M1","BRAND","B1","promotion.activity.publish");
    }
    @Test void hqAndEveryActualStoreMustBothAuthorizeParticipationAndCost() {
        setup();
        when(grants.can(eq(actor),eq("M1"),eq("ENTITY"),anyString(),anyString())).thenReturn(true);
        assertThatThrownBy(() -> authority.authorizePublication(actor,"M1","B1",List.of("S1"),"R1"))
                .isInstanceOf(BizException.class);
        clearInvocations(grants);
        when(grants.can(actor,"M1","STORE","S1","promotion.participate")).thenReturn(true);
        authority.authorizePublication(actor,"M1","B1",List.of("S1"),"R1");
        verify(grants).can(actor,"M1","ENTITY","HQ","promotion.sponsor.authorize");
        verify(grants).can(actor,"M1","ENTITY","E2","promotion.cost.authorize");
    }
    @Test void realOwnerRightsAreEntitySpecificAndForeignStoresCannotBeInjected() {
        setup();
        when(grants.ownerAccounts(actor)).thenReturn(List.of(Map.of("entity_no","HQ"),Map.of("entity_no","E2")));
        authority.authorizePublication(actor,"M1","B1",List.of("S1"),"R1");
        assertThatThrownBy(() -> authority.authorizePublication(actor,"M1","B1",List.of("FOREIGN"),"R1"))
                .isInstanceOf(BizException.class);
        assertThat(authority.canEntity(actor,"M1","E3","promotion.cost.read")).isFalse();
        assertThat(authority.canEntity(actor,"M1","E2","sv.settlement.read")).isFalse();
        when(facts.find("MAIN","M1","B1","HQ")).thenReturn(new BrandPromotionScopePort.Brand("M1","B1","HQ",2,3,
                List.of(new BrandPromotionScopePort.Store("S1","E2",1,"BD1",1,"TW"))));
        assertThatThrownBy(() -> authority.authorizePublication(actor,"M1","B1",List.of("S1"),"R1"))
                .isInstanceOf(BizException.class);
    }
    @Test void financialReadUsesExactEntityPermissionWithoutMarketingManage() {
        setup();
        when(grants.can(actor,"M1","ENTITY","E2","promotion.cost.read")).thenReturn(true);
        assertThat(authority.financial(actor,"M1","B1","E2","promotion.cost.read").entityNo()).isEqualTo("HQ");
        assertThatThrownBy(() -> authority.financial(actor,"M1","B1","E3","promotion.cost.read"))
                .isInstanceOf(BizException.class);
        verify(grants,never()).require(actor,"M1","BRAND","B1","promotion.activity.manage");
    }
}
