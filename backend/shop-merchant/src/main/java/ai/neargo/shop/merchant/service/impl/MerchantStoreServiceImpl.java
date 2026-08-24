package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchServiceArea;
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
    /** 覆盖项要显示成人能读的名字 —— 端上只拿到 330106 的话要么显示数字要么再查一次 */
    private final ai.neargo.shop.spi.user.CommunityQueryPort communityNamePort;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper;

    public MerchantStoreServiceImpl(MchStoreMapper storeMapper, MchEntityMapper merchantMapper,
                                    MchEntityCommunityMapper merchantCommunityMapper,
                                    ObjectMapper json,
                                    ai.neargo.shop.merchant.mapper.MerchantMappers.StoreAuditMapper storeAuditMapper,
                                    ai.neargo.shop.spi.platform.SettingPort settingPort,
                                    ai.neargo.shop.spi.platform.MasterDataPort masterDataPort,
                                    ai.neargo.shop.spi.user.CommunityQueryPort communityNamePort,
                                    ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper) {
        this.communityNamePort = communityNamePort;
        this.serviceAreaMapper = serviceAreaMapper;
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
        return profile(merchantNo, null);
    }

    @Override
    public StoreProfileVO profile(String merchantNo, String storeNo) {
        MchStore store = row(merchantNo, storeNo);
        MchEntity merchant = merchant(merchantNo);
        return new StoreProfileVO(
                // 过期即空：两条读路径（B 端这里、C 端 storeFront）走同一个判断
                store == null ? "" : store.effectiveAnnouncement(),
                store == null ? null : store.getAnnouncementUntil(),
                store == null ? List.of() : readList(store.getAnnouncementRecent()),
                pendingNotice(merchantNo, store == null ? null : store.getStoreNo()),
                store == null ? "" : nz(store.getOpenHours()),
                store == null ? "" : nz(store.getAddress()),
                store == null ? "" : nz(store.getAddressDetail()),
                store == null ? List.of() : readList(store.getFeatured()),
                merchant == null || merchant.getServiceScope() == null
                        ? COMMUNITY : merchant.getServiceScope(),
                communitiesOf(merchantNo),
                merchant == null ? null : merchant.getServiceCityCode(),
                merchant == null || merchant.getFulfillmentReach() == null
                        ? PICKUP : merchant.getFulfillmentReach(),
                areasOf(merchantNo),
                store == null ? null : store.getLatE6(),
                store == null ? null : store.getLngE6());
    }

    @Override
    @Transactional
    public StoreProfileVO saveAnnouncement(String merchantNo, String storeNo, String announcement, Long until) {
        MchStore store = row(merchantNo, storeNo);
        if (store == null) {
            // 还没建过门店就改公告：这条路只在异常状态下走得到，直接拒比默默建一行安全
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * 机审同 save()：命中转人审并**保留旧公告** —— 清空的话店铺页会突然变白，
         * 而店主以为自己改坏了，只会反复再改一遍。
         */
        List<String> hits = screen(announcement);
        if (!hits.isEmpty()) {
            submitForAudit(merchantNo, store.getStoreNo(), MchStoreAudit.NOTICE, announcement, hits, until);
            return profile(merchantNo, storeNo);
        }
        store.setAnnouncement(announcement);
        store.setAnnouncementUntil(until);
        store.setAnnouncementRecent(writeJson(pushRecent(store.getAnnouncementRecent(), announcement)));
        MchStore toSave = store;
        DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(toSave));
        return profile(merchantNo, storeNo);
    }

    @Override
    @Transactional
    public StoreProfileVO save(String merchantNo, SaveCommand cmd) {
        return save(merchantNo, null, cmd);
    }

    @Override
    @Transactional
    public StoreProfileVO save(String merchantNo, String storeNo, SaveCommand cmd) {
        /*
         * 先过值域与一期启用白名单，再谈默认值。
         *
         * 此前这里是「为空给默认、非空原样存」—— 传 "ABC" 能写进库，
         * 之后按范围查商品会静默漏掉这家店：商家看到的是保存成功、商品在架、订单为零。
         * 与下面那条社区必填校验同一个形状的故障，只是那条已经拦了，这条没有。
         */
        masterDataPort.assertServiceScopeAllowed(cmd.serviceScope());
        String reach = cmd.fulfillmentReach() == null || cmd.fulfillmentReach().isBlank()
                ? PICKUP : cmd.fulfillmentReach();
        /*
         * 「这家店对谁都不可见」这条硬规则要在**写入口**拦 —— 它没有任何报错，
         * 商家看到的是保存成功、商品在架、订单为零，自己永远查不出来。
         *
         * 新旧两个入口的表达方式不同，但拦的是同一件事：
         *   新（传了 serviceAreas）：PICKUP 且把覆盖项清空 —— 自提没有落点
         *   旧（只传 serviceScope）：scope=COMMUNITY 却一个社区都没配
         *
         * serviceAreas 为 null 表示「这次不改覆盖项」（老版本 b-app 不传这个字段），
         * 那就走旧校验；传了空列表才是明确的「清空」。
         */
        if (cmd.serviceAreas() != null) {
            if (PICKUP.equals(reach) && cmd.serviceAreas().isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        } else if (cmd.serviceScope() != null && COMMUNITY.equals(cmd.serviceScope())
                && (cmd.serviceCommunityNos() == null || cmd.serviceCommunityNos().isEmpty())) {
            // 只有**老入口明确传了 scope** 才走这条。两个字段都没传 = 这次不改覆盖范围，
            // 没什么可校验的 —— 拿默认值去拦，会让「只改一句公告」的保存被拒
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        MchStore store = row(merchantNo, storeNo);
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
            submitForAudit(merchantNo, store.getStoreNo(), MchStoreAudit.NOTICE, cmd.announcement(), hits,
                    cmd.announcementUntil());
            // 命中期间**保留旧公告**：把它清空的话，店铺页会突然变白，
            // 而店主以为自己"改坏了"，只会反复再改一遍
        } else {
            store.setAnnouncement(cmd.announcement());
            /*
             * 有效期跟着公告走：**换了内容就换有效期**，包括「这次没给 = 长期」。
             * 不这么做的话，上一条「今天有效」的到期时间会跟着新公告一起留下来，
             * 于是他刚发的长期公告在今晚零点悄悄消失。
             */
            store.setAnnouncementUntil(cmd.announcementUntil());
            store.setAnnouncementRecent(writeJson(pushRecent(store.getAnnouncementRecent(), cmd.announcement())));
        }
        store.setOpenHours(cmd.openHours());
        store.setAddress(cmd.address());
        /*
         * 门牌号 **null = 这次不改**（老版本端上不传这个字段），空串才是「清掉」。
         * 不分开的话，老 App 保存一次公告就会把商家填的门牌号抹掉，且那一下看不出来。
         */
        if (cmd.addressDetail() != null) {
            store.setAddressDetail(cmd.addressDetail().isBlank() ? null : cmd.addressDetail().trim());
        }
        store.setFeatured(writeJson(cmd.featured()));
        // 坐标两个都给才写：只来一半是端上的 bug，写进去会得到一个在赤道或本初子午线上的门店
        if (cmd.latE6() != null && cmd.lngE6() != null) {
            store.setLatE6(cmd.latE6());
            store.setLngE6(cmd.lngE6());
        }
        MchStore toSave = store;
        DataScopeContext.executeWithoutScope(() ->
                toSave.getId() == null ? storeMapper.insert(toSave) : storeMapper.updateById(toSave));

        MchEntity merchant = merchant(merchantNo);
        if (merchant != null) {
            /*
             * **service_scope 只在老入口写。**
             *
             * ADR-013 阶段二之后它是冻结的回滚锚点（迁移当时的快照），不做双写 ——
             * 双写在本仓库有前科：「少一个入口、少一条分支」，漏了之后
             * 「设置里改了、详情页还是老的」，且不报错。
             * 代价是回滚会丢掉切换之后的编辑，但不会产生错误的可见性 —— 这个方向是对的。
             */
            if (cmd.serviceScope() != null && !cmd.serviceScope().isBlank()) {
                merchant.setServiceScope(cmd.serviceScope());
            }
            merchant.setServiceCityCode(cmd.serviceCityCode());
            merchant.setFulfillmentReach(reach);
            /*
             * **不再往主体表回写地址与营业时间**（V42 已删那两列）。
             * 之前的双写是在给「两张表都有」这处重复打补丁 —— 而双写永远有漏的一天
             * （少一个入口、少一条分支），漏了之后症状是「设置里改了、详情页还是老的」，
             * 且不报错。现在门面表是唯一权威，C 端商家详情也从那里读。
             */
            DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(merchant));
        }
        syncCommunities(merchantNo, cmd.serviceCommunityNos());
        syncAreas(merchantNo, cmd.serviceAreas());
        return profile(merchantNo);
    }

    private static final String PICKUP = "PICKUP";
    private static final String AREA_COMMUNITY = "COMMUNITY";
    private static final String AREA_STREET = "STREET";

    /**
     * 覆盖项：**全量替换**，勾选面板上的就是最终结果。
     *
     * <p>与 {@link #syncCommunities} 不同，这里可以放心「先删再插」——
     * {@code mch_service_area} 走<b>物理删除</b>，没有墓碑行占着唯一索引位
     * （ADR-013 阶段二特意这么设计的，见 {@code MchServiceArea} 的说明）。
     * 那正是本仓库在另外四张表上打了四个 revive 补丁的那个坑。
     *
     * <p>{@code null} 表示端上这次不改覆盖项（老版本 b-app 就不会传）——
     * 与空列表要分开：空列表是「清空」，而清空对 PICKUP 商家意味着从 C 端消失。
     */
    private void syncAreas(String merchantNo, List<AreaCommand> areas) {
        if (areas == null) {
            return;
        }
        replaceAreas(merchantNo, areas, null);
    }

    /**
     * 替换覆盖项。{@code onlyLevel} 非空时**只替换那一层** ——
     * 老入口（只管社区）不该顺手把商家在新界面上勾的「西湖区」抹掉。
     */
    private void replaceAreas(String merchantNo, List<AreaCommand> areas, String onlyLevel) {
        List<MchServiceArea> current = DataScopeContext.executeWithoutScope(() ->
                serviceAreaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                        .eq(MchServiceArea::getEntityNo, merchantNo)
                        .eq(onlyLevel != null, MchServiceArea::getLevel, onlyLevel)));
        /*
         * 这里是全量删重插（唯一键在 entity+level+ref 上，改动最少的写法）。
         * 曾经要在这里保住旧的审核状态（PENDING/REJECTED 不能被删重插抹掉）——
         * 现在所有粒度都自选即生效，新插入的一律是 ACTIVE，不再需要记这份旧状态。
         */
        for (MchServiceArea old : current) {
            DataScopeContext.executeWithoutScope(() ->
                    serviceAreaMapper.hardDelete(merchantNo, old.getLevel(), old.getRefCode()));
        }
        for (AreaCommand a : normalize(areas)) {
            if (a == null || a.level() == null || a.refCode() == null || a.refCode().isBlank()) {
                continue;
            }
            MchServiceArea row = new MchServiceArea();
            row.setAreaNo(BizKey.next(BizKey.SERVICE_AREA));
            row.setEntityNo(merchantNo);
            row.setLevel(a.level());
            row.setRefCode(a.refCode());
            row.setSource("SELF");
            /*
             * **所有粒度自选即生效**（2026-08-24 起，取代 ADR-013 §4.2 的区/市送审规则）。
             *
             * 旧规则是「小区/村/街道自助，区/市/省要运营审」，理由是「一家菜摊声称
             * 覆盖整个西湖区，得有履约能力佐证」——这道闸现在拿掉了：拿掉之后，
             * 商家勾一个省会立刻对省内所有买家可见，履约能力不再有人工兜底核实，
             * 这是产品侧权衡后的决定，不是这段代码本身能挽回的取舍。
             *
             * 拿掉旧 PENDING 记录里可能还没处理完的那批：这次保存起，一律按 ACTIVE 写，
             * 旧的待审队列（MchStoreAudit，kind=SERVICE_AREA）不会再收到新记录；
             * 已经在队列里的历史记录不受影响，运营该怎么处理还怎么处理。
             */
            row.setStatus(MchServiceArea.ACTIVE);
            DataScopeContext.executeWithoutScope(() -> serviceAreaMapper.insert(row));
        }
    }



    /**
     * 这家店有没有一条公告正卡在人审里。
     *
     * <p><b>不下发的后果是「说了发布其实没发布」</b>：命中敏感词时后端保留旧公告，
     * 而端上拿到的仍是旧资料 —— 它照样弹「已发布」，输入框还原成上一条。
     * 商家只会以为自己手滑，再改一遍、再送审一次，队列里堆出一串同样的单子。
     *
     * <p>只看最近一条：同一家店重复提交时，人审看的是最后那条，
     * 商家要知道的也是「我最后发的那句在等」。
     */
    private StoreProfileVO.NoticePending pendingNotice(String merchantNo, String storeNo) {
        if (merchantNo == null) {
            return null;
        }
        var q = Wrappers.<MchStoreAudit>lambdaQuery()
                .eq(MchStoreAudit::getEntityNo, merchantNo)
                .eq(MchStoreAudit::getKind, MchStoreAudit.NOTICE)
                .eq(MchStoreAudit::getStatus, MchStoreAudit.PENDING);
        if (storeNo != null && !storeNo.isBlank()) {
            // 存量单没有门店号：它属于哪家说不清，一并显示给这家店也好过瞒着
            q.and(w -> w.isNull(MchStoreAudit::getStoreNo).or().eq(MchStoreAudit::getStoreNo, storeNo));
        }
        q.orderByDesc(MchStoreAudit::getSubmittedAt).last("limit 1");
        MchStoreAudit a = DataScopeContext.executeWithoutScope(() -> storeAuditMapper.selectOne(q));
        return a == null ? null : new StoreProfileVO.NoticePending(a.getContent(), a.getSubmittedAt());
    }

    /** 常用公告最多留几条。5 条覆盖得住轮换，再多这一排就要换行、也要开始滚动 */
    private static final int RECENT_MAX = 5;

    /**
     * 把这次用的公告推进「常用」：去重、移到最前、截到 5 条。
     *
     * <p>空公告不进列表 —— 清空公告是个动作，不是一句「常用语」。
     */
    private List<String> pushRecent(String rawJson, String announcement) {
        String now = announcement == null ? "" : announcement.trim();
        List<String> kept = new java.util.ArrayList<>();
        if (!now.isEmpty()) {
            kept.add(now);
        }
        for (String old : readList(rawJson)) {
            if (kept.size() >= RECENT_MAX) {
                break;
            }
            if (old != null && !old.isBlank() && !old.equals(now)) {
                kept.add(old);
            }
        }
        return kept;
    }

    /**
     * 父子归一：**同时勾了「浙江省」和「西湖区」时只留省**。
     *
     * <p>为什么必须在服务端也做一遍（端上已经做了一次）：
     * 老版本的 b-app 会原样回传它那份集合，而覆盖展开走的是国标码前缀 ——
     * 留着子项不会算错范围，但会在运营的待审队列里多出一条永远无意义的
     * 「整个西湖区」（省已经盖住它了），运营点通过或驳回都不改变任何事实。
     *
     * <p>只归一区划之间的父子。聚落（COMMUNITY）在这里留着：
     * 它的归属是 {@code cmt_community.region_code}，这一层拿不到，
     * 而多留一条聚落覆盖项与父项同时存在时，展开结果仍然正确（并集）。
     */
    private static List<AreaCommand> normalize(List<AreaCommand> areas) {
        List<String> regionCodes = areas.stream()
                .filter(a -> a != null && a.level() != null && a.refCode() != null && !a.refCode().isBlank())
                .filter(a -> !AREA_COMMUNITY.equals(a.level()))
                .map(AreaCommand::refCode)
                .toList();
        return areas.stream()
                .filter(a -> a == null || a.refCode() == null || AREA_COMMUNITY.equals(a.level())
                        || regionCodes.stream().noneMatch(
                                p -> !p.equals(a.refCode()) && a.refCode().startsWith(p)))
                .toList();
    }

    /** 回显覆盖项，名字由后端补 —— 端上只拿到 330106 的话要么显示数字要么再查一次 */
    private List<StoreProfileVO.ServiceAreaVO> areasOf(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        serviceAreaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                                .eq(MchServiceArea::getEntityNo, merchantNo)))
                .stream()
                .map(a -> new StoreProfileVO.ServiceAreaVO(
                        a.getLevel(), a.getRefCode(), areaNameOf(a), a.getStatus(), a.getAreaNo()))
                .toList();
    }

    private String areaNameOf(MchServiceArea a) {
        if (AREA_COMMUNITY.equals(a.getLevel())) {
            return communityNamePort.communityName(a.getRefCode());
        }
        // 区划给整条路径：「浙江省 / 杭州市 / 西湖区」比光一个「西湖区」更不容易选错
        return masterDataPort.regionPathName(a.getRefCode());
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
            /*
             * **先复活、再插入。** 差集只挡得住「同一次保存里重复加」，挡不住
             * 「先移除、之后又加回同一个社区」—— 那一行被逻辑删掉了（deleted=1）
             * 但仍占着 uk_entity_community 的索引位，直接 insert 必撞唯一键，
             * 而商家看到的是「系统开小差了」，他做的只是把经营范围改回去。
             */
            int revived = DataScopeContext.executeWithoutScope(() ->
                    merchantCommunityMapper.revive(merchantNo, added));
            if (revived > 0) {
                continue;
            }
            MchEntityCommunity row = new MchEntityCommunity();
            row.setEntityNo(merchantNo);
            row.setCommunityNo(added);
            DataScopeContext.executeWithoutScope(() -> merchantCommunityMapper.insert(row));
        }
        /*
         * **老写入口必须喂新读出口。**
         *
         * ADR-013 阶段二把可见性的读侧换到了 mch_service_area，而入驻审核这条老路径
         * 只写 mch_entity_community。V33 回填了存量，但此后新建的商家在新表里没有行 ——
         * 于是 PICKUP + 无覆盖项 = 谁也看不到，商家上完架一个订单都不来，且不报错。
         *
         * 这不是「为回滚而双写」（那个已经明确不做），是**过渡期里老写入口的必要延伸**：
         * 镜像只在这一个方法里做，没有「少一个入口、少一条分支」的余地。
         * mch_entity_community 退役时这一段跟着删。
         */
        mirrorCommunitiesToAreas(merchantNo, communityNos);
    }

    /** 把社区列表镜像成 COMMUNITY 层的覆盖项。**只动这一层**，不碰商家勾的区/市 */
    private void mirrorCommunitiesToAreas(String merchantNo, List<String> communityNos) {
        replaceAreas(merchantNo,
                communityNos.stream().map(c -> new AreaCommand(AREA_COMMUNITY, c)).toList(),
                AREA_COMMUNITY);
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

    /**
     * 这次读写落在哪一行门店。
     *
     * <p><b>不能是「随便一行」</b>：`limit 1` 不带排序时，多门店商家的门面资料
     * 落到哪家由数据库返回顺序决定 —— 线上 M0001 有三家店，地址填在第二家，
     * 而「门店自取」读到第一家（空地址），于是提示「还没填地址」，
     * 商家反复去填也没用，因为他填的和系统读的不是同一行。
     *
     * @param storeNo 请求头 `X-Store-No` 指定的当前门店；为空时取默认店
     */
    private MchStore row(String merchantNo, String storeNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchantNo)
                        .eq(storeNo != null && !storeNo.isBlank(), MchStore::getStoreNo, storeNo)
                        // 没指定就按默认店；同为默认时按建店顺序，保证每次都是同一行
                        .orderByDesc(MchStore::getIsDefault)
                        .orderByAsc(MchStore::getId)
                        .last("limit 1")));
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

    private void submitForAudit(String merchantNo, String storeNo, String kind, String content,
                                List<String> hits, Long noticeUntil) {
        MchStoreAudit a = new MchStoreAudit();
        a.setAuditNo(BizKey.next(BizKey.STORE_AUDIT));
        a.setEntityNo(merchantNo);
        /*
         * 门店与有效期都要跟着单子走。
         *
         * 只记商户号的时候，通过之后按商户取第一家店写回 ——
         * 「南门店今天停电」会落到总店的公告上；而有效期没地方存，
         * 审出来的「今日到货」就一直挂着。两个错都不报错，也就都没人发现。
         */
        a.setStoreNo(storeNo);
        a.setNoticeUntil(noticeUntil);
        a.setKind(kind);
        a.setContent(content);
        a.setStatus(MchStoreAudit.PENDING);
        a.setHits(json.writeValueAsString(hits));
        a.setSubmittedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> storeAuditMapper.insert(a));
    }
}
