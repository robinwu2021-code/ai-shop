package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.payclient.BuyerPointsAppService;
import ai.neargo.shop.support.TestLogin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:buyer-points-read;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class MpBuyerPointsReadTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @MockitoBean BuyerPointsAppService buyer;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(
                        org.springframework.security.test.web.servlet.setup
                                .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void anonymous_cannot_read_buyer_ledger() throws Exception {
        assertThat(mvc().perform(get("/mp/points/accounts")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(
                        mvc().perform(get("/mp/points/entries").param("typeCode", "CN"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        verifyNoInteractions(buyer);
    }

    @Test
    void account_and_entries_are_scoped_to_authenticated_buyer() throws Exception {
        when(buyer.accounts(anyString())).thenReturn(List.of());
        when(buyer.records(anyString(), anyString(), eq(0L), eq(20)))
                .thenReturn(new BuyerPointsAppService.EntryPage(List.of(), 0, false));
        String first = TestLogin.consumer(mvc(), json, otp, "13000349120");
        String second = TestLogin.consumer(mvc(), json, otp, "13000349121");
        var accountsA =
                mvc().perform(get("/mp/points/accounts").header("Authorization", "Bearer " + first))
                        .andReturn()
                        .getResponse();
        var accountsB =
                mvc().perform(
                                get("/mp/points/accounts")
                                        .header("Authorization", "Bearer " + second))
                        .andReturn()
                        .getResponse();
        assertThat(accountsA.getStatus()).isEqualTo(200);
        assertThat(accountsB.getStatus()).isEqualTo(200);
        var users = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(buyer, times(2)).accounts(users.capture());
        assertThat(users.getAllValues().get(0)).isNotEqualTo(users.getAllValues().get(1));
        var records =
                mvc().perform(
                                get("/mp/points/entries")
                                        .param("typeCode", "CN")
                                        .header("Authorization", "Bearer " + first))
                        .andReturn()
                        .getResponse();
        assertThat(records.getStatus()).isEqualTo(200);
        verify(buyer).records(users.getAllValues().get(0), "CN", 0, 20);
    }
}
