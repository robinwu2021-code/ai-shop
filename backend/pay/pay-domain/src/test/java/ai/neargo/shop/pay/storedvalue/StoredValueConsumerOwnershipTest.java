package ai.neargo.shop.pay.storedvalue;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.mapper.storedvalue.SvConsumerStatementMapper;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

/** Executes the real ownership SQL against stale offline statements, not a permissive mock. */
class StoredValueConsumerOwnershipTest {
    private SqlSession session() throws Exception {
        var ds=new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL","sa","");
        Connection c=ds.getConnection();
        try(var q=c.createStatement()) {
            q.execute("CREATE TABLE sv_statement(record_no VARCHAR,tenant_no VARCHAR,store_no VARCHAR,entity_no VARCHAR,"
                    + "account_no VARCHAR,customer_no VARCHAR,user_no VARCHAR,type VARCHAR,business_no VARCHAR,"
                    + "occurred_at TIMESTAMP,deleted INT)");
            q.execute("CREATE TABLE sv_account(account_no VARCHAR,tenant_no VARCHAR,store_no VARCHAR,"
                    + "entity_no VARCHAR,customer_no VARCHAR,user_no VARCHAR,deleted INT)");
            q.execute("CREATE TABLE sv_customer_identity(tenant_no VARCHAR,customer_no VARCHAR,user_no VARCHAR)");
            q.execute("INSERT INTO sv_account VALUES ('A','T','S','E','OFFLINE',NULL,0),"
                    + "('B','T','OTHER','E','OTHER','U',0),('C','TENANT2','S','E','OFFLINE','U',0),"
                    + "('D','T','S','E','DELETED','U',1)");
            q.execute("INSERT INTO sv_customer_identity VALUES ('T','OFFLINE','U')");
            q.execute("INSERT INTO sv_statement VALUES "
                    + "('R3','T','S','E','A','OFFLINE',NULL,'REDEMPTION','OP3','2026-10-21 01:00:00',0),"
                    + "('R2','T','S','E','A','OFFLINE',NULL,'RECHARGE','OP2','2026-10-21 01:00:00',0),"
                    + "('R1','T','S','E','A','OFFLINE',NULL,'RECHARGE','OP1','2026-10-20 01:00:00',0),"
                    + "('EVIL_STORE','T','S','E','B','OTHER','U','RECHARGE','X','2026-10-22 01:00:00',0),"
                    + "('EVIL_ENTITY','T','S','WRONG','A','OFFLINE','U','RECHARGE','X','2026-10-22 01:00:00',0),"
                    + "('EVIL_TENANT','TENANT2','S','E','C','OFFLINE','U','RECHARGE','X','2026-10-22 01:00:00',0),"
                    + "('EVIL_DELETED','T','S','E','D','DELETED','U','RECHARGE','X','2026-10-22 01:00:00',0)");
        }
        var config=new Configuration(new Environment("test",new JdbcTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(SvConsumerStatementMapper.class);
        // Keep the memory database alive until the session acquires its own connection.
        var session=new SqlSessionFactoryBuilder().build(config).openSession();
        session.getConnection(); c.close(); return session;
    }
    @Test void offlineBindingAuthorizesHistoryWithoutRewritingStatementsAndKeepsScopes() throws Exception {
        try(var session=session()) {
            var mapper=session.getMapper(SvConsumerStatementMapper.class);
            assertThat(mapper.page("T","U","S","ALL",null,null,null,null,false,20))
                    .extracting(r->r.getRecordNo()).containsExactly("R3","R2","R1");
            assertThat(mapper.page("T","OTHER_USER","S","ALL",null,null,null,null,false,20)).isEmpty();
            assertThat(mapper.focus("T","U","S","RECHARGE","X")).isNull();
        }
    }
    @Test void equalTimestampsUseExclusiveStableKeysetsAndFocusIncludesItsAnchor() throws Exception {
        try(var session=session()) {
            var mapper=session.getMapper(SvConsumerStatementMapper.class);
            var at=LocalDateTime.parse("2026-10-21T01:00:00");
            assertThat(mapper.page("T","U","S","ALL",null,null,at,"R3",false,20))
                    .extracting(r->r.getRecordNo()).containsExactly("R2","R1");
            assertThat(mapper.page("T","U","S","ALL",null,null,at,"R2",true,20))
                    .extracting(r->r.getRecordNo()).containsExactly("R2","R1");
            assertThat(mapper.page("T","U","S","RECHARGE",at,at.plusDays(1),null,null,false,20))
                    .extracting(r->r.getRecordNo()).containsExactly("R2");
            assertThat(mapper.page("T","U","S","ALL",null,at,null,null,false,20))
                    .extracting(r->r.getRecordNo()).containsExactly("R1");
        }
    }
}
