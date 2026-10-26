package ai.neargo.shop.commerce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.commerce.api.biz.BizChannelAccountController;
import ai.neargo.shop.commerce.config.CommerceWebAutoConfiguration;
import ai.neargo.shop.commerce.dto.ChannelAccountVO;
import ai.neargo.shop.commerce.dto.CreateChannelAccountReq;
import ai.neargo.shop.commerce.dto.CreateChannelConnectionReq;
import ai.neargo.shop.commerce.service.ChannelAccountService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

class CommerceWebContractTest {
    @AfterEach
    void clear() {
        BizContext.clear();
    }

    @Test
    void requestCannotClaimEntityCredentialsOrNumericIndustry() {
        var json = new ObjectMapper();
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"profileNo\":\"P\",\"externalAccountRef\":\"A\","
                                                + "\"entityNo\":\"VICTIM\"}",
                                        CreateChannelAccountReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"profileNo\":1,\"externalAccountRef\":\"A\"}",
                                        CreateChannelAccountReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"accountNo\":\"A\",\"externalStoreRef\":\"S\","
                                            + "\"sourceOwner\":0,\"fulfillmentProfile\":\"INSTANT\"}",
                                        CreateChannelConnectionReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"profileNo\":\"P\",\"externalAccountRef\":\"A\","
                                                + "\"credentialRef\":\"secret\"}",
                                        CreateChannelAccountReq.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void ownerAndCurrentStoreAreBothMandatoryBeforeDirectoryLookup() {
        var stores = mock(StoreDirectoryPort.class);
        var scope = new CommerceRequestScope(stores);
        BizContext.set(
                new BizContext(
                        "E", Set.of(), Set.of(), Set.of("S"), "S", false, Map.of(), Map.of()));
        assertThatThrownBy(scope::owner).isInstanceOf(BizException.class);
        BizContext.set(
                new BizContext(
                        "E", Set.of(), Set.of(), Set.of("S"), "S", true, Map.of(), Map.of()));
        assertThat(scope.owner()).isEqualTo("E");
        assertThatThrownBy(() -> scope.store("OTHER", new MockHttpServletRequest()))
                .isInstanceOf(BizException.class);
        var request = new MockHttpServletRequest();
        request.addHeader("X-Store-No", "OTHER");
        assertThatThrownBy(() -> scope.store("S", request)).isInstanceOf(BizException.class);
        verifyNoInteractions(stores);
    }

    @Test
    void unknownAndDuplicateQueryParametersCannotSilentlyWidenScope() {
        var request = new MockHttpServletRequest();
        request.addParameter("entityNo", "OTHER");
        assertThatThrownBy(() -> CommerceRequestScope.query(request, Set.of("page", "size")))
                .isInstanceOf(BizException.class);
        request.removeParameter("entityNo");
        request.addParameter("page", "1", "2");
        assertThatThrownBy(() -> CommerceRequestScope.query(request, Set.of("page", "size")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void disabledOrStandaloneShapeDoesNotRequireLocalService() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommerceWebAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=api")
                .run(
                        c ->
                                assertThat(c)
                                        .hasNotFailed()
                                        .doesNotHaveBean(CommerceRequestScope.class)
                                        .doesNotHaveBean(ChannelAccountService.class));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommerceWebAutoConfiguration.class))
                .withPropertyValues(
                        "spring.profiles.active=api",
                        "shop.commerce.enabled=true",
                        "shop.commerce.deployment=standalone")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(CommerceRequestScope.class));
    }

    @Test
    void actualHttpRoutesReturnOnlyPublicFieldsAndRejectUnknownBodyFields() throws Exception {
        var service = mock(ChannelAccountService.class);
        var stores = mock(StoreDirectoryPort.class);
        BizContext.set(
                new BizContext(
                        "E", Set.of(), Set.of(), Set.of("S"), "S", true, Map.of(), Map.of()));
        when(service.listAccounts("E", 1, 20))
                .thenReturn(
                        PageData.of(
                                List.of(
                                        new ChannelAccountVO(
                                                "ACC", "PROFILE", "REMOTE", "DRAFT", 0)),
                                1,
                                1,
                                20));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new BizChannelAccountController(
                                        service, new CommerceRequestScope(stores)))
                        .build();
        mvc.perform(get("/biz/x/commerce/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].accountNo").value("ACC"))
                .andExpect(jsonPath("$.records[0].id").doesNotExist())
                .andExpect(jsonPath("$.records[0].credentialRef").doesNotExist());
        mvc.perform(
                        post("/biz/x/commerce/accounts")
                                .header("Idempotency-Key", "KEY")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"profileNo\":\"P\","
                                            + "\"externalAccountRef\":\"A\",\"entityNo\":\"OTHER\"}"))
                .andExpect(status().isBadRequest());
    }
}
