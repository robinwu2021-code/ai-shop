package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.storedvalue.migration.StoredValueWriterCapabilityService;
import ai.neargo.shop.portal.internal.InternalStoredValueWriterCapabilityEndpoint;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.svc.InternalHttp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class StoredValueWriterCapabilityEndpointTest {
    @Test void coreExposesOnlyAuthenticatedLocalProcessFacts() throws Exception {
        var service=mock(StoredValueWriterCapabilityService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new InternalStoredValueWriterCapabilityEndpoint(service,"test-token")).build();
        var query=new Query("core-nonce");
        var cap=new Capability("core-nonce","shop-app","boot-core","a".repeat(40),2,false,false,"b".repeat(64),true,Instant.parse("2026-10-23T08:00:00Z"));
        when(service.capability(query)).thenReturn(cap);
        var request=post(PayInternalPaths.SV_MIGRATION_CAPABILITY).contentType(MediaType.APPLICATION_JSON).content("{\"challenge\":\"core-nonce\"}");
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(401);verifyNoInteractions(service);
        var response=mvc.perform(post(PayInternalPaths.SV_MIGRATION_CAPABILITY).header(InternalHttp.TOKEN_HEADER,"test-token").contentType(MediaType.APPLICATION_JSON).content("{\"challenge\":\"core-nonce\"}")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var json=new tools.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertThat(json.path("application").asString()).isEqualTo("shop-app");assertThat(json.path("writerGuardEnabled").asBoolean()).isFalse();
        assertThat(json.path("challenge").asString()).isEqualTo("core-nonce");
    }
}
