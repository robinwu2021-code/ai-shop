package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit source fixtures test the actual settlement amount calculation, without any money gateway execution. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:points_settlement_basis;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PerOrderSettlementTest {
    @Autowired private SettleService settlement;
    @Autowired private BillMapper bills;
    @Autowired @Qualifier("payDataSource") private DataSource payDataSource;

    @Test
    void merchant_is_not_charged_earn_fee() {
        String no="PF"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        var source=new SettleSourcePort.SettleSource("S:"+no,"M:"+no,"MERCHANT_OWNED",1000,0,0,null,1,
                "STORE:"+no,200,300,"TEST","MP",0,0,null);
        var input=new SettleService.SettleInput(source,MerchantQueryPort.MODE_SELF_OPERATED,"ACCOUNT", "NATURAL",
                MerchantQueryPort.FUNDS_AGGREGATED,"PLATFORM","CN");
        assertThat(settlement.generateForOrder("O:"+no,List.of(input))).isEqualTo(1);
        StlBill bill=DataScopeContext.executeWithoutScope(() -> bills.selectOne(Wrappers.<StlBill>lambdaQuery()
                .eq(StlBill::getSubOrderNo,source.subOrderNo())));
        assertThat(bill.getPointsFeeMinor()).isZero();
        assertThat(bill.getGrossMinor()).isEqualTo(1200);
        assertThat(bill.getNetMinor()).isEqualTo(bill.getGrossMinor()-bill.getCommissionMinor()
                -bill.getServiceFeeMinor());
        assertThat(new JdbcTemplate(payDataSource).queryForObject("SELECT COUNT(*) FROM stl_points_pool"
                + " WHERE ref_no=?",Long.class,bill.getSettleNo())).isZero();
    }
}
