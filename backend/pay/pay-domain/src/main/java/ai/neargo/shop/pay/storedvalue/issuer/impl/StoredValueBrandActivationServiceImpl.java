package ai.neargo.shop.pay.storedvalue.issuer.impl;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueBrandActivationService;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueBrandRuntimeReadiness;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Ack;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Prepare;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Activates the entire trusted brand membership snapshot; no individual manual store enablement list. */
@Service("storedValueBrandActivationService")
public class StoredValueBrandActivationServiceImpl implements StoredValueBrandActivationService {
    private final IssuerRepository db;private final StoredValueBrandRuntimeReadiness readiness;private final EntitlementCommandDeduplicator commands;private final boolean crossEntity;
    public StoredValueBrandActivationServiceImpl(IssuerRepository db,StoredValueBrandRuntimeReadiness readiness,EntitlementCommandDeduplicator commands,@Value("${shop.pay.stored-value.cross-entity-enabled:false}")boolean crossEntity){this.db=db;this.readiness=readiness;this.commands=commands;this.crossEntity=crossEntity;}
    public Ack activate(Prepare command){
        var authority=command.authority();var view=command.issuer();var scope=new CatalogScope(authority.tenantNo(),view.settlementEntityNo(),authority.actorNo(),false);var material=material(command);
        var prior=commands.replay(scope,"SV_BRAND_ACTIVATION",command.activationNo(),material,Ack.class);if(prior.isPresent())return prior.get();readiness.require();
        return db.write(()->commands.execute(scope,"SV_BRAND_ACTIVATION",command.activationNo(),material,view.issuerNo(),Ack.class,()->{
            var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND scope_type='BRAND' AND scope_no=? AND deleted=0 FOR UPDATE",authority.tenantNo(),view.issuerNo(),authority.scopeNo());
            if(issuer==null||!view.issuerVersionNo().equals(text(issuer,"current_issuer_version_no"))||!Set.of("DRAFT","ACTIVE").contains(text(issuer,"status"))||!Set.of("LEGACY","ISSUER").contains(text(issuer,"write_mode")))conflict();
            var version=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",authority.tenantNo(),view.issuerNo(),view.issuerVersionNo());
            if(version==null||!view.settlementEntityNo().equals(text(version,"settlement_entity_no"))||!view.currency().equals(text(version,"currency"))||!authority.authorizedSettlementEntities().contains(view.settlementEntityNo())||!"CENTRALIZED".equals(text(version,"collection_mode"))||!"PRO_RATA".equals(text(version,"debit_allocation_mode")))conflict();
            var routes=db.routes(text(version,"collection_config"));if(!authority.authorizedRoutes().containsAll(routes)||routes.isEmpty()||routes.stream().anyMatch(r->!view.settlementEntityNo().equals(r.entityNo())))conflict();
            int accepted=0;var at=Timestamp.from(Instant.now());
            for(var store:command.stores().stream().sorted(Comparator.comparing(ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope::storeNo)).toList()){
                if(!authority.tenantNo().equals(store.tenantNo())||!authority.managementNo().equals(store.managementNo())||!authority.scopeNo().equals(store.brandNo())||store.brandBindingNo()==null||!view.currency().equals(store.currency()))conflict();
                boolean can=crossEntity||view.settlementEntityNo().equals(store.entityNo());if(can)accepted++;
                var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0 FOR UPDATE",authority.tenantNo(),view.issuerNo(),store.storeNo());
                if(access!=null&&(number(access,"source_revision")>store.brandBindingRevision()||number(access,"source_revision")==store.brandBindingRevision()&&!Objects.equals(text(access,"source_binding_no"),store.brandBindingNo())))conflict();
                if(access==null){var row=base(authority.tenantNo(),authority.actorNo(),at);row.put("access_no",BizKey.next(BizKey.STORED_VALUE_ISSUER_ACCESS));row.put("issuer_no",view.issuerNo());row.put("store_no",store.storeNo());row.put("operating_entity_no",store.entityNo());row.put("source_binding_no",store.brandBindingNo());row.put("source_revision",store.brandBindingRevision());row.put("can_issue",can?1:0);row.put("can_redeem",can?1:0);row.put("status","ACTIVE");row.put("effective_from",at);db.insert("sv_issuer_store_access",row);}
                else db.update("UPDATE sv_issuer_store_access SET operating_entity_no=?,source_binding_no=?,source_revision=?,can_issue=?,can_redeem=?,status='ACTIVE',effective_to=NULL,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND issuer_no=? AND store_no=?",store.entityNo(),store.brandBindingNo(),store.brandBindingRevision(),can?1:0,can?1:0,at,authority.actorNo(),authority.tenantNo(),view.issuerNo(),store.storeNo());
            }
            if(accepted==0)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            var expected=command.stores().stream().map(ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope::storeNo).collect(java.util.stream.Collectors.toSet());
            for(var old:db.rows("SELECT store_no FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND status='ACTIVE' AND deleted=0 FOR UPDATE",authority.tenantNo(),view.issuerNo()))if(!expected.contains(text(old,"store_no")))conflict();
            db.update("UPDATE sv_issuer SET status='ACTIVE',write_mode='ISSUER',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND issuer_no=?",at,authority.actorNo(),authority.tenantNo(),view.issuerNo());
            return new Ack(command.activationNo(),view.issuerNo(),view.issuerVersionNo(),command.sourceRevision(),accepted,"ISSUER_READY");
        }));
    }
    private List<Object> material(Prepare c){var a=c.authority();return List.of(c.activationNo(),a.tenantNo(),a.actorNo(),a.managementNo(),a.scopeNo(),a.authorityRevision(),c.issuer().issuerNo(),c.issuer().issuerVersionNo(),c.sourceRevision(),a.authorizedSettlementEntities().stream().sorted().toList(),a.authorizedRoutes().stream().sorted(Comparator.comparing(r->r.channel()+"|"+r.scene()+"|"+Objects.toString(r.storeNo(),""))).toList(),c.stores().stream().sorted(Comparator.comparing(ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope::storeNo)).toList());}
    private Map<String,Object> base(String tenant,String actor,Timestamp at){var row=new LinkedHashMap<String,Object>();row.put("tenant_no",tenant);row.put("version",0);row.put("deleted",0);row.put("created_at",at);row.put("updated_at",at);row.put("created_by",actor);row.put("updated_by",actor);return row;}
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
