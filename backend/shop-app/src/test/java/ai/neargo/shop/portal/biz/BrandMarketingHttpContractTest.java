package ai.neargo.shop.portal.biz;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.promotion.BrandMarketingApplicationService;
import ai.neargo.shop.promotion.BrandMarketingCommandInputs.PublishActivity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import static org.mockito.Mockito.*;

class BrandMarketingHttpContractTest {
    @Test void commandUsesAuthenticatedAccountAndExactBrandAndOriginalKey() {
        var access=mock(ManagementAccess.class);
        var service=mock(BrandMarketingApplicationService.class);
        var actor=new Actor("MAIN","USER:U1");
        when(access.actor()).thenReturn(actor);
        var payload=new PublishActivity("A1",0L,"R1");
        when(service.execute(actor,"M1","B1","publishActivity",payload,"7","original"))
                .thenReturn(Map.of("success",true));
        var controller=new BizBrandMarketingController(access,service);
        assertThat(controller.execute("B1","M1",new BizBrandMarketingController.Command(
                "publishActivity","7",payload),"original")).containsEntry("success",true);
        verify(service).execute(actor,"M1","B1","publishActivity",payload,"7","original");
    }
    @Test void originalReceiptGetHasNoMutationAndWriteRequiresOriginalHeader() throws Exception {
        var access=mock(ManagementAccess.class);
        var service=mock(BrandMarketingApplicationService.class);
        var actor=new Actor("MAIN","MCH:A1"); when(access.actor()).thenReturn(actor);
        var controller=new BizBrandMarketingController(access,service);
        controller.result("B1","M1","original");
        verify(service).result(actor,"M1","B1","original");
        verifyNoMoreInteractions(service);
        var method=BizBrandMarketingController.class.getMethod("execute",String.class,String.class,
                BizBrandMarketingController.Command.class,String.class);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly("/commands");
        assertThat(method.getParameters()[3].getAnnotation(RequestHeader.class).value()).isEqualTo("Idempotency-Key");
        assertThat(BizBrandMarketingController.class.getMethod("result",String.class,String.class,String.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/commands");
        assertThat(List.of(BizBrandMarketingController.Command.class.getRecordComponents()).stream()
                .map(java.lang.reflect.RecordComponent::getName)).containsExactly("action","expectedRevision","payload");
    }
}
