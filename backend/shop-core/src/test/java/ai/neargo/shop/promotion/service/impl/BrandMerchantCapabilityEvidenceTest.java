package ai.neargo.shop.promotion.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BrandMerchantCapabilityEvidenceTest {
    @Test void crossEntityBrandRequiresEachRealEntityAdmissionAndStoreOwnership() {
        var merchants=mock(MerchantScopeFactsPort.class);
        var brands=mock(BrandPromotionScopePort.class);
        var evidence=new MerchantActivityCapabilityEvidence(merchants);
        ReflectionTestUtils.setField(evidence,"brands",brands);
        var actor=new ActivityActor("MAIN","HQ","A1",Set.of("S1","S2"),"M1","B1");
        when(brands.find("MAIN","M1","B1","HQ")).thenReturn(new BrandPromotionScopePort.Brand("M1","B1","HQ",0,0,
                List.of(new BrandPromotionScopePort.Store("S1","E1",0,"BD1",0,"CN"),
                        new BrandPromotionScopePort.Store("S2","E2",0,"BD2",0,"CN"))));
        when(merchants.entityActive(eq("MAIN"),anyString())).thenReturn(true);
        when(merchants.promotionIndustryAdmitted(eq("MAIN"),anyString())).thenReturn(true);
        when(merchants.storesOwned("MAIN","E1",Set.of("S1"))).thenReturn(true);
        assertThat(evidence.verifiedFor(actor)).doesNotContain(ActivityCapability.STORE_PERMISSION);
        when(merchants.storesOwned("MAIN","E2",Set.of("S2"))).thenReturn(true);
        assertThat(evidence.verifiedFor(actor)).contains(ActivityCapability.STORE_PERMISSION,ActivityCapability.INDUSTRY_ADMISSION);
        when(merchants.promotionIndustryAdmitted("MAIN","E2")).thenReturn(false);
        assertThat(evidence.verifiedFor(actor)).doesNotContain(ActivityCapability.INDUSTRY_ADMISSION);
        var spoofed=new ActivityActor("MAIN","HQ","A1",Set.of("S1","S3"),"M1","B1");
        assertThat(evidence.verifiedFor(spoofed)).isEmpty();
    }
}
