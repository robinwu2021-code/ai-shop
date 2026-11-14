package ai.neargo.shop.pay.storedvalue.migration;

import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.DataAccessException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StoredValueSchemaDdlTest {
    record Fixture(StoredValueSchemaDdl ddl,Connection connection,Statement statement){}
    Fixture fixture() throws Exception{
        var source=mock(DataSource.class);var connection=mock(Connection.class);var statement=mock(Statement.class);
        when(source.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);when(connection.getAutoCommit()).thenReturn(true);
        when(statement.executeQuery(anyString())).thenAnswer(call->{
            var result=mock(ResultSet.class);when(result.next()).thenReturn(true);
            when(result.getLong(1)).thenReturn(((String)call.getArgument(0)).contains("@@SESSION")?31536000L:1L);
            return result;
        });
        return new Fixture(new StoredValueSchemaDdl(new DataSourceTransactionManager(source)),connection,statement);
    }
    @Test void uncertainLockAcquisitionAbortsInsteadOfReturningAPossiblyLockedSession() throws Exception{
        var f=fixture();when(f.statement().executeQuery(contains("GET_LOCK"))).thenThrow(new SQLException("sensitive transport contents"));
        var invoked=new AtomicBoolean();
        assertThatThrownBy(()->f.ddl().locked(execute->{invoked.set(true);return null;})).isInstanceOf(DataAccessException.class).hasMessageNotContaining("sensitive");
        assertThat(invoked).isFalse();verify(f.connection()).abort(any());
    }
    @Test void failureAfterDdlAttemptStillRestoresTimeoutAndReleasesTheSameSessionLock() throws Exception{
        var f=fixture();when(f.statement().executeUpdate(anyString())).thenThrow(new SQLException("secret details"));
        assertThatThrownBy(()->f.ddl().locked(execute->{execute.accept("ALTER TABLE sv_account DROP INDEX uk_sv_owner");return null;})).isInstanceOf(DataAccessException.class).hasMessageNotContaining("secret");
        verify(f.statement()).execute("SET SESSION lock_wait_timeout=31536000");
        verify(f.statement()).executeQuery(contains("RELEASE_LOCK"));
        verify(f.connection(),never()).abort(any());
    }
    @Test void releaseFailureAbortsAndUnknownSqlNeverExecutes() throws Exception{
        var f=fixture();when(f.statement().executeQuery(contains("RELEASE_LOCK"))).thenThrow(new SQLException("lost release"));
        assertThatThrownBy(()->f.ddl().locked(execute->"done")).isInstanceOf(DataAccessException.class);
        verify(f.connection()).abort(any());
        var other=fixture();
        assertThatThrownBy(()->other.ddl().locked(execute->{execute.accept("DROP TABLE sv_account");return null;})).isInstanceOf(ai.neargo.shop.common.BizException.class);
        verify(other.statement(),never()).executeUpdate(anyString());
        verify(other.statement()).executeQuery(contains("RELEASE_LOCK"));
    }
}
