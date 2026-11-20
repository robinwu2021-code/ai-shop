package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.InitializeStoreCommand;
import ai.neargo.commerce.core.subscription.StoreProvisioningService;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.service.MerchantStoreCreation;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.sql.DataSource;

/** Resolves the validated B-app account and reads membership on the host transaction connection. */
final class HostStoreCreationAuthority
        implements MerchantStoreCreation.Session, StoreProvisioningService.CreationAuthority {
    private final Object creationScope = new Object();
    private record Creation(InitializeStoreCommand command, String targetOwner) {}
    private final DataSource ds;
    private final JdbcTemplate jdbc;
    private final java.util.Map<String, String> currencies;
    private final PermChecker operatorPermissions;

    HostStoreCreationAuthority(DataSource ds, java.util.Map<String, String> currencies, PermChecker operatorPermissions) {
        this.operatorPermissions=operatorPermissions;
        this.ds = ds;
        jdbc = new JdbcTemplate(ds);
        this.currencies = java.util.Map.copyOf(currencies);
    }

    private ActorContext authenticated() {
        var user = SecurityUtils.requireUser();
        boolean operator=user.realm()==Realm.OPERATOR && user.subjectKind()==ai.neargo.auth.store.SubjectKind.OPS;
        if (!"MAIN".equals(user.tenantNo()) || (operator
                ? !operatorPermissions.can(Perms.MERCHANT_APPLY_AUDIT)
                : user.realm()!=Realm.MERCHANT || (user.subjectKind()!=ai.neargo.auth.store.SubjectKind.USR
                        && user.subjectKind()!=ai.neargo.auth.store.SubjectKind.MCH)))
            throw BizException.of(ErrorCode.FORBIDDEN);
        String token =
                SecurityUtils.currentToken()
                        .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
        try {
            String fingerprint =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(token.getBytes(StandardCharsets.UTF_8)));
            return new ActorContext(
                    user.tenantNo(), operator ? SubjectKind.OPERATOR : SubjectKind.ACCOUNT, user.userNo(), fingerprint);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public ActorContext lockCreator(String expectedUserNo) {
        var actor = authenticated();
        if (actor.subjectKind()==SubjectKind.OPERATOR) {
            if (expectedUserNo==null) throw BizException.of(ErrorCode.FORBIDDEN);
            JdbcWriteGuard.require(ds,actor.tenantNo());
            var staff=jdbc.queryForList("SELECT staff_no FROM sys_ops_staff WHERE tenant_no=? AND staff_no=? AND status='ACTIVE' AND deleted=0 FOR SHARE",
                    String.class,actor.tenantNo(),actor.principalNo());
            var owner=jdbc.queryForList("SELECT user_no FROM usr_account WHERE tenant_no=? AND user_no=? AND status='NORMAL' AND deleted=0 FOR UPDATE",
                    String.class,actor.tenantNo(),expectedUserNo);
            if (staff.size()!=1 || owner.size()!=1) throw BizException.of(ErrorCode.FORBIDDEN);
            return actor;
        }
        boolean userAccount =
                SecurityUtils.requireUser().subjectKind() == ai.neargo.auth.store.SubjectKind.USR;
        if (expectedUserNo != null && (!userAccount || !expectedUserNo.equals(actor.principalNo())))
            throw BizException.of(ErrorCode.FORBIDDEN);
        JdbcWriteGuard.require(ds, actor.tenantNo());
        var rows =
                jdbc.queryForList(
                        userAccount
                                ? "SELECT user_no FROM usr_account WHERE tenant_no=? AND user_no=?"
                                        + " AND status='NORMAL' AND deleted=0 FOR UPDATE"
                                : "SELECT mch_account_no FROM mch_account WHERE tenant_no=? AND"
                                        + " mch_account_no=? AND status='ACTIVE' AND is_owner=1 AND"
                                        + " deleted=0 FOR UPDATE",
                        String.class,
                        actor.tenantNo(),
                        actor.principalNo());
        if (rows.size() != 1) throw BizException.of(ErrorCode.FORBIDDEN);
        return actor;
    }

    public java.util.Optional<String> reserveBranch(
            ActorContext actor,
            String entity,
            ai.neargo.shop.merchant.service.StoreAdminService.CreateCommand command,
            String proposedStore) {
        JdbcWriteGuard.require(ds, actor.tenantNo());
        if (!authenticated().equals(actor)) throw BizException.of(ErrorCode.FORBIDDEN);
        var owner =
                jdbc.queryForList(
                        """
SELECT m.entity_no FROM mch_entity m JOIN mch_account a ON a.tenant_no=m.tenant_no AND a.entity_no=m.entity_no
WHERE m.tenant_no=? AND m.entity_no=? AND m.deleted=0 AND %s=? AND a.is_owner=1 AND a.status='ACTIVE' AND a.deleted=0 FOR UPDATE
"""
                                .formatted(ownerKey()),
                        String.class,
                        actor.tenantNo(),
                        entity,
                        actor.principalNo());
        if (owner.size() != 1) throw BizException.of(ErrorCode.FORBIDDEN);
        String key =
                ai.neargo.commerce.core.spi.FactDigest.of(
                        java.util.List.of(
                                SecurityUtils.requireUser().subjectKind().name(),
                                actor.principalNo(),
                                entity,
                                command.operationNo()));
        var fields = new java.util.ArrayList<String>();
        java.util.Collections.addAll(
                fields, command.name(), command.address(), command.businessTypeCode());
        fields.add(
                command.categoryNos() == null
                        ? null
                        : Integer.toString(command.categoryNos().size()));
        if (command.categoryNos() != null) fields.addAll(command.categoryNos());
        try {
            return new ai.neargo.commerce.persistence.JdbcCommerceEventPersistence(ds)
                    .reserveStoreCreation(
                            actor.tenantNo(),
                            key,
                            ai.neargo.commerce.core.spi.FactDigest.of(fields),
                            proposedStore);
        } catch (
                ai.neargo.commerce.persistence.JdbcCommerceEventPersistence
                                .StoreCreationConflictException
                        conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private String ownerKey() {
        return SecurityUtils.requireUser().subjectKind() == ai.neargo.auth.store.SubjectKind.USR
                ? "a.user_no"
                : "a.mch_account_no";
    }

    public void forInsertedStore(InitializeStoreCommand command, Runnable initialize) {
        forInsertedStore(command,null,initialize);
    }

    public void forInsertedStore(InitializeStoreCommand command, String targetOwner, Runnable initialize) {
        JdbcWriteGuard.require(ds, command.store().tenantNo());
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(
                creationScope, new Creation(command,targetOwner));
        try {
            initialize.run();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .unbindResource(creationScope);
        }
    }

    public StoreProvisioningService.CreationFacts verify(
            ActorContext actor, InitializeStoreCommand cmd) {
        JdbcWriteGuard.require(ds, actor.tenantNo());
        var scope=(Creation)org.springframework.transaction.support.TransactionSynchronizationManager.getResource(creationScope);
        if (scope==null || !cmd.equals(scope.command()) || !authenticated().equals(actor))
            throw BizException.of(ErrorCode.FORBIDDEN);
        if (actor.subjectKind()==SubjectKind.OPERATOR) {
            if (scope.targetOwner()==null) throw BizException.of(ErrorCode.FORBIDDEN);
            var facts=jdbc.query("""
SELECT s.entity_no,s.business_type_code,m.market FROM mch_store s
JOIN mch_entity m ON m.tenant_no=s.tenant_no AND m.entity_no=s.entity_no AND m.deleted=0
JOIN mch_account a ON a.tenant_no=m.tenant_no AND a.entity_no=m.entity_no AND a.user_no=m.owner_user_no
JOIN usr_account u ON u.tenant_no=a.tenant_no AND u.user_no=a.user_no AND u.status='NORMAL' AND u.deleted=0
WHERE s.tenant_no=? AND s.store_no=? AND s.commerce_model='STORE_V1' AND s.status='ACTIVE' AND s.deleted=0
 AND s.ownership_change_no IS NULL AND m.ownership_change_no IS NULL AND a.user_no=?
 AND a.is_owner=1 AND a.status='ACTIVE' AND a.deleted=0 FOR SHARE
""",(r,i)->new StoreProvisioningService.CreationFacts(new StoreSnapshot(cmd.store(),r.getString(1),r.getString(2),
                    StoreStatus.ACTIVE,CommerceModel.STORE_V1),currencies.get(r.getString(3))),
                    actor.tenantNo(),cmd.store().storeNo(),scope.targetOwner());
            if (facts.size()!=1) throw BizException.of(ErrorCode.FORBIDDEN);
            return facts.getFirst();
        }
        var rows =
                jdbc.query(
                        """
SELECT s.entity_no,s.business_type_code,m.market FROM mch_store s
JOIN mch_entity m ON m.tenant_no=s.tenant_no AND m.entity_no=s.entity_no AND m.deleted=0
JOIN mch_account a ON a.tenant_no=s.tenant_no AND a.entity_no=s.entity_no
WHERE s.tenant_no=? AND s.store_no=? AND s.commerce_model='STORE_V1' AND s.status='ACTIVE'
  AND s.deleted=0 AND s.ownership_change_no IS NULL AND m.ownership_change_no IS NULL AND %s=? AND a.is_owner=1 AND a.status='ACTIVE' AND a.deleted=0 FOR SHARE
"""
                                .formatted(ownerKey()),
                        (r, i) ->
                                new StoreProvisioningService.CreationFacts(
                                        new StoreSnapshot(
                                                cmd.store(),
                                                r.getString(1),
                                                r.getString(2),
                                                StoreStatus.ACTIVE,
                                                CommerceModel.STORE_V1),
                                        currencies.get(r.getString(3))),
                        actor.tenantNo(),
                        cmd.store().storeNo(),
                        actor.principalNo());
        if (rows.size() != 1) throw BizException.of(ErrorCode.FORBIDDEN);
        return rows.getFirst();
    }
}
