package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.InternalHttp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Credential and scope boundaries use controlled owner facts; actual native owners are tested separately. */
class MerchantFactsControllerTest {
    @Test
    void credentialsAreCheckedBeforeFactsAndCanonicalStoreScopeIsRequired() throws Exception {
        var calls = new AtomicInteger();
        ResourceFactsPort resources = query -> {
            calls.incrementAndGet();
            return List.of(new ResourceFactsPort.ResourceFact("R", ResourceFactsPort.Type.ROOM, "S", null,
                    "room", 2, false));
        };
        var mvc = MockMvcBuilders.standaloneSetup(controller(resources)).build();
        String body = "{\"tenantNo\":\"MAIN\",\"entityNo\":\"E\",\"storeNo\":\"S\","
                + "\"resourceNos\":[\"R\"],\"at\":\"2026-10-22T00:00:00Z\"}";
        String path = "/internal/merchant/facts/resources";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "wrong").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isUnauthorized());
        assertThat(calls.get()).isZero();
        var response = mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "facts-key")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).startsWith("[").contains("resourceNo", "R")
                .doesNotContain("accountNo", "contactRef", "openOccupationNo");
        assertThat(calls.get()).isOne();
        mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "facts-key").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"E\"", "\"e\""))).andExpect(status().isForbidden());
        mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "facts-key").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("MAIN", "OTHER"))).andExpect(status().isBadRequest());
        assertThat(calls.get()).isOne();
    }
    @Test
    void realExceptionAdviceKeepsAllOwnerAmbiguitiesAsHttpConflict() throws Exception {
        PersonnelFactsPort people = query -> { throw new IllegalStateException("ambiguous personnel employment"); };
        ResourceFactsPort resources = query -> { throw new IllegalStateException("ambiguous resource employment"); };
        ScheduleWindowsPort schedules = query -> { throw new IllegalStateException("cross-store schedule overlap"); };
        var mvc = MockMvcBuilders.standaloneSetup(controller(people, resources, schedules))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String scope = "{\"tenantNo\":\"MAIN\",\"entityNo\":\"E\",\"storeNo\":\"S\",";
        String at = "\"at\":\"2026-10-22T00:00:00Z\"}";
        for (String path : List.of("personnel", "resources", "schedules")) {
            String body = path.equals("personnel") ? scope + "\"personnelNos\":[\"P\"]," + at
                    : path.equals("resources") ? scope + "\"resourceNos\":[\"R\"]," + at
                    : scope + "\"resourceNos\":[\"R\"],\"startAt\":\"2026-10-22T00:00:00Z\","
                        + "\"endAt\":\"2026-10-23T00:00:00Z\"}";
            mvc.perform(post("/internal/merchant/facts/" + path).header(InternalHttp.TOKEN_HEADER, "facts-key")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        }
    }
    private Object controller(ResourceFactsPort resources) throws Exception {
        return controller(query -> List.of(), resources, query -> List.of());
    }
    private Object controller(PersonnelFactsPort people, ResourceFactsPort resources,
            ScheduleWindowsPort schedules) throws Exception {
        Class<?> type = assertDoesNotThrow(() ->
                Class.forName("ai.neargo.shop.portal.internal.InternalMerchantFactsController"));
        var stores = mock(StoreDirectoryPort.class);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard(
                "S", "store", "E", null, "ACTIVE", true, null, null, null, null, 0, 0)));
        return type.getConstructor(PersonnelFactsPort.class, ResourceFactsPort.class, ScheduleWindowsPort.class,
                StoreDirectoryPort.class, String.class).newInstance(people, resources, schedules, stores, "facts-key");
    }
}
