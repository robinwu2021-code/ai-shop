package ai.neargo.shop.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.platform.impl.AdmissionManagementServiceImpl;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.spi.user.SelfOperatedAdmissionPort;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdmissionManagementServiceTest {
    private final OpsService applications = mock(OpsService.class);
    private final SelfOperatedAdmissionPort entities = mock(SelfOperatedAdmissionPort.class);
    private final MarketPhonePolicyService markets = mock(MarketPhonePolicyService.class);
    private final AdmissionManagementService service = new AdmissionManagementServiceImpl(applications,entities,markets);

    AdmissionManagementServiceTest() {
        when(markets.instancePolicy()).thenReturn(new MarketPhonePolicyService.Policy("TW","+886",true,false,false,true));
        when(applications.searchApplies(any(),any(),anyLong(),anyLong(),anyBoolean(),any(),anyBoolean()))
                .thenReturn(PageData.empty(1,20));
        when(entities.search(any(),any(),any(),anyLong())).thenReturn(PageData.empty(1,20));
    }
    private MerchantApplyVO application(String no,long at) {
        var a=mock(MerchantApplyVO.class);
        when(a.applyNo()).thenReturn(no);when(a.createdAt()).thenReturn(at);
        when(a.ownerUserNo()).thenReturn("OWNER");when(a.status()).thenReturn("APPROVED");
        return a;
    }

    @Test void mergesBeforePaginationWithAccurateCountsAndStableOrder() {
        var a3=application("A3",300);var a1=application("A1",100);
        when(applications.searchApplies(any(),isNull(),eq(1L),eq(4L),eq(false),eq("TW"),eq(true)))
                .thenReturn(PageData.of(List.of(a3,a1),7,1,4));
        when(entities.search("TW",null,null,4)).thenReturn(PageData.of(List.of(
                new SelfOperatedAdmissionPort.Entry("M4","Platform","OWNER","ACTIVE","CITY",400),
                new SelfOperatedAdmissionPort.Entry("M2","Platform","OWNER","ACTIVE","CITY",200)),9,1,4));
        var page=service.search(null,null,null,2,2,true,true,true);
        assertEquals(16,page.total());assertEquals(2,page.page());assertEquals(2,page.size());
        assertEquals(List.of("SELF:M2","APPLY:A1"),page.records().stream().map(AdmissionManagementService.Entry::key).toList());
        assertNotNull(page.records().get(1).application());
    }
    @Test void assistedOperatorCannotReadSelfOperatedOrUnassistedApplications() {
        service.search(null,"PENDING","Breakfast",1,20,false,true,false);
        verify(applications).searchApplies("PENDING","Breakfast",1,20,true,"TW",true);
        verifyNoInteractions(entities);
    }
    @Test void selfOperatedPermissionAloneNeverReadsApplications() {
        service.search(null,null,null,1,20,false,false,true);
        verifyNoInteractions(applications);verify(entities).search("TW",null,null,20);
    }
    @Test void requestedAssistedTypeRestrictsEvenAuditorToAssistedSources() {
        service.search("ON_BEHALF",null,null,1,20,true,true,true);
        verify(applications).searchApplies("PENDING,REVIEWING,APPROVED,REJECTED",null,1,20,true,"TW",true);
        verifyNoInteractions(entities);
    }
    @Test void selfOperatedFilterDoesNotReadApplicationsAndOutOfRangePageIsEmpty() {
        var page=service.search("SELF_OPERATED",null,null,10,20,true,true,true);
        verifyNoInteractions(applications);assertTrue(page.records().isEmpty());
        verify(entities).search("TW",null,null,200);
    }
    @Test void equalTimesHaveStableDistinctKeysAndNoPermissionReturnsNothing() {
        var a1=application("A1",100);var a2=application("A2",100);
        when(applications.searchApplies(any(),isNull(),eq(1L),eq(20L),eq(false),eq("TW"),eq(true)))
                .thenReturn(PageData.of(List.of(a1,a2),2,1,20));
        assertEquals(List.of("APPLY:A2","APPLY:A1"),service.search(null,null,null,1,20,true,false,false)
                .records().stream().map(AdmissionManagementService.Entry::key).toList());
        assertEquals(0,service.search(null,null,null,1,20,false,false,false).total());
    }
}
