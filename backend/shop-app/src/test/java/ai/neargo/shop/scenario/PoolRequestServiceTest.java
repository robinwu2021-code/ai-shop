package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.pool.PoolRequestService;
import ai.neargo.shop.pay.points.type.PointTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PoolRequestServiceTest {
    @Autowired private ApplicationContext context;

    @Test
    void approval_and_evidence_are_unique() {
        String type = type();
        PoolRequestService requests = context.getBean(PoolRequestService.class);
        String receipt = "BANK:" + type;
        jdbc().update("INSERT INTO pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                + " VALUES(?,'CNY',100,1)", receipt);
        requests.submit(new PoolRequest("REQ1:" + type, type, PoolRequest.Action.TOPUP_U, 100, "maker", receipt));
        assertThatThrownBy(() -> requests.approve("REQ1:" + type, "maker"))
                .isInstanceOf(IllegalArgumentException.class);
        requests.approve("REQ1:" + type, "checker");
        requests.approve("REQ1:" + type, "checker");
        assertThat(jdbc().queryForObject("SELECT u_minor FROM pts_fund_pool WHERE type_code=?", Long.class, type))
                .isEqualTo(100L);
        requests.submit(new PoolRequest("REQ2:" + type, type, PoolRequest.Action.TOPUP_U, 100, "maker", receipt));
        assertThatThrownBy(() -> requests.approve("REQ2:" + type, "checker"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc().queryForObject("SELECT u_minor FROM pts_fund_pool WHERE type_code=?", Long.class, type))
                .isEqualTo(100L);
    }

    @Test
    void unverified_money_never_creates_points() {
        String type = type();
        PoolRequestService requests = context.getBean(PoolRequestService.class);
        requests.submit(new PoolRequest("REQ:" + type, type, PoolRequest.Action.TOPUP_U, 100, "maker", "UNVERIFIED"));
        assertThatThrownBy(() -> requests.approve("REQ:" + type, "checker"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc().queryForObject("SELECT u_minor FROM pts_fund_pool WHERE type_code=?", Long.class, type))
                .isZero();
    }

    @Test
    void withdrawals_require_the_original_bank_target() {
        String type=type();
        PoolRequestService requests=context.getBean(PoolRequestService.class);
        assertThatThrownBy(() -> requests.submit(new PoolRequest("W:"+type,type,PoolRequest.Action.WITHDRAW_B,
                100,"maker",null))).hasMessage("POINTS_WITHDRAW_TARGET_REQUIRED");
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_pool_request WHERE request_no=?",Long.class,
                "W:"+type)).isZero();
    }

    private String type() {
        assertThat(context.getBeanNamesForType(PointTypeService.class)).as("actual type service required").isNotEmpty();
        String no = "OP" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        context.getBean(PointTypeService.class).create(
                new PointTypeService.CreateType(no, "CN", new RedeemRate(1, 1), 3000, 365), "maker");
        return no;
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(context.getBean("payDataSource", DataSource.class)); }
}
