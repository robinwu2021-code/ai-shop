package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.ProductProfileVO;
import ai.neargo.shop.commerce.dto.ProviderVO;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimProvider;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ProviderService;
import ai.neargo.shop.common.PageData;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/** 品牌与产品查询采用数据库分页，状态原样展示不推断真实网关可用。 */
public class ProviderServiceImpl implements ProviderService {
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
                                                            row.getStatus()))
                                    .toList();
                    return PageData.of(records, total, page, size);
                });
    }
}
