package ai.neargo.shop.merchant.service;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.entity.MchStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** The two authenticated merchant create paths use this policy before inserting a new store. */
@Component
public class MerchantStoreCreation {
    public interface Session {
        ActorContext lockCreator(String quickStartOwnerNo);

        Optional<String> reserveBranch(
                ActorContext actor,
                String entity,
                StoreAdminService.CreateCommand command,
                String proposedStore);

        void forInsertedStore(InitializeStoreCommand command, Runnable initialize);
        default void forInsertedStore(InitializeStoreCommand command, String targetOwner, Runnable initialize) {
            forInsertedStore(command, initialize);
        }
    }

    private final boolean enabled;
    private final Optional<Session> session;
    private final Optional<StoreProvisioningFacade> provisioning;

    public MerchantStoreCreation(
            @Value("${commerce.store-creation.enabled:false}") boolean enabled,
            Optional<Session> session,
            Optional<StoreProvisioningFacade> provisioning) {
        this.enabled = enabled;
        this.session = session;
        this.provisioning = provisioning;
        if (enabled && (session.isEmpty() || provisioning.isEmpty()))
            throw new IllegalStateException(
                    "Store creation requires the embedded commerce module and host authority");
    }

    public ActorContext actor(String expectedOwner) {
        return enabled ? session.orElseThrow().lockCreator(expectedOwner) : null;
    }

    public Optional<String> reserveBranch(
            ActorContext actor,
            String entity,
            StoreAdminService.CreateCommand cmd,
            String proposedStore) {
        if (!enabled) return Optional.empty();
        if (cmd.operationNo() == null || !cmd.operationNo().matches("[A-Za-z0-9_.:-]{1,64}"))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        return session.orElseThrow().reserveBranch(actor, entity, cmd, proposedStore);
    }

    public void checkReplay(MchStore row, String type) {
        if (!enabled && row == null && type == null) return;
        if (row == null) throw BizException.of(ErrorCode.CONFLICT);
        if (!"STORE_V1".equals(row.getCommerceModel())) {
            if (type != null) throw BizException.of(ErrorCode.STORE_COMMERCE_MIGRATION_REQUIRED);
        } else if (!row.getBusinessTypeCode().equals(type))
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    public void prepare(MchStore row, ActorContext actor, String type) {
        if (!enabled) {
            if (type != null) throw BizException.of(ErrorCode.BAD_REQUEST);
            row.setCommerceModel("LEGACY");
            return;
        }
        if (actor == null || type == null || !type.matches("[A-Za-z0-9_.:-]{1,32}"))
            throw BizException.of(ErrorCode.STORE_BUSINESS_TYPE_REQUIRED);
        row.setCommerceModel("STORE_V1");
        row.setBusinessTypeCode(type);
        row.setBusinessTypeChangedAt(System.currentTimeMillis());
    }

    public void inserted(MchStore row, ActorContext actor) { inserted(row,actor,null); }

    public void inserted(MchStore row, ActorContext actor, String targetOwner) {
        if (!enabled) return;
        var command =
                new InitializeStoreCommand(
                        new StoreRef(actor.tenantNo(), row.getStoreNo()),
                        row.getBusinessTypeCode(),
                        row.getStoreNo());
        try {
            session.orElseThrow()
                    .forInsertedStore(
                            command, targetOwner,
                            () -> provisioning.orElseThrow().initializeFree(actor, command));
        } catch (BusinessTypeUnavailableException unavailable) {
            throw BizException.of(ErrorCode.STORE_BUSINESS_TYPE_REQUIRED);
        }
    }

    public void requireExplicitCreation() {
        if (enabled) throw BizException.of(ErrorCode.STORE_BUSINESS_TYPE_REQUIRED);
    }
}
