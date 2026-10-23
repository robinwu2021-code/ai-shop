package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Preparation only: preserve the existing store's liability, never activate or merge accounts. */
@Service
public class StoredValueStoreIssuerService {
    private final IssuerRepository db;
    public StoredValueStoreIssuerService(IssuerRepository db){this.db=db;}

    public View prepare(StoreAuthority scope) {
        if(scope==null||!"MAIN".equals(scope.tenantNo()))throw BizException.of(ErrorCode.BAD_REQUEST);
        return db.write(()->{
            var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND scope_type='STORE' AND scope_no=? AND deleted=0 FOR UPDATE",scope.tenantNo(),scope.storeNo());
            if(issuer!=null)return current(scope,issuer);
            var at=Timestamp.from(Instant.now());String no=BizKey.next(BizKey.STORED_VALUE_ISSUER),versionNo=BizKey.next(BizKey.STORED_VALUE_ISSUER_VERSION);
            var route=new Route(null,scope.entityNo(),scope.storeNo(),"MERCHANT_OFFLINE","OFFLINE");
            var version=base(scope,at);version.put("issuer_version_no",versionNo);version.put("issuer_no",no);
            version.put("configuration_key","LEGACY_STORE_DEFAULT");version.put("name","门店储值");version.put("version_index",1);
            version.put("settlement_entity_no",scope.entityNo());version.put("collection_mode","STORE_LOCAL");
            version.put("clearing_mode","INTERNAL_RECON");version.put("debit_allocation_mode","LEGACY_SNAPSHOT");version.put("bonus_bearer","ISSUER");
            version.put("currency","CNY");version.put("market_zone","Asia/Shanghai");version.put("collection_config",db.encode(List.of(route)));
            version.put("clearing_rule",db.encode(Map.of("rounding","LARGEST_REMAINDER","version",1)));
            version.put("entity_acceptance",db.encode(Map.of("source","LEGACY_STORE_SCOPE","actorNo",scope.actorNo(),"entityNo",scope.entityNo(),"storeNo",scope.storeNo(),"storeRevision",scope.storeRevision())));
            version.put("content_hash",hash(db.encode(List.of("LEGACY_STORE_DEFAULT",scope.entityNo(),scope.storeNo(),"CNY","Asia/Shanghai",route))));version.put("effective_at",at);
            db.insert("sv_issuer_version",version);
            issuer=base(scope,at);issuer.put("issuer_no",no);issuer.put("scope_type","STORE");issuer.put("scope_no",scope.storeNo());
            issuer.put("name","门店储值");issuer.put("status","DRAFT");issuer.put("current_issuer_version_no",versionNo);db.insert("sv_issuer",issuer);
            var access=base(scope,at);access.put("access_no",BizKey.next(BizKey.STORED_VALUE_ISSUER_ACCESS));access.put("issuer_no",no);
            access.put("store_no",scope.storeNo());access.put("operating_entity_no",scope.entityNo());access.put("source_binding_no",scope.storeNo());access.put("source_revision",scope.storeRevision());
            access.put("can_issue",0);access.put("can_redeem",0);access.put("status","PREPARING");access.put("effective_from",at);db.insert("sv_issuer_store_access",access);
            return current(scope,issuer);
        });
    }
    private View current(StoreAuthority scope,Map<String,Object> issuer) {
        var version=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",scope.tenantNo(),text(issuer,"issuer_no"),text(issuer,"current_issuer_version_no"));
        if(version==null||!scope.entityNo().equals(text(version,"settlement_entity_no"))||!"CNY".equals(text(version,"currency")))throw BizException.of(ErrorCode.CONFLICT);
        return new View(text(issuer,"issuer_no"),"STORE",scope.storeNo(),text(version,"name"),text(issuer,"status"),text(version,"issuer_version_no"),number(version,"version_index")-1,scope.entityNo(),"CNY",db.routes(text(version,"collection_config")));
    }
    private Map<String,Object> base(StoreAuthority scope,Timestamp at) {
        var row=new LinkedHashMap<String,Object>();row.put("tenant_no",scope.tenantNo());row.put("version",0);row.put("deleted",0);
        row.put("created_at",at);row.put("updated_at",at);row.put("created_by",scope.actorNo());row.put("updated_by",scope.actorNo());return row;
    }
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
