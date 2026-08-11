package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchStoreAudit;
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

    /** 值域见 {@link ai.neargo.shop.common.ServiceScopes} —— 这里只是本类默认值的别名 */
    private static final String COMMUNITY = ai.neargo.shop.common.ServiceScopes.COMMUNITY;

    /** 配送半径默认 3km：「先跑起来再说」，而不是 0（那等于谁都送不到）。 */
    private static final int DEFAULT_RADIUS_M = 3000;

    private final MchStoreMapper storeMapper;
    private final MchEntityMapper merchantMapper;
    private final MchEntityCommunityMapper merchantCommunityMapper;
    private final ObjectMapper json;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.StoreAuditMapper storeAuditMapper;
    /** 敏感词表从平台参数取 —— 运营加词不该等发版 */
    private final ai.neargo.shop.spi.platform.SettingPort settingPort;
    /** 经营范围的值域与启用白名单归 platform 管，本域只问「这个值能不能用」 */
    private final ai.neargo.shop.spi.platform.MasterDataPort masterDataPort;

    public MerchantStoreServiceImpl(MchStoreMapper storeMapper, MchEntityMapper merchantMapper,
                                    MchEntityCommunityMapper merchantCommunityMapper,
                                    ObjectMapper json,
                                    ai.neargo.shop.merchant.mapper.MerchantMappers.StoreAuditMapper storeAuditMapper,
                                    ai.neargo.shop.spi.platform.SettingPort settingPort,
                                    ai.neargo.shop.spi.platform.MasterDataPort masterDataPort) {
        this.storeMapper = storeMapper;
        this.merchantMapper = merchantMapper;
        this.merchantCommunityMapper = merchantCommunityMapper;
        this.json = json;
        this.storeAuditMapper = storeAuditMapper;
        this.settingPort = settingPort;
        this.masterDataPort = masterDataPort;
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
        /*
         * 先过值域与一期启用白名单，再谈默认值。
         *
         * 此前这里是「为空给默认、非空原样存」—— 传 "ABC" 能写进库，
         * 之后按范围查商品会静默漏掉这家店：商家看到的是保存成功、商品在架、订单为零。
         * 与下面那条社区必填校验同一个形状的故障，只是那条已经拦了，这条没有。
         */
        masterDataPort.assertServiceScopeAllowed(cmd.serviceScope());
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
        /*
         * 公告过机审。**命中不是拒绝，是转人审** —— 词表总会误伤
         * （「最低价」可能出现在「不是最低价也保证新鲜」里），人审是纠偏的那一层。
         *
         * 没命中就直接生效：公告是店主自发的时效内容（「今日到货」），
         * 全部先审后发要等几小时，那等于这个功能没用。
         */
        List<String> hits = screen(cmd.announcement());
        if (!hits.isEmpty()) {
            submitForAudit(merchantNo, MchStoreAudit.NOTICE, cmd.announcement(), hits);
            // 命中期间**保留旧公告**：把它清空的话，店铺页会突然变白，
            // 而店主以为自己"改坏了"，只会反复再改一遍
        } else {
            store.setAnnouncement(cmd.announcement());
        }
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

    @Override
    public DeliveryRuleVO deliveryRule(String merchantNo, String storeNo) {
        MchStore st = storeOf(merchantNo, storeNo);
        // 没配过时返回**默认值**：端上拿 null 会渲染出四个空框，店主以为功能坏了
        if (st == null) {
            return new DeliveryRuleVO(DEFAULT_RADIUS_M, 0L, 0L, 0L);
        }
        return new DeliveryRuleVO(
                st.getDeliveryRadiusM() == null ? DEFAULT_RADIUS_M : st.getDeliveryRadiusM(),
                nz(st.getDeliveryMinOrderMinor()), nz(st.getDeliveryFeeMinor()),
                nz(st.getDeliveryFreeThresholdMinor()));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public DeliveryRuleVO saveDeliveryRule(String merchantNo, String storeNo, DeliveryRuleVO rule) {
        if (rule == null || rule.radius() <= 0
                || rule.minOrderMinor() < 0 || rule.feeMinor() < 0 || rule.freeThresholdMinor() < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 免配送费门槛低于起送价是**无意义配置**：起送价 30、满 20 免运费，
         * 意味着每一单都免运费 —— 店主以为自己设了门槛，实际等于把配送费关了。
         * 门槛为 0（不免）不在此列。
         */
        if (rule.freeThresholdMinor() > 0 && rule.freeThresholdMinor() < rule.minOrderMinor()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        MchStore st = storeOf(merchantNo, storeNo);
        if (st == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        st.setDeliveryRadiusM(rule.radius());
        st.setDeliveryMinOrderMinor(rule.minOrderMinor());
        st.setDeliveryFeeMinor(rule.feeMinor());
        st.setDeliveryFreeThresholdMinor(rule.freeThresholdMinor());
        DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(st));
        return rule;
    }

    /** 指定门店；storeNo 为空时落到主体的任意一家（单店商家的常态）。 */
    private MchStore storeOf(String merchantNo, String storeNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchantNo)
                        .eq(storeNo != null && !storeNo.isBlank(), MchStore::getStoreNo, storeNo)
                        .last("limit 1")));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
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

    // ---------------------------------------------------------------- 门面内容机审

    /** 敏感词表键。放在 sys_setting 里 —— 运营加词不该等发版。 */
    private static final String WORDS_KEY = "store.sensitive-words";
    private static final String WORDS_DEFAULT = "[]";

    /** @return 命中的词；空表示放行 */
    private List<String> screen(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> words;
        try {
            words = json.readValue(settingPort.get(WORDS_KEY, WORDS_DEFAULT),
                    new tools.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (RuntimeException e) {
            /*
             * 词表坏了就**放行**，不是拦下。
             * 拦下的话一行坏 JSON 会让全平台的公告都发不出去，而症状是「保存没反应」；
             * 放行的最坏情况是漏审几条，那由人审与举报兜底。
             */
            return List.of();
        }
        return words.stream().filter(w -> w != null && !w.isBlank() && text.contains(w)).toList();
    }

    private void submitForAudit(String merchantNo, String kind, String content, List<String> hits) {
        MchStoreAudit a = new MchStoreAudit();
        a.setAuditNo(BizKey.next(BizKey.STORE_AUDIT));
        a.setEntityNo(merchantNo);
        a.setKind(kind);
        a.setContent(content);
        a.setStatus(MchStoreAudit.PENDING);
        a.setHits(json.writeValueAsString(hits));
        a.setSubmittedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> storeAuditMapper.insert(a));
    }
}
