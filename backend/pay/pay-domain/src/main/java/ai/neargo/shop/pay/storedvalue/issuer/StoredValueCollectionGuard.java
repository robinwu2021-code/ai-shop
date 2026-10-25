package ai.neargo.shop.pay.storedvalue.issuer;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.storedvalue.StoredValueCollections.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
/** Pay-local validation of Core's per-request financial authorization; no RPC under financial locks. */
@Service
public class StoredValueCollectionGuard {
 private final IssuerRepository db;public StoredValueCollectionGuard(IssuerRepository db){this.db=db;}
 public Requirement requirement(ExecutionScope s){if(s.brandNo()==null)return null;var row=db.one("SELECT i.issuer_no,i.current_issuer_version_no,v.settlement_entity_no FROM sv_issuer i JOIN sv_issuer_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(v.issuer_no AS BINARY(256))=CAST(i.issuer_no AS BINARY(256)) AND CAST(v.issuer_version_no AS BINARY(256))=CAST(i.current_issuer_version_no AS BINARY(256)) WHERE i.tenant_no=? AND i.scope_type='BRAND' AND i.scope_no=? AND v.currency=? AND i.deleted=0 AND v.deleted=0",s.tenantNo(),s.brandNo(),s.currency());return row==null?null:new Requirement(s,text(row,"issuer_no"),text(row,"current_issuer_version_no"),text(row,"settlement_entity_no"),!s.entityNo().equals(text(row,"settlement_entity_no")));}
 public boolean canCollect(Query q,String issuer,String version,String settlement){return canCollect(q.scope(),q.collectionProof(),issuer,version,settlement);}
 public boolean canCollect(ExecutionScope s,ai.neargo.shop.spi.storedvalue.StoredValueCollections.Proof p,String issuer,String version,String settlement){if(s.entityNo().equals(settlement))return true;if(p==null||s.coreFinanceProtocol()<2)return false;var r=p.requirement();String actual=p.authenticatedActorKey(),actor=s.actorNo();boolean sameActor=actual.equals(actor)||actual.substring(actual.indexOf(':')+1).equals(actor);var now=Instant.now();return sameActor&&r.required()&&s.equals(r.scope())&&issuer.equals(r.issuerNo())&&version.equals(r.issuerVersionNo())&&settlement.equals(r.settlementEntityNo())&&p.expiresAt().isAfter(now)&&!p.expiresAt().isAfter(now.plusSeconds(90));}
 public void require(Query q,String issuer,String version,String settlement){if(!canCollect(q,issuer,version,settlement))throw BizException.of(ErrorCode.FORBIDDEN);}
}
