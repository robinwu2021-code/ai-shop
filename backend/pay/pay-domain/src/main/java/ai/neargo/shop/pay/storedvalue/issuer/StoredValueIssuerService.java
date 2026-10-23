package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Issuer identity is independent of its collection merchants and immutable configuration versions. */
@Service
public class StoredValueIssuerService {
    @org.springframework.beans.factory.annotation.Value("${shop.pay.stored-value.issuer-mode:false}")
    private boolean issuerMode;
    private final IssuerRepository db;
    public StoredValueIssuerService(IssuerRepository db) { this.db = db; }


    public ScopeRef locate(String tenant,String no) {
        var row=db.one("SELECT scope_type,scope_no FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND deleted=0",tenant,no);
        if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
        return new ScopeRef(text(row,"scope_type"),text(row,"scope_no"));
    }
    public ConfigurationImpact check(AuthorizedScope scope,String issuerNo,Config command) {
        validate(scope);validateConfig(scope,command);
        var row=issuer(scope,false);
        if(row==null||!issuerNo.equals(text(row,"issuer_no")))throw BizException.of(ErrorCode.NOT_FOUND);
        var previous=version(scope.tenantNo(),text(row,"current_issuer_version_no"));
        var blockers=new ArrayList<String>();
        if(command.expectedRevision()!=number(previous,"version_index")-1)blockers.add("STALE_CONFIGURATION");
        if(!command.currency().equals(text(previous,"currency")))blockers.add("CURRENCY_IMMUTABLE");
        if("FENCED".equals(text(row,"write_mode")))blockers.add("MAINTENANCE_IN_PROGRESS");
        long accounts=number(db.one("SELECT COUNT(*) AS n FROM sv_account WHERE tenant_no=? AND issuer_no=? AND deleted=0",scope.tenantNo(),issuerNo),"n");
        long liability=number(db.one("SELECT COUNT(*) AS n FROM sv_account WHERE tenant_no=? AND issuer_no=? AND deleted=0 AND (principal_minor>0 OR bonus_minor>0 OR held_principal_minor>0 OR held_bonus_minor>0)",scope.tenantNo(),issuerNo),"n");
        long pending=number(db.one("SELECT COUNT(*) AS n FROM sv_recharge WHERE tenant_no=? AND issuer_no=? AND deleted=0 AND status IN ('WAIT_COLLECTION','SUBMITTED','RECEIVED')",scope.tenantNo(),issuerNo),"n");
        if((liability>0||pending>0)&&(!command.settlementEntityNo().equals(text(previous,"settlement_entity_no"))||!command.clearingMode().equals(text(previous,"clearing_mode"))||!command.debitAllocationMode().equals(text(previous,"debit_allocation_mode"))))blockers.add("LIABILITY_RULES_LOCKED");
        return new ConfigurationImpact(blockers.isEmpty(),accounts,pending>0,blockers);
    }
    public StoreOptions storeOptions(StoreQuery query) {
        var rows=db.rows("SELECT i.*,v.currency,v.settlement_entity_no,a.can_issue,a.can_redeem,a.status AS access_status,a.operating_entity_no FROM sv_issuer i JOIN sv_issuer_version v ON v.tenant_no=i.tenant_no AND v.issuer_version_no=i.current_issuer_version_no AND v.issuer_no=i.issuer_no LEFT JOIN sv_issuer_store_access a ON a.tenant_no=i.tenant_no AND a.issuer_no=i.issuer_no AND a.store_no=i.scope_no AND a.deleted=0 WHERE i.tenant_no=? AND i.scope_type='STORE' AND i.scope_no=? AND i.deleted=0 AND v.deleted=0",query.tenantNo(),query.storeNo());
        if(rows.size()>1)throw BizException.of(ErrorCode.CONFLICT);
        boolean legacy=!issuerMode;
        var options=new ArrayList<StoreOption>();
        for(var row:rows) {
            if(!query.entityNo().equals(text(row,"settlement_entity_no"))||!query.entityNo().equals(text(row,"operating_entity_no")))throw BizException.of(ErrorCode.NOT_FOUND);
            boolean fenced="FENCED".equals(text(row,"write_mode"));
            boolean ready="ISSUER".equals(text(row,"write_mode"))&&"ACTIVE".equals(text(row,"access_status"));
            boolean issue=!fenced&&(legacy||ready&&"ACTIVE".equals(text(row,"status"))&&number(row,"can_issue")==1);
            boolean redeem=!fenced&&(legacy||ready&&Set.of("ACTIVE","ISSUE_STOPPED").contains(text(row,"status"))&&number(row,"can_redeem")==1);
            options.add(new StoreOption(text(row,"issuer_no"),"STORE",query.storeNo(),text(row,"name"),text(row,"currency"),text(row,"status"),text(row,"write_mode"),issue,redeem,issue?null:fenced?"MAINTENANCE_IN_PROGRESS":"ISSUANCE_NOT_ENABLED"));
        }
        return new StoreOptions(query.storeNo(),legacy,options);
    }

