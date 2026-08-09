package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityCommunityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** {@link MerchantStoreService} 实现。 */
@Service
public class MerchantStoreServiceImpl implements MerchantStoreService {

    private static final String COMMUNITY = "COMMUNITY";

    private final MchStoreMapper storeMapper;
    private final MchEntityMapper merchantMapper;
    private final MchEntityCommunityMapper merchantCommunityMapper;
    private final ObjectMapper json;

    public MerchantStoreServiceImpl(MchStoreMapper storeMapper, MchEntityMapper merchantMapper,
                                    MchEntityCommunityMapper merchantCommunityMapper,
                                    ObjectMapper json) {
        this.storeMapper = storeMapper;
        this.merchantMapper = merchantMapper;
        this.merchantCommunityMapper = merchantCommunityMapper;
        this.json = json;
    }

    @Override
    public StoreProfileVO profile(String merchantNo) {
        MchStore store = row(merchantNo);
        MchEntity merchant = merchant(merchantNo);
        return new StoreProfileVO(
                store == null ? "" : nz(store.getAnnouncement()),
                store == null ? "" : nz(store.getOpenHours()),
                store == null ? "" : nz(store.getAddress()),
                store == null ? List.of() : readList(store.getFeatured()),
                merchant == null || merchant.getServiceScope() == null
                        ? COMMUNITY : merchant.getServiceScope(),
                communitiesOf(merchantNo),
                merchant == null ? null : merchant.getServiceCityCode());
    }

    @Override
    @Transactional
    public StoreProfileVO save(String merchantNo, SaveCommand cmd) {
        String scope = cmd.serviceScope() == null ? COMMUNITY : cmd.serviceScope();
        /*
         * ADR-009 的硬规则，与入驻审核那边同一条：范围选「仅本社区」却一个社区都没覆盖，
         * 等于这家店对谁都不可见 —— 而商家看到的是保存成功、商品在架、订单为零。
         * 这个故障没有任何报错，所以只能在写入口拦。
         */
        if (COMMUNITY.equals(scope)
                && (cmd.serviceCommunityNos() == null || cmd.serviceCommunityNos().isEmpty())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        MchStore store = row(merchantNo);
        if (store == null) {
            store = new MchStore();
            store.setEntityNo(merchantNo);
        }
        store.setAnnouncement(cmd.announcement());
        store.setOpenHours(cmd.openHours());
        store.setAddress(cmd.address());
        store.setFeatured(writeJson(cmd.featured()));
        MchStore toSave = store;
        DataScopeContext.executeWithoutScope(() ->
                toSave.getId() == null ? storeMapper.insert(toSave) : storeMapper.updateById(toSave));

        MchEntity merchant = merchant(merchantNo);
        if (merchant != null) {
            merchant.setServiceScope(scope);
            merchant.setServiceCityCode(cmd.serviceCityCode());
            /*
             * **不再往主体表回写地址与营业时间**（V42 已删那两列）。
             * 之前的双写是在给「两张表都有」这处重复打补丁 —— 而双写永远有漏的一天
             * （少一个入口、少一条分支），漏了之后症状是「设置里改了、详情页还是老的」，
             * 且不报错。现在门面表是唯一权威，C 端商家详情也从那里读。
             */
            DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(merchant));
        }
        syncCommunities(merchantNo, cmd.serviceCommunityNos());
        return profile(merchantNo);
    }

    /**
     * 覆盖社区：全量替换而不是追加 —— 勾选面板上的就是最终结果，追加会留下取消不掉的旧勾选。
     *
     * <p><b>按差集增删，不能"先全删再全插"</b>：{@code mch_entity_community} 是逻辑删除
     * （{@code deleted} 标记位），而唯一键 {@code uk_merchant_community(entity_no, community_no)}
     * <b>不含 deleted</b> —— 删掉的行还占着索引位，再插同一个社区就撞键。
     *
     * <p>这个坑最常见的触发场景恰恰是最普通的操作：<b>改公告但不动经营范围</b>。
     * 先前只有入驻审核会调它、一家店只调一次，所以一直没现形。
     */
    @Override
    @Transactional
    public void syncCommunities(String merchantNo, List<String> communityNos) {
        if (communityNos == null) {
            return;
        }
        List<String> current = communitiesOf(merchantNo);
        for (String gone : current.stream().filter(c -> !communityNos.contains(c)).toList()) {
            DataScopeContext.executeWithoutScope(() ->
                    merchantCommunityMapper.delete(Wrappers.<MchEntityCommunity>lambdaQuery()
                            .eq(MchEntityCommunity::getEntityNo, merchantNo)
                            .eq(MchEntityCommunity::getCommunityNo, gone)));
        }
        for (String added : communityNos.stream().filter(c -> !current.contains(c)).toList()) {
            MchEntityCommunity row = new MchEntityCommunity();
            row.setEntityNo(merchantNo);
            row.setCommunityNo(added);
            DataScopeContext.executeWithoutScope(() -> merchantCommunityMapper.insert(row));
        }
    }

    private List<String> communitiesOf(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                merchantCommunityMapper.selectList(Wrappers.<MchEntityCommunity>lambdaQuery()
                        .eq(MchEntityCommunity::getEntityNo, merchantNo))).stream()
                .map(MchEntityCommunity::getCommunityNo).toList();
    }

    private MchStore row(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchantNo).last("limit 1")));
    }

    private MchEntity merchant(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new tools.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
