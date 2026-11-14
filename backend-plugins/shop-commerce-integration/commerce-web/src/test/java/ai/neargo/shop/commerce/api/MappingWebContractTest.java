package ai.neargo.shop.commerce.api;

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
import ai.neargo.shop.commerce.api.biz.BizResourceMappingController;
import ai.neargo.shop.commerce.dto.MappingContracts.CreateResourceMappingReq;
import ai.neargo.shop.commerce.dto.MappingContracts.ReviseResourceMappingReq;
import ai.neargo.shop.commerce.service.ResourceMappingService;
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

class MappingWebContractTest {
    @AfterEach
    void clear() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void fourRoutesBindStoreAndActorWithoutAcceptingProofOrOwnerScope() throws Exception {
        var service = mock(ResourceMappingService.class);
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
        when(service.listMappings("E", "S", 1, 20, null, null, null, null))
                .thenReturn(PageData.of(List.of(), 0, 1, 20));
        when(service.listRevisions("E", "S", "M", 1, 20))
                .thenReturn(PageData.of(List.of(), 0, 1, 20));
        var mvc =
                MockMvcBuilders.standaloneSetup(new BizResourceMappingController(service, boundary))
                        .build();
        mvc.perform(get("/biz/x/commerce/stores/S/resource-mappings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/biz/x/commerce/resource-mappings/M/revisions"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/biz/x/commerce/resource-mappings")
                                .header("Idempotency-Key", "C")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"connectionNo\":\"CON\",\"objectType\":\"SKU\",\"externalIdentity\":{\"externalRef\":\"REMOTE\",\"parents\":[{\"objectType\":\"OFFER\",\"externalRef\":\"PARENT\"}]},\"sourceOwner\":\"FOOD\",\"sourceRef\":\"SKU\",\"sourceRevision\":\"R1\",\"salesOfferRef\":\"OFFER\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        put("/biz/x/commerce/resource-mappings/M")
                                .header("Idempotency-Key", "R")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"version\":0,\"sourceOwner\":\"FOOD\",\"sourceRef\":\"SKU\",\"sourceRevision\":\"R2\",\"salesOfferRef\":\"OFFER\",\"reason\":\"revise\"}"))
                .andExpect(status().isOk());
        verify(service)
                .createMapping(
                        eq("E"), eq("S"), eq("A"), any(CreateResourceMappingReq.class), eq("C"));
        verify(service)
                .reviseMapping(
                        eq("E"),
                        eq("S"),
                        eq("M"),
                        eq("A"),
                        any(ReviseResourceMappingReq.class),
                        eq("R"));
        mvc.perform(
                        post("/biz/x/commerce/resource-mappings")
                                .header("Idempotency-Key", "X")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"validationStatus\":\"VERIFIED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void strictMappingSchemaRejectsClientProofManagedOriginNumericTypeAndIdentityMutation() {
        var json = new ObjectMapper();
        for (String body :
                List.of(
                        "{\"mappingOrigin\":\"MANAGED\"}",
                        "{\"entityNo\":\"OTHER\"}",
                        "{\"objectType\":0}",
                        "{\"externalIdentity\":{\"externalRef\":1,\"parents\":[]}}",
                        "{\"externalIdentity\":{\"externalRef\":\"REMOTE\",\"parents\":[],\"proof\":true}}"))
            assertThatThrownBy(() -> json.readValue(body, CreateResourceMappingReq.class))
                    .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"version\":0,\"externalIdentity\":{}}",
                                        ReviseResourceMappingReq.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void headerAndUnknownFilterCannotWidenMappingScope() {
        var service = mock(ResourceMappingService.class);
        var stores = mock(StoreDirectoryPort.class);
        var controller =
                new BizResourceMappingController(service, new CommerceRequestScope(stores));
        BizContext.set(
                new BizContext(
                        "E", Set.of(), Set.of(), Set.of("S"), "S", true, Map.of(), Map.of()));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Store-No", "OTHER");
        assertThatThrownBy(() -> controller.listRevisions("M", 1, 20, request))
                .isInstanceOf(BizException.class);
        var unknown = new MockHttpServletRequest();
        unknown.addParameter("entityNo", "OTHER");
        assertThatThrownBy(
                        () -> controller.listMappings("S", 1, 20, null, null, null, null, unknown))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(service, stores);
    }
}
