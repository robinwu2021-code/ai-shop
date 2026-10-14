package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.spi.trade.SettleSourcePort.SettleSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

final class SettlementBasisFixtures {
    private final JdbcTemplate jdbc;
    private final SettleService service;

    SettlementBasisFixtures(JdbcTemplate jdbc, SettleService service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    void requireBasis() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables"
                + " where lower(table_name)='stl_bill_refund_basis'", Long.class)).isEqualTo(1L);
    }

    String payment() {
        String no = "BASIS-" + UUID.randomUUID().toString().substring(0, 18);
        payment(no);
        return no;
    }

    void payment(String no) {
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,user_no,pay_channel,pay_scene,amount_minor,"
                + "currency,status,wx_appid,tenant_no,created_at,updated_at,version,deleted)"
                + " values(?,'PAY',?,'BASIS-BUYER','WECHAT','MP_WECHAT',350,'CNY','SUCCESS','ORIGINAL-APP','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)", "PAY-" + no, no);
    }

    int generate(String no, String account) {
        return DataScopeContext.executeWithoutScope(() -> service.generateForOrder(no, List.of(input(no, account))));
    }

    SettleService.SettleInput input(String no, String account) {
        SettleSource src = new SettleSource(no, "M0001", "PLATFORM", 350, 50, 0, null, 1,
                "ST-M0001", 20, 7, "WECHAT", "MP_WECHAT", 50, 0, "MERCHANT_SELF");
        return new SettleService.SettleInput(src, "THIRD_PARTY", account, "ENTERPRISE",
                "DIRECT", "MERCHANT", "CN");
    }

    Map<String, Object> basis(String no) {
        return jdbc.queryForMap("select * from stl_bill_refund_basis where sub_order_no=?", no);
    }

    Map<String, Object> bill(String no) {
        return jdbc.queryForMap("select * from stl_bill where sub_order_no=?", no);
    }

    long count(String table, String no) {
        return jdbc.queryForObject("select count(*) from " + table + " where sub_order_no=?", Long.class, no);
    }
}
