package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;

import ai.neargo.shop.commerce.dto.ProductProfileVO;
import ai.neargo.shop.commerce.dto.ProviderVO;
import ai.neargo.shop.commerce.dto.SaveProductProfileReq;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimProvider;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ProviderService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.ChannelProductProfilePort;
import ai.neargo.shop.spi.commerce.ChannelProductProfilePort.Definition;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 品牌与产品查询采用数据库分页，状态原样展示不推断真实网关可用。 */
public class ProviderServiceImpl implements ProviderService {
    private Supplier<List<ChannelProductProfilePort>> protocols = List::of;

    /**
     * Lazy installed-code discovery does not treat a provider database row as an approved protocol
     * implementation.
     */
    public void protocols(Supplier<List<ChannelProductProfilePort>> available) {
        protocols = available;
    }

    private final ProviderMapper providers;
    private final ProductProfileMapper profiles;
    private final CommerceCommands commands;

    public ProviderServiceImpl(
            ProviderMapper providers, ProductProfileMapper profiles, CommerceCommands commands) {
        this.providers = providers;
        this.profiles = profiles;
        this.commands = commands;
    }

    @Override
    public PageData<ProviderVO> listProviders(int page, int size) {
        long offset = CommerceValidation.offset(page, size);
        return commands.query(
                () -> {
                    var query = Wrappers.<CimProvider>query().eq("tenant_no", "MAIN");
                    long total = providers.selectCount(query);
                    var records =
                            providers
                                    .selectList(
                                            query.orderByAsc("provider_code", "id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(
                                            row ->
                                                    new ProviderVO(
                                                            row.getProviderNo(),
                                                            row.getProviderCode(),
                                                            row.getName(),
                                                            row.getStatus()))
                                    .toList();
                    return PageData.of(records, total, page, size);
                });
    }

    @Override
    public PageData<ProductProfileVO> listProfiles(String providerNo, int page, int size) {
        CommerceValidation.text(providerNo, 32);
        long offset = CommerceValidation.offset(page, size);
        return commands.query(
                () -> {
                    var query =
                            Wrappers.<CimProductProfile>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("provider_no", providerNo);
                    long total = profiles.selectCount(query);
                    var records =
                            profiles
                                    .selectList(
                                            query.orderByAsc("market", "product_code", "id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(
                                            row ->
                                                    new ProductProfileVO(
                                                            row.getProfileNo(),
                                                            row.getProviderNo(),
                                                            row.getProductCode(),
                                                            row.getMarket(),
                                                            row.getProtocolCode(),
                                                            row.getStatus(),
                                                            row.getVersion()))
                                    .toList();
                    return PageData.of(records, total, page, size);
                });
    }

    private record Target(String profileNo, Object request) {}

    /**
     * Protocol identity/capability fields remain immutable; returning VERIFIED requires proof from
     * installed code.
     */
    @Override
    public ProductProfileVO saveProfile(
            String no, String actor, SaveProductProfileReq request, String key) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw BizException.of(ErrorCode.CONFLICT);
        CommerceValidation.text(no, 32);
        CommerceValidation.text(actor, 32);
        if (request == null
                || request.version() == null
                || request.version() < 0
                || request.status() == null
                || !Set.of("PLANNED", "SUSPENDED", "VERIFIED").contains(request.status()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        CommerceValidation.text(request.reason(), 256);
        var target = new Target(no, request);
        var replay =
                commands.replayPlatform(
                        actor, "PROFILE_STATUS:" + no, key, target, ProductProfileVO.class);
        if (replay.isPresent()) return replay.get();
        var before = commands.query(() -> profile(no, false));
        var definition = commands.query(() -> definition(before));
        var proof = "VERIFIED".equals(request.status()) ? proof(definition) : null;
        return commands.executePlatform(
                actor,
                "PROFILE_STATUS:" + no,
                key,
                target,
                ProductProfileVO.class,
                () -> {
                    var current = profile(no, true);
                    if (!request.version().equals(current.getVersion())
                            || !definition.equals(definition(current)))
                        throw BizException.of(ErrorCode.CONFLICT);
                    if (proof != null && !proof.validUntil().isAfter(Instant.now()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    int changed =
                            profiles.update(
                                    Wrappers.<CimProductProfile>update()
                                            .eq("tenant_no", "MAIN")
                                            .eq("profile_no", no)
                                            .eq("version", request.version())
                                            .set("status", request.status())
                                            .set("version", request.version() + 1)
                                            .set("updated_by", actor)
                                            .set("updated_at", CatalogPersistence.now()));
                    if (changed != 1) throw BizException.of(ErrorCode.CONFLICT);
                    return view(profile(no, false));
                },
                ProductProfileVO::profileNo);
    }

    /**
     * Only an explicit non-production bootstrap invokes this; existing disabled profiles are never
     * auto-enabled.
     */
    public ProductProfileVO registerMock(
            ChannelProductProfilePort.Registration registration, String key) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || registration == null)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var definition = registration.definition();
        validateDefinition(definition);
        if (!definition.mock()
                || !"MOCK".equals(definition.providerCode())
                || !definition.profileNo().startsWith("MOCK")
                || !definition.protocolCode().startsWith("MOCK")
                || !definition.identityNamespace().startsWith("MOCK:"))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        CommerceValidation.text(registration.providerName(), 128);
        var replay =
                commands.replayPlatform(
                        "SYSTEM",
                        "REGISTER_MOCK_PROFILE",
                        key,
                        registration,
                        ProductProfileVO.class);
        if (replay.isPresent()) return replay.get();
        var proof = proof(definition);
        return commands.executePlatform(
                "SYSTEM",
                "REGISTER_MOCK_PROFILE",
                key,
                registration,
                ProductProfileVO.class,
                () -> {
                    if (!proof.validUntil().isAfter(Instant.now()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    var provider =
                            providers.selectOne(
                                    Wrappers.<CimProvider>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("provider_code", definition.providerCode())
                                            .last("FOR UPDATE"));
                    if (provider == null) {
                        provider = audit(new CimProvider(), "SYSTEM");
                        provider.setProviderNo(BizKey.next("CIPV"));
                        provider.setProviderCode(definition.providerCode());
                        provider.setName(registration.providerName());
                        provider.setStatus("ACTIVE");
                        providers.insert(provider);
                    }
                    if (!"ACTIVE".equals(provider.getStatus()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    var existing =
                            profiles.selectOne(
                                    Wrappers.<CimProductProfile>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("profile_no", definition.profileNo())
                                            .last("FOR UPDATE"));
                    if (existing != null) {
                        if (!sameDefinition(definition, definition(existing)))
                            throw BizException.of(ErrorCode.CONFLICT);
                        return view(existing);
                    }
                    var row = audit(new CimProductProfile(), "SYSTEM");
                    row.setProfileNo(definition.profileNo());
                    row.setProviderNo(provider.getProviderNo());
                    row.setProductCode(definition.productCode());
                    row.setMarket(definition.market());
                    row.setProtocolCode(definition.protocolCode());
                    row.setCapabilityDefinition(definition.capabilityDefinition());
                    row.setIdentitySchemaVersion(definition.identitySchemaVersion());
                    row.setIdentityNamespace(definition.identityNamespace());
                    row.setStoreIdentityScope(definition.accountScoped() ? "ACCOUNT" : "GLOBAL");
                    row.setStatus("VERIFIED");
                    profiles.insert(row);
                    return view(row);
                },
                ProductProfileVO::profileNo);
    }

    private ChannelProductProfilePort.Proof proof(Definition definition) {
        validateDefinition(definition);
        var matching =
                protocols.get().stream()
                        .filter(port -> port.profileNo().equals(definition.profileNo()))
                        .toList();
        if (matching.size() != 1) throw BizException.of(ErrorCode.CONFLICT);
        var result = matching.getFirst().verifyDefinition(definition);
        var now = Instant.now();
        if (result == null
                || result.status() != ChannelProductProfilePort.Status.VERIFIED
                || !definition.equals(result.definition())
                || result.verifiedAt() == null
                || result.verifiedAt().isAfter(now)
                || result.validUntil() == null
                || !result.validUntil().isAfter(now)
                || result.validUntil().isAfter(result.verifiedAt().plusSeconds(30)))
            throw BizException.of(ErrorCode.CONFLICT);
        CommerceValidation.text(result.originalReceiptRef(), 256);
        return result;
    }

    private Definition definition(CimProductProfile row) {
        var provider =
                providers.selectOne(
                        Wrappers.<CimProvider>query()
                                .eq("tenant_no", "MAIN")
                                .eq("provider_no", row.getProviderNo()));
        if (provider == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!Set.of("GLOBAL", "ACCOUNT").contains(row.getStoreIdentityScope()))
            throw BizException.of(ErrorCode.CONFLICT);
        var json = new ObjectMapper();
        var capabilities = json.readTree(row.getCapabilityDefinition());
        return new Definition(
                row.getProfileNo(),
                provider.getProviderCode(),
                row.getProductCode(),
                row.getMarket(),
                row.getProtocolCode(),
                row.getCapabilityDefinition(),
                row.getIdentitySchemaVersion(),
                row.getIdentityNamespace(),
                "ACCOUNT".equals(row.getStoreIdentityScope()),
                capabilities.path("mock").isBoolean() && capabilities.path("mock").asBoolean());
    }

    private boolean sameDefinition(Definition first, Definition second) {
        var json = new ObjectMapper();
        return first.profileNo().equals(second.profileNo())
                && first.providerCode().equals(second.providerCode())
                && first.productCode().equals(second.productCode())
                && first.market().equals(second.market())
                && first.protocolCode().equals(second.protocolCode())
                && first.identitySchemaVersion() == second.identitySchemaVersion()
                && first.identityNamespace().equals(second.identityNamespace())
                && first.accountScoped() == second.accountScoped()
                && first.mock() == second.mock()
                && json.readTree(first.capabilityDefinition())
                        .equals(json.readTree(second.capabilityDefinition()));
    }

    private void validateDefinition(Definition definition) {
        if (definition == null || definition.identitySchemaVersion() != 1)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        CommerceValidation.text(definition.profileNo(), 32);
        CommerceValidation.text(definition.providerCode(), 32);
        CommerceValidation.text(definition.productCode(), 32);
        CommerceValidation.text(definition.market(), 8);
        CommerceValidation.text(definition.protocolCode(), 64);
        CommerceValidation.text(definition.identityNamespace(), 128);
        if (definition.capabilityDefinition() == null
                || definition.capabilityDefinition().length() > 8192
                || !new ObjectMapper().readTree(definition.capabilityDefinition()).isObject())
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private CimProductProfile profile(String no, boolean lock) {
        var q = Wrappers.<CimProductProfile>query().eq("tenant_no", "MAIN").eq("profile_no", no);
        if (lock) q.last("FOR UPDATE");
        var row = profiles.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private static ProductProfileVO view(CimProductProfile row) {
        return new ProductProfileVO(
                row.getProfileNo(),
                row.getProviderNo(),
                row.getProductCode(),
                row.getMarket(),
                row.getProtocolCode(),
                row.getStatus(),
                row.getVersion());
    }
}
