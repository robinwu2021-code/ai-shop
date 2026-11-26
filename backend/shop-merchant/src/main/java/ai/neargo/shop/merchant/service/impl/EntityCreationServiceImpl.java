package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.EntityCreationService;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Locks the real personal account once, sharing the quota serialization used by commercial store creation. */
@Service
public class EntityCreationServiceImpl implements EntityCreationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MerchantAdminPort merchants;
    private final MerchantEntityService entities;
    private final MasterDataPort master;

    public EntityCreationServiceImpl(@Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate jdbc, ObjectMapper json, MerchantAdminPort merchants,
                                    MerchantEntityService entities, MasterDataPort master) {
        this.jdbc = jdbc;
        this.json = json;
        this.merchants = merchants;
        this.entities = entities;
        this.master = master;
    }

    private void personal(LoginUser user) {
        if (user.realm() != Realm.MERCHANT || user.subjectKind() != SubjectKind.USR)
            throw BizException.of(ErrorCode.FORBIDDEN);
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PLATFORM_TX_MANAGER)
    public Result create(LoginUser user, String requestNo, String name, String legalForm) {
        return create(user,requestNo,name,legalForm,null);
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PLATFORM_TX_MANAGER)
    public Result create(LoginUser user,String requestNo,String name,String legalForm,String initialProfileHash) {
        personal(user);
        var accounts = jdbc.queryForList("SELECT market FROM usr_account WHERE tenant_no=? AND user_no=? "
                + "AND status='NORMAL' AND deleted=0 FOR UPDATE", String.class, user.tenantNo(), user.userNo());
        if (accounts.size() != 1) throw BizException.of(ErrorCode.FORBIDDEN);
        // Preserve the original submitted intent, even after canonical master data changes.
        String hash = StoreContentVersion.hash(json.writeValueAsString(initialProfileHash == null ? Arrays.asList(name, legalForm) : Arrays.asList(name, legalForm, initialProfileHash)));
        var prior = receipt(user, requestNo);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst()[0])) throw BizException.of(ErrorCode.CONFLICT);
            return current(user, requestNo, prior.getFirst()[1], true);
        }
        String canonical = legalForm == null ? null : master.canonicalSubject(legalForm);
        if (legalForm != null && canonical == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        String entityNo = merchants.createPendingEntity(user.userNo(), name.trim(), canonical, accounts.getFirst());
        jdbc.update("INSERT INTO mch_entity_creation_request "
                + "(tenant_no,user_no,request_no,intent_hash,entity_no,created_at,initial_profile_hash,profile_request_no) VALUES(?,?,?,?,?,?,?,?)",
                user.tenantNo(), user.userNo(), requestNo, hash, entityNo, System.currentTimeMillis(),initialProfileHash,initialProfileHash==null?null:"INIT-"+StoreContentVersion.hash(requestNo).substring(0,48));
        return current(user, requestNo, entityNo, false);
    }

    private List<String[]> receipt(LoginUser user, String requestNo) {
        return jdbc.query("SELECT intent_hash,entity_no,initial_profile_hash,profile_request_no FROM mch_entity_creation_request "
                + "WHERE tenant_no=? AND user_no=? AND request_no=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2),rs.getString(3),rs.getString(4)}, user.tenantNo(), user.userNo(), requestNo);
    }

    private Result current(LoginUser user, String requestNo, String entityNo, boolean replayed) {
        var row=receipt(user,requestNo).getFirst();
        return new Result(requestNo, entities.detail(user.userNo(), entityNo).entity(), replayed,row[2]==null?"NOT_REQUESTED":"PENDING",row[3]);
    }

    @Override
    public Result find(LoginUser user, String requestNo) {
        personal(user);
        var prior = receipt(user, requestNo);
        if (prior.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        return current(user, requestNo, prior.getFirst()[1], true);
    }
}
