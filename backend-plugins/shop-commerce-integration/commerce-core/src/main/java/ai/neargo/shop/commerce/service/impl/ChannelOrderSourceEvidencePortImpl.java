package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimProvider;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.policy.ChannelOrderSourceEvidencePolicy;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.Clock;

/**
 * Reads are journaled before network access and bind the original immutable import to its exact
 * merchant/channel route.
 */
public final class ChannelOrderSourceEvidencePortImpl implements ChannelOrderSourceEvidencePort {
    private final ChannelOrderServiceImpl data;
    private final ChannelOrderMaintenanceService maintenance;
    private final ProviderMapper providers;
    private final HistoricalManagedMappingService history;

    public ChannelOrderSourceEvidencePortImpl(
            ChannelOrderServiceImpl data,
            ChannelOrderMaintenanceService maintenance,
            ProviderMapper providers,
            HistoricalManagedMappingService history) {
        this.data = data;
        this.maintenance = maintenance;
        this.providers = providers;
        this.history = history;
    }

    private record Original(
            CimOrderLink order, ChannelOrderIngestPort.Command command, Routing routing) {}

    @Override
    public Evidence read(Query query) {
        ChannelOrderSourceEvidencePolicy.query(query);
        var original = data.db.commands.query(() -> original(query));
        var read =
                maintenance.readOriginal(
                        query.entityNo(), query.storeNo(), original.order().getChannelOrderNo());
        return data.db.commands.query(
                () -> {
                    var current = original(query);
                    require(
                            current.command().equals(original.command())
                                    && current.routing().equals(original.routing())
                                    && !"CONFLICT".equals(current.order().getImportStatus()));
                    var connection =
                            data.db.connection(
                                    query.entityNo(),
                                    query.storeNo(),
                                    current.routing().connectionNo(),
                                    false,
                                    false);
                    var operation =
                            data.db.operations.selectOne(
                                    Wrappers.<CimOperation>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", query.entityNo())
                                            .eq("operation_no", read.operationNo()));
                    require(
                            operation != null
                                    && "SUCCEEDED".equals(operation.getStatus())
                                    && ("AUTH:" + connection.account().getActiveAuthorizationNo())
                                            .equals(operation.getRequestRef()));
                    var grant =
                            data.db.authorizations.selectOne(
                                    Wrappers.<CimAuthorizationRevision>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", query.entityNo())
                                            .eq("account_no", connection.account().getAccountNo())
                                            .eq(
                                                    "authorization_no",
                                                    connection
                                                            .account()
                                                            .getActiveAuthorizationNo()));
                    require(
                            grant != null
                                    && "GRANTED".equals(grant.getStatus())
                                    && (grant.getExpiresAt() == null
                                            || grant.getExpiresAt()
                                                    .isAfter(CatalogPersistence.now())));
                    var document = read.snapshot().document();
                    require(
                            original.command()
                                            .original()
                                            .document()
                                            .externalAccountRef()
                                            .equals(document.externalAccountRef())
                                    && original.command()
                                            .original()
                                            .document()
                                            .externalStoreRef()
                                            .equals(document.externalStoreRef())
                                    && original.order()
                                            .getExternalOrderRef()
                                            .equals(document.externalOrderRef()));
                    return ChannelOrderSourceEvidencePolicy.validate(
                            query,
                            new Evidence(
                                    query,
                                    original.command(),
                                    read.snapshot(),
                                    current.routing(),
                                    read.operationNo(),
                                    read.attemptRef()),
                            Clock.systemUTC());
                });
    }

    @Override
    public PublishedCatalog publishedCatalog(Query query) {
        ChannelOrderSourceEvidencePolicy.query(query);
        var original = data.db.commands.query(() -> original(query));
        return history.publishedCatalog(
                original.order(), original.command().original().document().catalogHash(), query);
    }

    private Original original(Query query) {
        var row =
                data.orders.selectOne(
                        Wrappers.<CimOrderLink>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", query.entityNo())
                                .eq("store_no", query.storeNo())
                                .eq("import_ref", query.importNo()));
        require(
                row != null
                        && query.owner().name().equals(row.getSourceOwner())
                        && row.getImportSnapshot() != null
                        && !"CONFLICT".equals(row.getImportStatus()));
        var command =
                data.db.json.readValue(
                        row.getImportSnapshot(), ChannelOrderIngestPort.Command.class);
        require(
                query.importNo().equals(command.importNo())
                        && query.entityNo().equals(command.entityNo())
                        && query.storeNo().equals(command.storeNo())
                        && query.originalDocumentHash().equals(command.original().documentHash())
                        && row.getConnectionNo().equals(command.connectionNo())
                        && row.getConnectionRevisionNo().equals(command.connectionRevisionNo()));
        var connection =
                data.db.connection(
                        query.entityNo(), query.storeNo(), row.getConnectionNo(), false, false);
        require(
                "ACTIVE".equals(connection.connection().getStatus())
                        && "ACTIVE".equals(connection.account().getStatus())
                        && row.getConnectionRevisionNo()
                                .equals(connection.connection().getActiveRevisionNo())
                        && query.owner().name().equals(connection.revision().getSourceOwner()));
        var profile =
                data.db.profiles.selectOne(
                        Wrappers.<CimProductProfile>query()
                                .eq("tenant_no", "MAIN")
                                .eq("profile_no", connection.connection().getProfileNo()));
        require(profile != null && "VERIFIED".equals(profile.getStatus()));
        var provider =
                providers.selectOne(
                        Wrappers.<CimProvider>query()
                                .eq("tenant_no", "MAIN")
                                .eq("provider_no", profile.getProviderNo()));
        require(provider != null && "ACTIVE".equals(provider.getStatus()));
        var document = command.original().document();
        require(
                document.externalAccountRef().equals(connection.account().getExternalAccountRef())
                        && document.externalStoreRef()
                                .equals(connection.connection().getExternalStoreRef()));
        return new Original(
                row,
                command,
                new Routing(
                        row.getConnectionNo(),
                        row.getConnectionRevisionNo(),
                        provider.getProviderCode(),
                        profile.getProfileNo(),
                        connection.connection().getChannelStoreKey(),
                        profile.getMarket()));
    }
}
