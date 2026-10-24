package ai.neargo.shop.marketing;

import ai.neargo.shop.marketing.attribution.*;
import ai.neargo.shop.marketing.attribution.entity.MktFissionCampaign;
import ai.neargo.shop.marketing.attribution.impl.FissionServiceImpl;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.*;
import ai.neargo.shop.marketing.campaign.CampaignService;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.campaign.impl.CampaignServiceImpl;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper;
import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.numbering.CoreMarketingDocumentNumbers;
import ai.neargo.shop.spi.entitlement.LegacyCouponWriteFencePort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyCampaignNumberingTest {
    private final DocumentNumberCoordinator coordinator = mock(DocumentNumberCoordinator.class);
    private final Map<DocumentSource,String> committed = new HashMap<>();
    @SuppressWarnings("unchecked") private CoreMarketingDocumentNumbers numbers() {
        when(coordinator.transaction(anyMap(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(coordinator.bind(any(), any())).thenAnswer(call -> {
            DocumentType type=call.getArgument(0); DocumentSource source=call.getArgument(1);
            return committed.computeIfAbsent(source, ignored -> type.prefix()+"20261020"+String.format("%06d", committed.size()+1));
        });
        when(coordinator.findAll(any(), anyCollection())).thenAnswer(call -> {
            Map<DocumentSource,String> result=new HashMap<>();
            for(DocumentSource source : (Collection<DocumentSource>)call.getArgument(1))
                if(committed.containsKey(source))result.put(source,committed.get(source));
            return result;
        });
        return new CoreMarketingDocumentNumbers(coordinator);
    }
    @Test void legacyCouponCampaignCreatesSeparateActivityAndTemplateNumbersAndEditingKeepsBoth() {
        var campaigns=mock(CampaignMapper.class);var coupons=mock(CouponMapper.class);
        when(campaigns.insert(any(MktCampaign.class))).thenReturn(1);when(coupons.insert(any(MktCoupon.class))).thenReturn(1);
        var service=new CampaignServiceImpl(campaigns,coupons,mock(GoodsQueryPort.class),new ObjectMapper(),mock(LegacyCouponWriteFencePort.class));
        service.setDocumentNumbers(numbers());
        var created=service.save("M", command(null,"First"));
        var row=ArgumentCaptor.forClass(MktCampaign.class);verify(campaigns).insert(row.capture());
        var template=ArgumentCaptor.forClass(MktCoupon.class);verify(coupons).insert(template.capture());
        assertTrue(created.documentNo().startsWith("PT"));
        assertEquals("CU@"+created.campaignNo(),template.getValue().getCouponNo());
        assertTrue(committed.containsKey(new DocumentSource("MAIN","CORE_LEGACY","MKT_CAMPAIGN",created.campaignNo())));
        assertTrue(committed.containsKey(new DocumentSource("MAIN","MKT_LEGACY","mkt_coupon",template.getValue().getCouponNo())));
        when(campaigns.selectOne(any())).thenReturn(row.getValue());when(coupons.selectOne(any())).thenReturn(template.getValue());
        var edited=service.save("M",command(created.campaignNo(),"Edited"));
        assertEquals(created.campaignNo(),edited.campaignNo());assertEquals(created.documentNo(),edited.documentNo());
        assertEquals(2,committed.size());verify(coordinator,times(2)).bind(any(),any());
    }
    @Test void invitationCampaignHasItsOwnSourceNamespaceWithoutNumberingInvitationRows() {
        var campaigns=mock(FissionCampaignMapper.class);var coupons=mock(CouponMapper.class);var invites=mock(FissionInviteMapper.class);
        var coupon=new MktCoupon();coupon.setCouponNo("CO");coupon.setTenantNo("MAIN");
        when(coupons.selectOne(any())).thenReturn(coupon);when(campaigns.insert(any(MktFissionCampaign.class))).thenReturn(1);when(invites.selectCount(any())).thenReturn(0L);
        var service=new FissionServiceImpl(mock(LegacyCouponWriteFencePort.class),campaigns,coupons,invites);service.setDocumentNumbers(numbers());
        var created=service.save(new FissionService.SaveCommand(null,"Invite","CO",1,1),"OP");
        assertTrue(created.documentNo().startsWith("PT"));
        assertTrue(committed.containsKey(new DocumentSource("MAIN","CORE_LEGACY","MKT_FISSION_CAMPAIGN",created.fissionNo())));
        verify(invites,never()).insert(any(ai.neargo.shop.marketing.attribution.entity.MktFissionInvite.class));
    }
    private CampaignService.SaveCommand command(String id,String name) {
        return new CampaignService.SaveCommand(id,"COUPON",name,1,100000,0L,100L,null,null,null,List.of(),10,null);
    }
}
