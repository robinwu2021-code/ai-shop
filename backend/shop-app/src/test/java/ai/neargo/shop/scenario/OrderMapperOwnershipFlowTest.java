package ai.neargo.shop.scenario;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapper归属与真实工厂、SQL、预约释放CAS；不以目录存在当作装配完成。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-mapper-owner;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class OrderMapperOwnershipFlowTest {
    private static final String PACKAGE = "ai.neargo.shop.trade.order.mapper.";
    private static final List<String> OWNED = List.of("PaymentMapper", "StoreOrderMapper", "OrderItemMapper",
            "OrderStatusLogMapper", "AfterSaleMapper", "InvoiceRequestMapper", "ShippingUploadMapper",
            "ExpressPickupMapper", "SeqMapper");
    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyOrderMapperHasOneBean() {
        for (String name : OWNED) {
            var matches = factories(name);
            assertThat(matches).as("订单域%s恰一套生产装配", name).hasSize(1);
            String beanName = Character.toLowerCase(name.charAt(0)) + name.substring(1);
            assertThat(matches.getFirst().getKey()).isEqualTo(beanName);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void orderMappersUsePlatformFactoryAndSameRows() throws Exception {
        SqlSessionFactory platform = context.getBean("sqlSessionFactory", SqlSessionFactory.class);
        for (String name : OWNED) {
            List<Map.Entry<String, MapperFactoryBean<?>>> matches = factories(name);
            assertThat(matches).as("%s必须由真实Mapper工厂注册", name).hasSize(1);
            SqlSessionTemplate session = (SqlSessionTemplate) matches.getFirst().getValue().getSqlSession();
            assertThat(session.getSqlSessionFactory()).isSameAs(platform);
        }
        var store = factories("StoreOrderMapper").getFirst();
        BaseMapper<OrdSubOrder> mapper = (BaseMapper<OrdSubOrder>) context.getBean(store.getKey());
        String no = "MO-" + UUID.randomUUID();
        jdbc.update("insert into ord_sub_order (sub_order_no, order_no, user_no, entity_no, status, fulfillment, "
                + "pay_amount, goods_amount, appointment_slot_no, tenant_no, version, deleted, created_at, updated_at) "
                + "values (?, ?, 'U-MAPPER', 'M0001', 'WAIT_PAY', 'APPOINTMENT', 1000, 1000, 'SLOT-M', "
                + "'MAIN', 0, 0, now(), now())", no, "P-" + no);
        Long id = jdbc.queryForObject("select id from ord_sub_order where sub_order_no = ?", Long.class, no);
        assertThat(mapper.selectById(id).getSubOrderNo()).isEqualTo(no);
        var release = store.getValue().getMapperInterface().getMethod("markAppointmentReleased", String.class, long.class);
        assertThat(release.invoke(mapper, no, 100L)).isEqualTo(1);
        assertThat(release.invoke(mapper, no, 200L)).isEqualTo(0);
        assertThat(jdbc.queryForObject("select appointment_released_at from ord_sub_order where sub_order_no = ?",
                Long.class, no)).isEqualTo(100L);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map.Entry<String, MapperFactoryBean<?>>> factories(String simpleName) {
        return context.getBeansOfType(BaseMapper.class).keySet().stream()
                .<Map.Entry<String, MapperFactoryBean<?>>>map(name -> Map.entry(name,
                        (MapperFactoryBean<?>) context.getBean("&" + name)))
                .filter(entry -> entry.getValue().getMapperInterface().getName().equals(PACKAGE + simpleName))
                .toList();
    }
}