    public Optional<View> find(AuthorizedScope scope) {
        validate(scope);
        var row = issuer(scope, false);
        return row == null ? Optional.empty() : Optional.of(view(row, version(scope.tenantNo(), text(row,"current_issuer_version_no"))));
    }

    public View configure(AuthorizedScope scope, Config command, String key) {
        validate(scope);
        var routes = validateConfig(scope, command);
        requireText(key, 64);
        String contentHash = hash(db.encode(List.of(command.name().trim(), command.settlementEntityNo(),
                command.collectionMode(), command.clearingMode(), command.debitAllocationMode(), command.currency(),
                command.marketZone(), routes)));
        return db.write(() -> {
            var row = issuer(scope, true);
            if (row != null) {
                var prior = db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND configuration_key=?",
                        scope.tenantNo(), text(row,"issuer_no"), key);
                if (prior != null) {
                    if (!contentHash.equals(text(prior,"content_hash"))) throw BizException.of(ErrorCode.CONFLICT);
                    return view(row, prior);
                }
                var impact=check(scope,text(row,"issuer_no"),command);
                if(!impact.allowed())throw BizException.of(ErrorCode.CONFLICT);
                var previous = version(scope.tenantNo(), text(row,"current_issuer_version_no"));
                // The public revision identifies immutable configuration, not maintenance/status row writes.
                if (number(previous,"version_index") - 1 != command.expectedRevision()) throw BizException.of(ErrorCode.CONFLICT);
                if (!command.currency().equals(text(previous,"currency"))) throw BizException.of(ErrorCode.CONFLICT);
            } else if (command.expectedRevision() != 0) throw BizException.of(ErrorCode.CONFLICT);

            String issuerNo = row == null ? BizKey.next(BizKey.STORED_VALUE_ISSUER) : text(row,"issuer_no");
            String versionNo = BizKey.next(BizKey.STORED_VALUE_ISSUER_VERSION);
            long revision = row == null ? 0 : Math.addExact(number(row,"version"), 1);
            long versionIndex = row == null ? 1 : Math.addExact(number(version(scope.tenantNo(), text(row,"current_issuer_version_no")),"version_index"), 1);
            var config = base(scope);
            config.put("issuer_version_no",versionNo); config.put("issuer_no",issuerNo);
            config.put("name",command.name().trim()); config.put("configuration_key",key); config.put("version_index",versionIndex);
            config.put("settlement_entity_no",command.settlementEntityNo());
            config.put("collection_mode",command.collectionMode()); config.put("clearing_mode",command.clearingMode());
            config.put("debit_allocation_mode",command.debitAllocationMode()); config.put("bonus_bearer","ISSUER");
            config.put("currency",command.currency()); config.put("market_zone",command.marketZone());
            config.put("collection_config",db.encode(routes)); config.put("clearing_rule",db.encode(Map.of("rounding","LARGEST_REMAINDER","version",1)));
            config.put("entity_acceptance",db.encode(Map.of("actorNo",scope.actorNo(),"managementNo",scope.managementNo(),
                    "authorityRevision",scope.authorityRevision(),"entityNo",command.settlementEntityNo())));
            config.put("content_hash",contentHash); config.put("effective_at",Timestamp.from(Instant.now()));
            db.insert("sv_issuer_version", config);
            if (row == null) {
                row = base(scope); row.put("issuer_no",issuerNo); row.put("scope_type",scope.scopeType());
                row.put("scope_no",scope.scopeNo()); row.put("name",command.name().trim());
                row.put("status","DRAFT"); row.put("current_issuer_version_no",versionNo);
                db.insert("sv_issuer", row);
            } else {
                db.update("UPDATE sv_issuer SET name=?,current_issuer_version_no=?,updated_at=?,updated_by=?,version=? WHERE tenant_no=? AND issuer_no=?",
                        command.name().trim(),versionNo,Timestamp.from(Instant.now()),scope.actorNo(),revision,scope.tenantNo(),issuerNo);
                row = issuer(scope, false);
            }
            return view(row, config);
        });
    }

    /** Inspection only; execution must call requireIssuable and freeze this version on the original recharge. */
    public CollectionRoute collectionRoute(AuthorizedScope scope, String issuerNo, String channel, String scene, String store) {
        var issuer = owned(scope, issuerNo);
        if ("STORE".equals(scope.scopeType()) && !scope.scopeNo().equals(store)) throw BizException.of(ErrorCode.FORBIDDEN);
        var routes = issuer.routes().stream().filter(route -> channel.equals(route.channel()) && scene.equals(route.scene())
                && (route.storeNo() == null || route.storeNo().isBlank() || store.equals(route.storeNo()))
                && scope.authorizedRoutes().contains(route)).toList();
        if (routes.size() != 1) throw BizException.of(ErrorCode.CONFLICT);
        return new CollectionRoute(issuerNo, issuer.issuerVersionNo(), routes.getFirst());
    }

    public View requireIssuable(AuthorizedScope scope, String issuerNo) {
        var issuer = owned(scope, issuerNo);
        if (!"ACTIVE".equals(issuer.status()) || !"STORE".equals(issuer.scopeType()))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return issuer;
    }

    private View owned(AuthorizedScope scope, String no) {
        var issuer = find(scope).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (!issuer.issuerNo().equals(no)) throw BizException.of(ErrorCode.NOT_FOUND);
        return issuer;
    }
    private Map<String,Object> issuer(AuthorizedScope scope, boolean lock) {
        return db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND scope_type=? AND scope_no=? AND deleted=0"+(lock?" FOR UPDATE":""),
                scope.tenantNo(),scope.scopeType(),scope.scopeNo());
    }
    private Map<String,Object> version(String tenant, String no) {
        var version = db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_version_no=? AND deleted=0",tenant,no);
        if (version == null) throw BizException.of(ErrorCode.CONFLICT);
        return version;
    }
    private View view(Map<String,Object> issuer, Map<String,Object> version) {
        return new View(text(issuer,"issuer_no"),text(issuer,"scope_type"),text(issuer,"scope_no"),text(version,"name"),text(issuer,"status"),
                text(version,"issuer_version_no"),number(version,"version_index")-1,text(version,"settlement_entity_no"),text(version,"currency"),
                db.routes(text(version,"collection_config")),text(version,"collection_mode"),text(version,"clearing_mode"),text(version,"debit_allocation_mode"),text(version,"market_zone"));
    }
    private Map<String,Object> base(AuthorizedScope scope) {
        var row = new LinkedHashMap<String,Object>();
        row.put("tenant_no",scope.tenantNo()); row.put("version",0L); row.put("deleted",0);
        row.put("created_at",Timestamp.from(Instant.now())); row.put("updated_at",Timestamp.from(Instant.now()));
        row.put("created_by",scope.actorNo()); row.put("updated_by",scope.actorNo());
        return row;
    }
    private void validate(AuthorizedScope scope) {
        requireText(scope.tenantNo(),32); requireText(scope.actorNo(),64); requireText(scope.managementNo(),64); requireText(scope.scopeNo(),64);
        if (!Set.of("STORE","BRAND").contains(scope.scopeType()) || scope.authorityRevision()<0)
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private List<Route> validateConfig(AuthorizedScope scope, Config c) {
        requireText(c.name(),64); requireText(c.settlementEntityNo(),64);
        if (!scope.authorizedSettlementEntities().contains(c.settlementEntityNo())
                || "STORE".equals(scope.scopeType())&&!c.settlementEntityNo().equals(scope.operatingEntityNo()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        if (!Set.of("CENTRALIZED","STORE_LOCAL").contains(c.collectionMode())
                || !Set.of("PRINCIPAL_COMPONENT","INTERNAL_RECON").contains(c.clearingMode())
                || !Set.of("PRO_RATA","LEGACY_SNAPSHOT").contains(c.debitAllocationMode())
                || !"CNY".equals(c.currency()) || c.expectedRevision()<0 || c.routes().isEmpty() || c.routes().size()>50)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        try { ZoneId.of(c.marketZone()); } catch (RuntimeException invalid) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        var seen = new HashSet<String>();
        for (var route:c.routes()) {
            if (!scope.authorizedRoutes().contains(route) || !scope.authorizedSettlementEntities().contains(route.entityNo())
                    || "CENTRALIZED".equals(c.collectionMode())&&!c.settlementEntityNo().equals(route.entityNo())
                    || "STORE".equals(scope.scopeType())&&route.storeNo()!=null&&!route.storeNo().isBlank()&&!scope.scopeNo().equals(route.storeNo()))
                throw BizException.of(ErrorCode.FORBIDDEN);
            if (!Set.of("WECHAT","ALIPAY","MERCHANT_OFFLINE").contains(route.channel())) throw BizException.of(ErrorCode.BAD_REQUEST);
            if ("MERCHANT_OFFLINE".equals(route.channel())) {
                if (route.paymentMerchantNo()!=null || !"OFFLINE".equals(route.scene())) throw BizException.of(ErrorCode.BAD_REQUEST);
            } else {
                requireText(route.paymentMerchantNo(),64);
                if (!Set.of("JSAPI","APP","H5","NATIVE","MICROPAY","PAYCODE").contains(route.scene())) throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            if (!seen.add(route.channel()+"|"+route.scene()+"|"+Objects.toString(route.storeNo(),""))) throw BizException.of(ErrorCode.CONFLICT);
        }
        return c.routes().stream().sorted(Comparator.comparing(route -> route.channel()+"|"+route.scene()+"|"+Objects.toString(route.storeNo(),""))).toList();
    }
    private static void requireText(String text, int max) {
        if (text==null || text.isBlank() || text.length()>max) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
