package ai.neargo.shop.pay.storedvalue.issuer.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueStoreIssuerService;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AccessAck;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Route;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;



/** Preparation only: preserve the existing store's liability, never activate or merge accounts. */
@Service("storedValueStoreIssuerService")
public class StoredValueStoreIssuerServiceImpl implements StoredValueStoreIssuerService {
    private final IssuerRepository db;
    public StoredValueStoreIssuerServiceImpl(IssuerRepository db){this.db=db;}

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

    public View refresh(StoreAuthority scope) {
        return db.write(()->{
            var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND scope_type='STORE' AND scope_no=? AND deleted=0 FOR UPDATE",scope.tenantNo(),scope.storeNo());
            if(issuer==null)throw BizException.of(ErrorCode.NOT_FOUND);
            if("FENCED".equals(text(issuer,"write_mode")))throw BizException.of(ErrorCode.CONFLICT);
            var view=current(scope,issuer);
            var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0 FOR UPDATE",scope.tenantNo(),view.issuerNo(),scope.storeNo());
            if(access==null||!scope.entityNo().equals(text(access,"operating_entity_no"))||!scope.storeNo().equals(text(access,"source_binding_no"))||number(access,"source_revision")>scope.storeRevision())throw BizException.of(ErrorCode.CONFLICT);
            if(number(access,"source_revision")!=scope.storeRevision())db.update("UPDATE sv_issuer_store_access SET source_revision=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0",scope.storeRevision(),Timestamp.from(Instant.now()),scope.actorNo(),scope.tenantNo(),view.issuerNo(),scope.storeNo());
            return view;
        });
    }

    public AccessAck refreshAck(StoreAuthority scope){
        return db.write(()->{
            var issuer=refresh(scope);
            var access=db.one("SELECT source_revision,status FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0",scope.tenantNo(),issuer.issuerNo(),scope.storeNo());
            return new AccessAck(issuer.issuerNo(),scope.storeNo(),number(access,"source_revision"),text(access,"status"));
        });
    }
    private View current(StoreAuthority scope,Map<String,Object> issuer) {
        var version=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",scope.tenantNo(),text(issuer,"issuer_no"),text(issuer,"current_issuer_version_no"));
        if(version==null||!scope.entityNo().equals(text(version,"settlement_entity_no"))||!"CNY".equals(text(version,"currency")))throw BizException.of(ErrorCode.CONFLICT);
        return new View(text(issuer,"issuer_no"),"STORE",scope.storeNo(),text(version,"name"),text(issuer,"status"),text(version,"issuer_version_no"),number(version,"version_index")-1,scope.entityNo(),"CNY",db.routes(text(version,"collection_config")),text(version,"collection_mode"),text(version,"clearing_mode"),text(version,"debit_allocation_mode"),text(version,"market_zone"));
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
