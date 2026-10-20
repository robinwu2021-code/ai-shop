package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.type.PointTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PointTypeServiceTest {
    @Autowired private PointTypeService types;
    @Autowired private PointsReadStore read;
    @Autowired @Qualifier("payDataSource") private DataSource dataSource;

    @Test
    void active_unique_rate_locked_and_drain() {
        String a = no(); String b = no();
        types.create(new PointTypeService.CreateType(a,"CN",new RedeemRate(1,1),3000,365),"maker");
        types.create(new PointTypeService.CreateType(b,"CN",new RedeemRate(3,2),3000,365),"maker");
        try {
            types.activate(a,"maker");
            assertThatThrownBy(() -> types.activate(b,"maker")).isInstanceOf(IllegalArgumentException.class);
            types.drain(a,"maker");
            assertThat(read.activeType("CN").orElseThrow().status()).isEqualTo(PointType.Status.DRAINING);
            assertThatThrownBy(() -> types.activate(b,"maker")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> types.create(new PointTypeService.CreateType(a,"CN",new RedeemRate(3,2),
                    3000,365),"maker")).hasMessageContaining("POINTS_TYPE_DUPLICATE");
            assertThat(read.type(a).orElseThrow().rate()).isEqualTo(new RedeemRate(1,1));
        } finally {
            new JdbcTemplate(dataSource).update("UPDATE pts_point_type SET active_market=NULL WHERE type_code IN (?,?)",a,b);
        }
    }

    @Test
    void point_type_name_is_unique_and_returned_with_type() {
        String first = no();
        String second = no();
        types.create(new PointTypeService.CreateType(first, "商城积分"), "maker");
        assertThat(read.type(first).orElseThrow().name()).isEqualTo("商城积分");
        assertThat(read.type(first).orElseThrow().rate()).isEqualTo(new RedeemRate(1, 1));
        assertThat(read.type(first).orElseThrow().market()).isEqualTo("CN");
        assertThat(read.type(first).orElseThrow().currency()).isEqualTo("CNY");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO pts_point_type(type_code,name,market,currency,rate_points,rate_minor) VALUES(?,'商城积分','CN','CNY',1,1)", second))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO pts_point_type(type_code,name,market,currency,rate_points,rate_minor) VALUES(?,'其他名称','CN','CNY',1,1)", first))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> types.create(new PointTypeService.CreateType(second, "商城积分"), "maker"))
                .hasMessageContaining("POINTS_TYPE_DUPLICATE");
    }
    private String no() { return "TY"+UUID.randomUUID().toString().replace("-", "").substring(0,20); }
}
