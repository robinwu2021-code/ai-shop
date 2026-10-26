package ai.neargo.shop.commerce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.commerce.api.biz.BizCatalogPreviewController;
import ai.neargo.shop.commerce.api.biz.BizCatalogScopeController;
import ai.neargo.shop.commerce.api.biz.BizSyncPolicyController;
import ai.neargo.shop.commerce.api.biz.BizSyncRunController;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveCatalogSourcesReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveSyncPolicyReq;
import ai.neargo.shop.commerce.job.CommerceRecoveryJob;
import ai.neargo.shop.commerce.service.CatalogPublicationService;
import ai.neargo.shop.commerce.service.CatalogScopeService;
import ai.neargo.shop.commerce.service.CommerceRecoveryService;
import ai.neargo.shop.commerce.service.ResourceMappingVerificationService;
import ai.neargo.shop.commerce.service.SyncPolicyService;
import ai.neargo.shop.commerce.service.SyncRunService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

class CatalogWebContractTest {
    @AfterEach
    void clear() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void twelveRoutesUseTypedServicesAndPublicBusinessNumbers() throws Exception {
        var scopes = mock(CatalogScopeService.class);
        var previews = mock(CatalogPublicationService.class);
        var policies = mock(SyncPolicyService.class);
        var runs = mock(SyncRunService.class);
        var boundary = mock(CommerceRequestScope.class);
        when(boundary.store(eq("S"), any(HttpServletRequest.class))).thenReturn("E");
        BizContext.set(
                new BizContext(
                        "E", Set.of(), Set.of(), Set.of("S"), "S", true, Map.of(), Map.of()));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new LoginUser(
                                        null, null, "A", "Actor", List.of(), List.of(), "MAIN",
                                        null),
                                null));
        when(scopes.listScopes("E", "S", 1, 20, null, null))
                .thenReturn(PageData.of(List.of(), 0, 1, 20));
        when(runs.listRuns("E", "S", 1, 20, null, null))
                .thenReturn(PageData.of(List.of(), 0, 1, 20));
        when(runs.listItems("E", "S", "RUN", 1, 20)).thenReturn(PageData.of(List.of(), 0, 1, 20));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new BizCatalogScopeController(scopes, boundary),
                                new BizCatalogPreviewController(previews, boundary),
                                new BizSyncPolicyController(policies, boundary),
                                new BizSyncRunController(runs, boundary))
                        .build();
        mvc.perform(get("/biz/x/commerce/stores/S/catalog-scopes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/biz/x/commerce/catalog-scopes/SC")).andExpect(status().isOk());
        mvc.perform(
                        post("/biz/x/commerce/stores/S/catalog-scopes")
                                .header("Idempotency-Key", "C")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"connectionNo\":\"CON\",\"remoteScopeKey\":\"STORE\",\"name\":\"Menu\",\"sourceRefs\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(
                        put("/biz/x/commerce/catalog-scopes/SC")
                                .header("Idempotency-Key", "S")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":0,\"sourceRefs\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/biz/x/commerce/catalog-scopes/SC/sync-policy"))
                .andExpect(status().isOk());
        mvc.perform(
                        put("/biz/x/commerce/catalog-scopes/SC/sync-policy")
                                .header("Idempotency-Key", "P")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"version\":0,\"enabled\":false,\"onPublish\":true,\"onAvailability\":false,\"scheduledCheck\":true,\"debounceSeconds\":30,\"recoveryProfile\":\"QUERY_ONLY\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/biz/x/commerce/catalog-scopes/SC:preview")
                                .header("Idempotency-Key", "V")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedScopeVersion\":0}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/biz/x/commerce/stores/S/sync-runs")
                                .header("Idempotency-Key", "R")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"scopeNo\":\"SC\",\"snapshotNo\":\"SN\",\"hash\":\"HASH\",\"expectedScopeVersion\":0,\"reason\":\"publish\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/biz/x/commerce/stores/S/sync-runs")).andExpect(status().isOk());
        mvc.perform(get("/biz/x/commerce/sync-runs/RUN")).andExpect(status().isOk());
        mvc.perform(get("/biz/x/commerce/sync-runs/RUN/items")).andExpect(status().isOk());
        mvc.perform(
                        post("/biz/x/commerce/sync-runs/RUN:cancel-queued")
                                .header("Idempotency-Key", "X")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":0,\"reason\":\"cancel\"}"))
                .andExpect(status().isOk());
        verify(scopes).createScope(eq("E"), eq("S"), eq("A"), any(), eq("C"));
        verify(scopes).getScope("E", "S", "SC");
        verify(scopes).saveSources(eq("E"), eq("S"), eq("SC"), eq("A"), any(), eq("S"));
        verify(policies).getPolicy("E", "S", "SC");
        verify(policies).savePolicy(eq("E"), eq("S"), eq("SC"), eq("A"), any(), eq("P"));
        verify(previews).preview(eq("E"), eq("S"), eq("SC"), eq("A"), any(), eq("V"));
        verify(runs).submit(eq("E"), eq("S"), eq("A"), any(), eq("R"));
        verify(runs).getRun("E", "S", "RUN");
        verify(runs).cancelQueued(eq("E"), eq("S"), eq("RUN"), eq("A"), any(), eq("X"));
    }

    @Test
    void catalogBodiesRejectForgedFactsAndCoercion() {
        var json = new ObjectMapper();
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"expectedScopeVersion\":0,\"price\":100}",
                                        PreviewCatalogReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"version\":0,\"enabled\":\"true\"}",
                                        SaveSyncPolicyReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"version\":0,\"debounceSeconds\":1.5}",
                                        SaveSyncPolicyReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"sourceRefs\":[{\"sourceOwner\":\"FOOD\",\"sourceRef\":\"M\",\"selectionRevision\":\"R\",\"priority\":0,\"stock\":5}]}",
                                        SaveCatalogSourcesReq.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void storeAndQueryGuardsRunBeforeCatalogServices() {
        var service = mock(CatalogScopeService.class);
        var stores = mock(StoreDirectoryPort.class);
        var controller = new BizCatalogScopeController(service, new CommerceRequestScope(stores));
        BizContext.set(
                new BizContext(
                        "E", Set.of(), Set.of(), Set.of("S"), "S", true, Map.of(), Map.of()));
        var req = new MockHttpServletRequest();
        req.addHeader("X-Store-No", "OTHER");
        assertThatThrownBy(() -> controller.getScope("SC", req)).isInstanceOf(BizException.class);
        var unknown = new MockHttpServletRequest();
        unknown.addParameter("entityNo", "OTHER");
        assertThatThrownBy(() -> controller.getScope("SC", unknown))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(service, stores);
    }

    @Test
    void recoveryUsesOneDisabledPlatformJob() {
        var service = mock(CommerceRecoveryService.class);
        var job = new CommerceRecoveryJob(service, mock(ResourceMappingVerificationService.class));
        assertThat(job.commerceRecoveryDeclaration().enabled()).isFalse();
        assertThat(job.name()).isEqualTo("commerce-recovery");
        verifyNoInteractions(service);
    }
}
