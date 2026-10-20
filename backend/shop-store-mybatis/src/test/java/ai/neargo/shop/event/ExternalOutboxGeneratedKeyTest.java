package ai.neargo.shop.event;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExternalOutboxGeneratedKeyTest {
    record Received(String id) implements DomainEvent {
        public String aggregateType(){return "STORED_VALUE_RECHARGE";}
        public String aggregateId(){return id;}
        public String eventType(){return "STORED_VALUE_RECHARGE_RECEIVED";}
    }
    @Test void externalInsertReturnsItsGeneratedKeyAndWakesPumpOnlyAfterCommit() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:external-outbox-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sys_outbox (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_no VARCHAR(64) UNIQUE,aggregate_type VARCHAR(64),aggregate_id VARCHAR(64),event_type VARCHAR(64),payload VARCHAR(1000),status VARCHAR(16),retry_count INT,created_at TIMESTAMP)");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(SysOutboxMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(SysOutboxMapper.class);
        var pump=mock(OutboxPump.class);
        @SuppressWarnings("unchecked") ObjectProvider<OutboxPump> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(pump);
        var bus=new OutboxEventBus(mapper,new ObjectMapper(),provider);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.executeWithoutResult(s->{bus.publish("ORIGINAL-EVENT",new Received("ORIGINAL-RECHARGE"));verifyNoInteractions(pump);});
        Long id=jdbc.queryForObject("SELECT id FROM sys_outbox WHERE event_no='ORIGINAL-EVENT'",Long.class);
        assertThat(id).isPositive();verify(pump).submit(id,"ORIGINAL-RECHARGE");
        tx.executeWithoutResult(s->bus.publish("ORIGINAL-EVENT",new Received("ORIGINAL-RECHARGE")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isEqualTo(1);
        verifyNoMoreInteractions(pump);
    }
}
