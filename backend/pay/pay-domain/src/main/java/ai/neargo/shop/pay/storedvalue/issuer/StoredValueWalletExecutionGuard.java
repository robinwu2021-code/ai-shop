package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Proof;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Pay-local checks only. Source and consent are resolved before this transaction; finalization honors an original hold. */
@Service
public class StoredValueWalletExecutionGuard {
    @org.springframework.beans.factory.annotation.Autowired private StoredValueIssueQuotaService quotas;
    private final IssuerRepository db;private final boolean enabled;private final boolean crossEntity;
    public StoredValueWalletExecutionGuard(IssuerRepository db,@Value("${shop.pay.stored-value.brand-execution-enabled:false}")boolean enabled,@Value("${shop.pay.stored-value.cross-entity-enabled:false}")boolean crossEntity){this.db=db;this.enabled=enabled;this.crossEntity=crossEntity;}
    public boolean before(EntitlementContext ctx,UseCommand command,Proof proof,boolean finalizing){
        if(proof==null)return false;if(!db.payTransactionActive())throw new IllegalStateException("Pay transaction required");
        var scope=proof.scope();var selected=proof.selected();boolean brand="BRAND".equals(selected.scopeType());
        if(!enabled&&!finalizing)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(!ctx.tenantNo().equals(scope.tenantNo())||!command.entityNo().equals(scope.entityNo())||!command.storeNo().equals(scope.storeNo())||!command.currency().equals(scope.currency())||!command.userNo().equals(proof.userNo())||!command.accountNo().equals(selected.accountNo())||!command.currency().equals(selected.currency())||command.expectedAssetRevision()!=selected.assetRevision())conflict();
        if(brand?!Objects.equals(scope.brandNo(),selected.scopeNo()):!"STORE".equals(selected.scopeType())||!scope.storeNo().equals(selected.scopeNo()))conflict();
        var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),selected.issuerNo());
        if(issuer==null||!"ISSUER".equals(text(issuer,"write_mode"))||!Set.of("ACTIVE","ISSUE_STOPPED").contains(text(issuer,"status"))||!selected.scopeType().equals(text(issuer,"scope_type"))||!selected.scopeNo().equals(text(issuer,"scope_no")))conflict();
        var frozen=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),selected.issuerNo(),selected.issuerVersionNo());
        if(frozen==null||!command.currency().equals(text(frozen,"currency")))conflict();
        if(!finalizing&&!crossEntity&&!command.entityNo().equals(text(frozen,"settlement_entity_no")))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(!finalizing){
            var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),selected.issuerNo(),scope.storeNo());
            String binding=brand?scope.brandBindingNo():scope.storeNo();long revision=brand?scope.brandBindingRevision():scope.storeRevision();
            if(access==null||!"ACTIVE".equals(text(access,"status"))||number(access,"can_redeem")!=1||!scope.entityNo().equals(text(access,"operating_entity_no"))||!Objects.equals(binding,text(access,"source_binding_no"))||revision!=number(access,"source_revision"))conflict();
            var from=instant(access.get("effective_from"));var to=instant(access.get("effective_to"));var now=Instant.now();if(from==null||now.isBefore(from)||to!=null&&!now.isBefore(to))conflict();
        }
        if(brand)quotas.lockForAccount(ctx,command.accountNo());
        return brand;
    }
    /** Called only after the account mapper has acquired the selected row lock. */
    public void account(EntitlementContext ctx,UseCommand command,Proof proof){
        if(proof==null)return;if(!db.payTransactionActive())throw new IllegalStateException("Pay transaction required");
        var selected=proof.selected();var account=db.one("SELECT * FROM sv_account WHERE tenant_no=? AND account_no=? AND deleted=0",ctx.tenantNo(),command.accountNo());
        if(account==null||!selected.issuerNo().equals(text(account,"issuer_no"))||!selected.customerNo().equals(text(account,"customer_no"))||!command.userNo().equals(text(account,"user_no"))||!command.currency().equals(text(account,"currency")))conflict();
        var version=db.one("SELECT settlement_entity_no FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),selected.issuerNo(),selected.issuerVersionNo());
        if(version==null||!Objects.equals(text(account,"entity_no"),text(version,"settlement_entity_no")))conflict();
        if("STORE".equals(selected.scopeType())?!command.storeNo().equals(text(account,"store_no")):text(account,"store_no")!=null)conflict();
    }
    private static Instant instant(Object value){if(value==null)return null;if(value instanceof java.sql.Timestamp time)return time.toInstant();if(value instanceof LocalDateTime time)return time.toInstant(ZoneOffset.UTC);if(value instanceof Instant time)return time;throw new IllegalStateException("Unsupported Pay timestamp");}
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
