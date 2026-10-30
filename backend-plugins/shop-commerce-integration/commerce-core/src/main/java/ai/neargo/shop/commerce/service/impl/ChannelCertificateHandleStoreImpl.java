package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.entity.CimCertificateHandle;
import ai.neargo.shop.commerce.mapper.CertificateHandleMapper;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;
import ai.neargo.shop.spi.user.MerchantQueryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.function.Supplier;

/**
 * The handle owner binds original encrypted material to exactly one actual
 * merchant/store/connection.
 */
public final class ChannelCertificateHandleStoreImpl implements ChannelCertificateHandleStore {
    private final CatalogPersistence db;
    private final CertificateHandleMapper handles;
    private final Supplier<MerchantQueryPort> merchantOwner;

    public ChannelCertificateHandleStoreImpl(
            CatalogPersistence db,
            CertificateHandleMapper handles,
            Supplier<MerchantQueryPort> merchantOwner) {
        this.db = db;
        this.handles = handles;
        this.merchantOwner = merchantOwner;
    }

    @Override
    public Handle prepare(Scope scope, String actor) {
        require(scope != null);
        text(actor, 32);
        var owner = merchantOwner.get();
        if (owner == null)
            throw new UnsupportedOperationException(
                    "Original merchant owner authority unavailable");
        require(owner.ownerUserNoOf(scope.entityNo()).filter(actor::equals).isPresent());
        return db.commands.atomic(
                () -> {
                    var connection =
                            db.connection(
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    scope.connectionNo(),
                                    true,
                                    false);
                    require(
                            "ACTIVE".equals(connection.account().getStatus())
                                    && "ACTIVE".equals(connection.connection().getStatus()));
                    var row = audit(new CimCertificateHandle(), actor);
                    row.setHandleNo(BizKey.next("CICH"));
                    row.setEntityNo(scope.entityNo());
                    row.setStoreNo(scope.storeNo());
                    row.setConnectionNo(scope.connectionNo());
                    row.setAccountNo(connection.account().getAccountNo());
                    row.setProfileNo(connection.connection().getProfileNo());
                    row.setStatus("AWAITING_SEAL");
                    handles.insert(row);
                    return view(row);
                });
    }

    @Override
    public Handle attach(Scope scope, String number, long encryptedBlobId) {
        require(encryptedBlobId > 0);
        return db.commands.atomic(
                () -> {
                    var c =
                            db.connection(
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    scope.connectionNo(),
                                    true,
                                    false);
                    var row = row(scope, number, true);
                    require(
                            row.getAccountNo().equals(c.account().getAccountNo())
                                    && row.getProfileNo().equals(c.connection().getProfileNo())
                                    && "AWAITING_SEAL".equals(row.getStatus())
                                    && row.getEncryptedBlobId() == null);
                    require(
                            handles.update(
                                            Wrappers.<CimCertificateHandle>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", row.getId())
                                                    .eq("status", "AWAITING_SEAL")
                                                    .isNull("encrypted_blob_id")
                                                    .set("encrypted_blob_id", encryptedBlobId)
                                                    .set("status", "SEALED")
                                                    .setSql("version=version+1"))
                                    == 1);
                    row.setEncryptedBlobId(encryptedBlobId);
                    row.setStatus("SEALED");
                    return view(row);
                });
    }

    @Override
    public Handle read(Scope scope, String number) {
        return db.commands.query(
                () -> {
                    var c =
                            db.connection(
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    scope.connectionNo(),
                                    false,
                                    false);
                    var row = row(scope, number, false);
                    require(
                            "SEALED".equals(row.getStatus())
                                    && row.getEncryptedBlobId() != null
                                    && row.getAccountNo().equals(c.account().getAccountNo())
                                    && row.getProfileNo().equals(c.connection().getProfileNo()));
                    return view(row);
                });
    }

    private CimCertificateHandle row(Scope scope, String number, boolean lock) {
        text(number, 32);
        var query =
                Wrappers.<CimCertificateHandle>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", scope.entityNo())
                        .eq("store_no", scope.storeNo())
                        .eq("connection_no", scope.connectionNo())
                        .eq("handle_no", number);
        if (lock) query.last("FOR UPDATE");
        var row = handles.selectOne(query);
        require(row != null);
        return row;
    }

    private Handle view(CimCertificateHandle row) {
        return new Handle(
                row.getHandleNo(),
                row.getId(),
                new Scope(row.getEntityNo(), row.getStoreNo(), row.getConnectionNo()),
                row.getAccountNo(),
                row.getProfileNo(),
                row.getEncryptedBlobId(),
                row.getStatus());
    }
}
