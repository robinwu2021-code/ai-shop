package ai.neargo.shop.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class AfterCommitOutsideTransactionTest {
    @Test void directActionSeesCommittedDataAndItsOwnWritesCommit() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:direct-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var sql=new JdbcTemplate(source);sql.execute("CREATE TABLE facts(id INT PRIMARY KEY)");
        var manager=new DataSourceTransactionManager(source);
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            sql.update("INSERT INTO facts VALUES(1)");
            AfterCommit.runOutsideTransaction("direct",manager,()->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM facts",Long.class)).isEqualTo(1);
                new TransactionTemplate(manager).executeWithoutResult(own->sql.update("INSERT INTO facts VALUES(2)"));
            });
        });
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM facts",Long.class)).isEqualTo(2);
    }
    @Test void rollbackNeverCallsTheRemoteAction() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:direct-rollback-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var sql=new JdbcTemplate(source);sql.execute("CREATE TABLE facts(id INT PRIMARY KEY)");
        var manager=new DataSourceTransactionManager(source);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
            AfterCommit.runOutsideTransaction("direct",manager,()->sql.update("INSERT INTO facts VALUES(1)"));
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM facts",Long.class)).isZero();
    }
}
