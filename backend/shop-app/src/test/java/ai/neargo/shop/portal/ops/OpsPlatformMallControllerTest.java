package ai.neargo.shop.portal.ops;

import ai.neargo.commerce.api.mall.reporting.PurchaseOrderQueryPort;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import ai.neargo.shop.commercebridge.PlatformMallAccess;
import ai.neargo.shop.commercebridge.PlatformMallReporting;
import ai.neargo.shop.commercebridge.PlatformMallReporting.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService;
import ai.neargo.shop.pay.service.PlatformCashReporting;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC routing must select its capability and queue semantics without inferring servletPath. */
class OpsPlatformMallControllerTest {
    private final PlatformMallAccess access=mock(PlatformMallAccess.class);
    private final PlatformMallReporting reports=mock(PlatformMallReporting.class);
    private final PlatformMallAccess.Context context=new PlatformMallAccess.Context(new Access("MAIN",true,Set.of()),"OPS1",Set.of(Model.ENTITY,Model.STORE));
    private MockMvc mvc;
    @BeforeEach void setup() {
        when(access.require(anyString(),nullable(String.class),anyBoolean())).thenReturn(context);
        when(reports.orders(eq(context),any(),nullable(String.class),anyInt())).thenReturn(new OrderPage(List.of(),null,context.models(),1));
        when(reports.cash(eq(context),anyLong(),anyLong(),nullable(String.class),nullable(String.class),nullable(String.class),nullable(String.class),anyInt()))
                .thenReturn(new CashPage(List.of(),List.of(),null,1,"CONSISTENT_PER_REQUEST"));
        mvc=MockMvcBuilders.standaloneSetup(new OpsPlatformMallController(access,reports)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void claimsRouteAlwaysUsesQueuePermissionAndPendingFilter() throws Exception {
        mvc.perform(get("/ops/platform-mall/claims").param("model","ENTITY").param("entityNo","E1").param("storeNo","S1")
                .param("orderNo","O1").param("status","SUBMITTED").param("currency","TWD")
                .param("createdFrom","100").param("createdTo","900").param("after","cursor").param("limit","7"))
                .andExpect(status().isOk());
        verify(access).require("commerce:platform:claims:read","ENTITY",true);
        verify(reports).orders(eq(context),eq(new Filter("E1","S1","O1","SUBMITTED","TWD",100L,900L,true)),eq("cursor"),eq(7));
        verifyNoMoreInteractions(access,reports);
    }
    @Test void ordersRouteUsesReadPermissionWithoutPendingRestriction() throws Exception {
        mvc.perform(get("/ops/platform-mall/orders")).andExpect(status().isOk());
        verify(access).require("commerce:platform:orders:read",null,false);
        verify(reports).orders(context,new Filter(null,null,null,null,null,null,null,false),null,20);
        verifyNoMoreInteractions(access,reports);
    }
    @Test void cashRouteForwardsExactOccurrenceRangeAndSourceFilters() throws Exception {
        mvc.perform(get("/ops/platform-mall/cash").param("fromInclusive","1000").param("toExclusive","9000")
                .param("currency","USD").param("model","STORE").param("entityNo","E1").param("storeNo","S1")
                .param("after","cursor").param("limit","3")).andExpect(status().isOk());
        verify(access).require("commerce:platform:cash:read","STORE",false);
        verify(reports).cash(context,1000,9000,"USD","E1","S1","cursor",3);
        verifyNoMoreInteractions(access,reports);
    }
    @Test void missingOrMalformedCashRangeIsRejectedBeforeDomainAccess() throws Exception {
        mvc.perform(get("/ops/platform-mall/cash").param("fromInclusive","0")).andExpect(jsonPath("$.code").value(10400));
        mvc.perform(get("/ops/platform-mall/cash").param("fromInclusive","not-a-number").param("toExclusive","10"))
                .andExpect(jsonPath("$.code").value(10400));
        verifyNoInteractions(access,reports);
    }
    @Test void invalidOccurrenceWindowNeverResolvesOrScansTheCashProvider() throws Exception {
        @SuppressWarnings("unchecked") var provider=(ObjectProvider<PlatformCashReporting>)mock(ObjectProvider.class);
        var real=new PlatformMallReporting(mock(PurchaseOrderQueryPort.class),mock(EntityPlanPurchaseService.class),provider);
        var actual=MockMvcBuilders.standaloneSetup(new OpsPlatformMallController(access,real)).setControllerAdvice(new GlobalExceptionHandler()).build();
        for(long[] range:List.of(new long[]{-1,10},new long[]{10,10},new long[]{20,10},new long[]{0,367L*86400000})) {
            actual.perform(get("/ops/platform-mall/cash").param("fromInclusive",Long.toString(range[0])).param("toExclusive",Long.toString(range[1])))
                    .andExpect(jsonPath("$.code").value(10400));
        }
        verifyNoInteractions(provider);
    }
    @Test void deniedCashCapabilityNeverInvokesReporting() throws Exception {
        when(access.require("commerce:platform:cash:read",null,false)).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        mvc.perform(get("/ops/platform-mall/cash").param("fromInclusive","0").param("toExclusive","1000"))
                .andExpect(jsonPath("$.code").value(10403));
        verifyNoInteractions(reports);
    }
    @Test void allRoutesDeclareTheirOwnMethodSecurityCapability() {
        var expected=Map.of("orders","commerce:platform:orders:read","claims","commerce:platform:claims:read","cash","commerce:platform:cash:read");
        for(var method:OpsPlatformMallController.class.getDeclaredMethods())if(expected.containsKey(method.getName()))
            assertEquals("@perm.can('"+expected.get(method.getName())+"')",method.getAnnotation(PreAuthorize.class).value());
    }
}
