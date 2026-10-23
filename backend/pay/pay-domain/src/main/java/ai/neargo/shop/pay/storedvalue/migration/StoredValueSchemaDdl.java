package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.common.*;
import java.sql.*;
import java.util.Set;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A dedicated autocommit MySQL session holds the database-scoped advisory lock across durable journal transactions. */
@Service
public class StoredValueSchemaDdl {
    private final DataSource source;
    private static final String LOCK="CONCAT('sv-key-',SHA2(DATABASE(),224))";
    private static final Set<String> ALLOWED=Set.of(
        "ALTER TABLE sv_account ADD UNIQUE KEY uk_sv_issuer_customer (tenant_no,issuer_no,customer_no,currency)",
        "ALTER TABLE sv_account DROP INDEX uk_sv_owner, DROP INDEX uk_sv_customer_owner",
        "ALTER TABLE sv_account DROP INDEX uk_sv_owner",
        "ALTER TABLE sv_account DROP INDEX uk_sv_customer_owner");
    public StoredValueSchemaDdl(@Qualifier("payTxManager") PlatformTransactionManager manager){
        if(!(manager instanceof DataSourceTransactionManager data))throw new IllegalStateException("Pay JDBC datasource required");
        source=java.util.Objects.requireNonNull(data.getDataSource());
    }
    public <T>T locked(Function<Consumer<String>,T> action){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw BizException.of(ErrorCode.CONFLICT);
        try(var connection=source.getConnection();var statement=connection.createStatement()){
            if(!connection.getAutoCommit())throw BizException.of(ErrorCode.CONFLICT);
            statement.setQueryTimeout(20);
            long acquired;
            try{acquired=scalar(statement,"SELECT GET_LOCK("+LOCK+",0)");}
            catch(SQLException uncertain){try{connection.abort(Runnable::run);}catch(SQLException ignored){}throw uncertain;}
            if(acquired!=1)throw BizException.of(ErrorCode.CONFLICT);
            long oldTimeout=0;boolean known=false;
            try{
                oldTimeout=scalar(statement,"SELECT @@SESSION.lock_wait_timeout");known=true;
                statement.execute("SET SESSION lock_wait_timeout=5");
                return action.apply(sql->{
                    if(!ALLOWED.contains(sql))throw BizException.of(ErrorCode.BAD_REQUEST);
                    try{statement.executeUpdate(sql);}
                    catch(SQLException failed){throw new DataAccessResourceFailureException("Stored value schema operation failed; inspect the durable intent");}
                });
            }finally{
                try{
                    if(known)statement.execute("SET SESSION lock_wait_timeout="+oldTimeout);
                    if(scalar(statement,"SELECT RELEASE_LOCK("+LOCK+")")!=1)throw new SQLException("Lock release failed");
                }catch(SQLException cleanup){
                    // Never return a session with a held advisory lock or altered timeout to the connection pool.
                    try{connection.abort(Runnable::run);}catch(SQLException ignored){}
                    throw new DataAccessResourceFailureException("Stored value schema session cleanup failed");
                }
            }
        }catch(SQLException unavailable){throw new DataAccessResourceFailureException("Stored value schema session unavailable");}
    }
    private static long scalar(Statement statement,String sql)throws SQLException{
        try(var result=statement.executeQuery(sql)){if(!result.next())throw new SQLException("Missing scalar");long value=result.getLong(1);if(result.wasNull())throw new SQLException("Null scalar");return value;}
    }
}
