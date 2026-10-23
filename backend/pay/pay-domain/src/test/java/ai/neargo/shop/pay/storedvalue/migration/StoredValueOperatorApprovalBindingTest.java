package ai.neargo.shop.pay.storedvalue.migration;
import ai.neargo.shop.common.BizException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredValueOperatorApprovalBindingTest {
 @Test void replacementApprovalCannotExecuteAnyActionAfterCoreVerifiedDifferentSources(){
  var db=mock(ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.class);var auth=mock(StoredValueSchemaAuthorization.class);var inspector=mock(StoredValueAccountKeyInspector.class);var ddl=mock(StoredValueSchemaDdl.class);
  var approval=new StoredValueSchemaAuthorization.Approval("A1","OP","a".repeat(64),"b".repeat(64),Instant.now().plusSeconds(600),true,List.of());var changed=new StoredValueSchemaAuthorization.Authorized(approval,"c".repeat(64),1,1);
  when(auth.authorize("OP","A1",null)).thenReturn(changed);when(auth.authorizeApproval("OP","A1",null)).thenReturn(changed);
  var keys=new StoredValueAccountKeyCutover(db,auth,inspector,ddl);var dimensions=new StoredValueAccountDimensionGuard(db,auth,inspector,ddl);var resume=new StoredValueMigrationResumeService(db,auth,inspector,dimensions,ddl);
  assertThatThrownBy(()->keys.step("OP","A1",null,"d".repeat(64))).isInstanceOf(BizException.class);
  assertThatThrownBy(()->dimensions.install("OP","A1",null,"d".repeat(64))).isInstanceOf(BizException.class);
  assertThatThrownBy(()->resume.resume("OP","A1",null,"d".repeat(64))).isInstanceOf(BizException.class);
  verifyNoInteractions(db,ddl,inspector);
 }
}
