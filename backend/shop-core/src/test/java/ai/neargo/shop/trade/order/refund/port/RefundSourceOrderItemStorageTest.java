package ai.neargo.shop.trade.order.refund.port;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.refund.port.impl.MybatisRefundSourceStore;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class RefundSourceOrderItemStorageTest {

    @Test
    void ordinaryOrderItemReadsCanUseCanonicalTradeStorage() {
        var table = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "order-item-storage"), OrdItem.class);
        assertThat(table.getAllSqlSelect()).doesNotContain("coupon_share_minor");
    }

    @Test
    void findsLegacySharesAndLeavesAbsentCanonicalEvidenceUnknown() throws SQLException {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE ord_item (
                        id BIGINT PRIMARY KEY, sub_order_no VARCHAR(32), deleted INT, coupon_share_minor BIGINT
                    )
                    """);
            sql.execute("INSERT INTO ord_item VALUES (1, 'SUB1', 0, 31), (2, 'SUB1', 1, 41)");
            var configuration = new MybatisConfiguration(
                    new Environment("order-items", new JdbcTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(OrderItemMapper.class);
            var sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);

            try (var session = sessions.openSession()) {
                var store = new MybatisRefundSourceStore(session.getMapper(OrderItemMapper.class), null);
                assertThat(store.items("SUB1")).extracting(OrdItem::getCouponShareMinor).containsExactly(31L);
                assertThat(store.lockItems("SUB1")).extracting(OrdItem::getCouponShareMinor).containsExactly(31L);
            }
            sql.execute("ALTER TABLE ord_item DROP COLUMN coupon_share_minor");
            try (var session = sessions.openSession()) {
                var store = new MybatisRefundSourceStore(session.getMapper(OrderItemMapper.class), null);
                assertThat(store.items("SUB1")).extracting(OrdItem::getCouponShareMinor).containsExactly((Long) null);
                assertThat(store.lockItems("SUB1")).extracting(OrdItem::getCouponShareMinor).containsExactly((Long) null);
            }
        }
    }
}
