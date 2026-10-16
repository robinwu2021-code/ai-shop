package ai.neargo.shop.scenario;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.trade.service.OrderService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recipient_track_view;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RecipientTrackViewTest {
    @Autowired private OrderService orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @MockitoBean private UserQueryPort users;

    @BeforeEach
    void seedRecipientParcel() {
        jdbc.update("delete from ord_sub_order where sub_order_no='SUB-TRACK-VIEW'");
        jdbc.update("insert into ord_sub_order(sub_order_no,order_no,user_no,entity_no,fulfillment,status,"
                + "receiver_phone,buyer_nickname,created_at,updated_at) values "
                + "('SUB-TRACK-VIEW','O-TRACK-VIEW','U-PLACER','M-TRACK-VIEW','EXPRESS','SHIPPED',"
                + "'13800138000','下单人小王',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var recipient = new LoginUser(Realm.CONSUMER, SubjectKind.USR, "U-RECIPIENT", "收件人",
                List.of(), List.of(), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(recipient, null, List.of()));
        when(users.phoneOf("U-RECIPIENT")).thenReturn(Optional.of("13800138000"));
        when(users.find("U-PLACER")).thenReturn(Optional.of(
                new UserQueryPort.UserBrief("U-PLACER", "当前昵称", "8000", "")));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from ord_sub_order where sub_order_no='SUB-TRACK-VIEW'");
    }

    @Test
    void verifiedRecipientSeesOrderPlacerSnapshot() {
        var result = json.valueToTree(orders.trackForRecipient("SUB-TRACK-VIEW", "MP"));
        assertThat(result.path("buyerNickname").asText("")).isEqualTo("下单人小王");
    }

    @Test
    void oldOrdersWithoutSnapshotUseTheOrderPlacersProfile() {
        jdbc.update("update ord_sub_order set buyer_nickname=null where sub_order_no='SUB-TRACK-VIEW'");
        var result = json.valueToTree(orders.trackForRecipient("SUB-TRACK-VIEW", "MP"));
        assertThat(result.path("buyerNickname").asText("")).isEqualTo("当前昵称");
    }

    @Test
    void anonymousViewDoesNotDiscloseOrderPlacer() {
        SecurityContextHolder.clearContext();
        var result = json.valueToTree(orders.trackBySubOrder("SUB-TRACK-VIEW", "H5"));
        assertThat(result.path("buyerNickname").asText("")).isEmpty();
    }

    @Test
    void anotherPhoneCannotSeeTheAuthenticatedView() {
        when(users.phoneOf("U-RECIPIENT")).thenReturn(Optional.of("13900139000"));
        assertThatThrownBy(() -> orders.trackForRecipient("SUB-TRACK-VIEW", "MP"))
                .isInstanceOf(BizException.class);
    }
}
