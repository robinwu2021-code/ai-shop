package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueBrandRuntimeReadiness;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Capability;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfSystemProperty(named="p6.mysql.url",matches="jdbc:mysql://127.*")
class StoredValueSettlementSchemaTest {
 @Test void readinessRequiresEnforcedDatabaseConstraintsAndNeverOpensUnfinishedCoordination()throws Exception{
  var f=new StoredValueSettlementFixture();f.ddl(f.file("V576__stored_value_financial_membership_saga.sql"));f.jdbc.execute("CREATE TABLE flyway_schema_history(version VARCHAR(32),success INT)");f.jdbc.update("INSERT INTO flyway_schema_history VALUES('575',1),('576',1)");var foundation=mock(StoredValueBrandRuntimeReadiness.class);when(foundation.capability()).thenReturn(new Capability(1,true,List.of()));var readiness=new StoredValueCrossEntityReadiness(f.db,foundation,f.cipher,true,true);
  assertTrue(readiness.schemaReady());assertTrue(readiness.settlementReady());assertFalse(readiness.capability().ready());assertTrue(readiness.capability().blockers().contains("P6_FINANCIAL_COORDINATION_NOT_INSTALLED"));assertThrows(BizException.class,()->readiness.requireCross(2));
  String constraint=f.jdbc.queryForObject("SELECT constraint_name FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='sv_financial_membership_change' AND constraint_type='CHECK' ORDER BY constraint_name LIMIT 1",String.class);assertTrue(constraint.matches("[a-zA-Z0-9_]+"));f.jdbc.execute("ALTER TABLE sv_financial_membership_change DROP CHECK `"+constraint+"`");assertFalse(readiness.schemaReady());
 }
}
