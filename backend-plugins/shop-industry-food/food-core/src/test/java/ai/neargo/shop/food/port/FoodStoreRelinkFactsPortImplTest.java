package ai.neargo.shop.food.port;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FoodStoreRelinkFactsPortImplTest {
    @Test void wiringReadsTransactionHistoryWithNoTablesInPrimary() {
        var primary = datasource();
        var trade = datasource();
        var jdbc = new JdbcTemplate(trade);
        for (String table : List.of("fnb_order_batch", "fnb_channel_order_source", "fnb_channel_import_guard")) {
            jdbc.execute("CREATE TABLE " + table
                    + "(tenant_no VARCHAR(32),entity_no VARCHAR(64),store_no VARCHAR(64))");
        }
        jdbc.update("INSERT INTO fnb_channel_import_guard VALUES('MAIN','OLD_ENTITY','S')");
        new ApplicationContextRunner()
                .withUserConfiguration(FoodStoreRelinkFactsPortImpl.class)
                .withBean("foodSqlSessionTemplate", SqlSessionTemplate.class,
                        () -> template(primary))
                .withBean("foodTradeSqlSessionTemplate", SqlSessionTemplate.class,
                        () -> template(trade))
                .run(context -> {
                    var facts = context.getBean(FoodStoreRelinkFactsPortImpl.class);
                    assertTrue(facts.inspect("MAIN", "NEW_ENTITY", "S").hasHistory());
                    assertFalse(facts.inspect("MAIN", "NEW_ENTITY", "SIBLING").hasHistory());
                });
    }

    private static DriverManagerDataSource datasource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:industry_relink_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static SqlSessionTemplate template(DriverManagerDataSource datasource) {
        var environment = new Environment(
                "test", new SpringManagedTransactionFactory(), datasource);
        var factory = new SqlSessionFactoryBuilder()
                .build(new Configuration(environment));
        return new SqlSessionTemplate(factory);
    }

    @Test void scopedHistoryIncludesArchivedOriginalsAndUnknownTablesFailClosed() {
        var jdbc=new JdbcTemplate(datasource());
        var tables=List.of("fnb_order_batch","fnb_channel_order_source","fnb_channel_import_guard");
        for(String table:tables) jdbc.execute("CREATE TABLE "+table+"(tenant_no VARCHAR(32),entity_no VARCHAR(64),store_no VARCHAR(64),status VARCHAR(24),deleted INT)");
        var owner=new FoodStoreRelinkFactsPortImpl(jdbc);
        assertFalse(owner.inspect("MAIN","M","S").complete(), "Standalone evidence cannot promise a host write fence");
        owner.setOwnershipWrites(org.mockito.Mockito.mock(ai.neargo.shop.spi.user.StoreOwnershipWritePort.class));
        assertTrue(owner.inspect("MAIN","M","S").complete());
        assertTrue(owner.businessTypes().isEmpty(), "No invented mapping from caller/sourceOwner to business type");
        assertFalse(owner.inspect("MAIN","M","S").hasHistory());
        for(String table:tables) {
            jdbc.update("INSERT INTO "+table+" VALUES('OTHER','M','S','CANCELLED',1)");
            jdbc.update("INSERT INTO "+table+" VALUES('MAIN','M','SIBLING','CANCELLED',1)");
            assertFalse(owner.inspect("MAIN","M","S").hasHistory());
            jdbc.update("INSERT INTO "+table+" VALUES('MAIN','OLD_ENTITY','S','CANCELLED',1)");
            assertTrue(owner.inspect("MAIN","M","S").hasHistory());
            jdbc.update("DELETE FROM "+table);
            jdbc.update("INSERT INTO "+table+" VALUES('MAIN','M',NULL,'CANCELLED',1)");
            assertTrue(owner.inspect("MAIN","M","S").hasHistory());
            jdbc.update("DELETE FROM "+table);
        }
        jdbc.execute("DROP TABLE "+tables.getFirst());
        assertThrows(org.springframework.dao.DataAccessException.class,()->owner.inspect("MAIN","M","S"));
    }
}
