package ai.neargo.shop.trade.stock;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real database receipt identity, isolated from all configured native test databases. */
class FoodStockBridgeJournalTest {
    private FoodStockBridgeJournal journal;private JdbcTemplate db;
    @BeforeEach void createDatabase(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:bridge_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(source);
        db.execute("CREATE TABLE ord_food_stock_bridge_command(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),plan_no VARCHAR(32),action VARCHAR(32),operation_key VARCHAR(128),intent_hash CHAR(64),command_json CLOB,payload_json CLOB,created_at TIMESTAMP,created_by VARCHAR(32),UNIQUE(tenant_no,entity_no,store_no,plan_no,action,operation_key))");
        journal=new FoodStockBridgeJournal(source,new DataSourceTransactionManager(source),JsonMapper.builder().build());}
    private FoodStockBridgeJournal.Wire wire(long revision,String payload){return new FoodStockBridgeJournal.Wire(new FoodLocalStockPort.Command("LOCAL_KEY","a".repeat(64),"CORE_ORDER","PLAN",0,revision),payload);}
    @Test void replayUsesOriginalRevisionAndPayloadAfterSourceProgresses(){var scope=new FoodLocalStockPort.Scope("E1","S1");var builds=new AtomicInteger();
        var first=journal.acquire(scope,"PLAN","ISSUE","OP1","b".repeat(64),()->{builds.incrementAndGet();return wire(7,"original");});
        var replay=journal.acquire(scope,"PLAN","ISSUE","OP1","b".repeat(64),()->{builds.incrementAndGet();return wire(99,"changed-current-state");});
        assertThat(replay).isEqualTo(first);assertThat(replay.command().lifecycleVersion()).isEqualTo(7);assertThat(replay.payloadJson()).isEqualTo("original");assertThat(builds).hasValue(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ord_food_stock_bridge_command",Integer.class)).isEqualTo(1);}
    @Test void changedIntentCannotReuseOriginalOperationOrOverwriteReceipt(){var scope=new FoodLocalStockPort.Scope("E1","S1");journal.acquire(scope,"PLAN","ISSUE","OP1","b".repeat(64),()->wire(7,"original"));
        assertThatThrownBy(()->journal.acquire(scope,"PLAN","ISSUE","OP1","c".repeat(64),()->wire(8,"replacement"))).isInstanceOf(BizException.class);
        assertThat(journal.lookup(scope,"PLAN","ISSUE","OP1","b".repeat(64)).payloadJson()).isEqualTo("original");}
    @Test void sameOperationKeyInAnotherStoreDoesNotReadOriginalStoreReceipt(){var first=new FoodLocalStockPort.Scope("E1","S1");var second=new FoodLocalStockPort.Scope("E1","S2");journal.acquire(first,"PLAN","ISSUE","OP1","b".repeat(64),()->wire(7,"store-one"));
        assertThat(journal.lookup(second,"PLAN","ISSUE","OP1","b".repeat(64))).isNull();
        assertThat(journal.acquire(second,"PLAN","ISSUE","OP1","b".repeat(64),()->wire(2,"store-two")).payloadJson()).isEqualTo("store-two");}
}
