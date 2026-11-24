package ai.neargo.shop.merchant.service.impl;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.StoreCreationAccessService;
import ai.neargo.shop.merchant.service.StoreOpeningService;
import ai.neargo.shop.merchant.service.StoreAdminService;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreOpeningServiceImpl implements StoreOpeningService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MerchantAdminPort merchants;
    private final StoreAdminService stores;
    private final StoreCreationAccessService access;
    public StoreOpeningServiceImpl(JdbcTemplate jdbc, ObjectMapper json, MerchantAdminPort merchants,
                                   StoreAdminService stores, StoreCreationAccessService access) {
        this.jdbc=jdbc; this.json=json; this.merchants=merchants; this.stores=stores; this.access=access;
    }
    private static String actor(LoginUser user) { return user.subjectKind().name()+":"+user.userNo(); }
    @Override
    @Transactional
    public Receipt open(LoginUser user, String requestNo, String entityNo, String sourceStoreNo,
                        MerchantAdminPort.QuickStartCommand command) {
        String digest=StoreContentVersion.hash(json.writeValueAsString(new Intent(entityNo,sourceStoreNo,command)));
        jdbc.update("INSERT IGNORE INTO mch_store_opening_request(tenant_no,actor_key,request_no,intent_hash,created_at) VALUES(?,?,?,?,?)",
            user.tenantNo(),actor(user),requestNo,digest,System.currentTimeMillis());
        var rows=jdbc.query("SELECT intent_hash,entity_no,store_no FROM mch_store_opening_request WHERE tenant_no=? AND actor_key=? AND request_no=? FOR UPDATE",
            (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},user.tenantNo(),actor(user),requestNo);
        var row=rows.getFirst();
        if(!digest.equals(row[0])) throw BizException.of(ErrorCode.CONFLICT);
        if(row[2]!=null) return new Receipt(requestNo,row[1],row[2],"COMPLETED");
        var choices=access.access(user);
        String createdEntity, createdStore;
        if(entityNo==null) {
            if(!choices.canQuickStart() && !choices.canResumeQuickStart()) throw BizException.of(ErrorCode.FORBIDDEN);
            var result=merchants.quickStart(command);
            createdEntity=result.entityNo(); createdStore=result.storeNo();
        } else {
            boolean first = sourceStoreNo == null;
            if (first && user.subjectKind() != SubjectKind.USR) throw BizException.of(ErrorCode.FORBIDDEN);
            var candidates = first ? choices.firstStoreSources() : choices.sources();
            var source = candidates.stream()
                    .filter(s -> entityNo.equals(s.entityNo()) && Objects.equals(sourceStoreNo, s.storeNo()))
                    .findFirst().orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
            if (!source.market().equals(command.market())) throw BizException.of(ErrorCode.BAD_REQUEST);
            var fields = new StoreAdminService.CreateCommand(command.storeName(), command.address(),
                    command.businessTypeCode(), requestNo, null);
            var result = first ? stores.createFirst(entityNo, source.market(), user.userNo(), fields) : stores.create(entityNo, fields);
            createdEntity=entityNo; createdStore=result.storeNo();
            int updated=jdbc.update("UPDATE mch_store SET address_detail=?,industry_code=?,sub_industry_code=?,industry_text=?,sub_industry_text=?,lat_e6=?,lng_e6=?,coord_sys=? WHERE tenant_no=? AND entity_no=? AND store_no=? AND deleted=0",
                command.addressDetail(),command.industryCode(),command.subIndustryCode(),command.industryText(),command.subIndustryText(),
                command.latE6(),command.lngE6(),command.coordSys(),user.tenantNo(),createdEntity,createdStore);
            if(updated!=1) throw BizException.of(ErrorCode.CONFLICT);
        }
        if(createdStore==null) throw BizException.of(ErrorCode.CONFLICT);
        jdbc.update("UPDATE mch_store_opening_request SET entity_no=?,store_no=? WHERE tenant_no=? AND actor_key=? AND request_no=?",
            createdEntity,createdStore,user.tenantNo(),actor(user),requestNo);
        return new Receipt(requestNo,createdEntity,createdStore,"COMPLETED");
    }
    private record Intent(String entityNo,String sourceStoreNo,MerchantAdminPort.QuickStartCommand fields) {}
    @Override
    public Receipt find(LoginUser user,String requestNo) {
        var result=jdbc.query("SELECT entity_no,store_no FROM mch_store_opening_request WHERE tenant_no=? AND actor_key=? AND request_no=? AND store_no IS NOT NULL",
            (rs,n)->new Receipt(requestNo,rs.getString(1),rs.getString(2),"COMPLETED"),user.tenantNo(),actor(user),requestNo);
        if(result.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        return result.getFirst();
    }

}
