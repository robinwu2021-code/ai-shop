package ai.neargo.shop.user.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.user.merchant.entity.MchEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.user.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.user.mapper.UserMappers.MchEntityCommunityMapper;
import ai.neargo.shop.user.mapper.UserMappers.MchPaymentMapper;
import java.util.List;
import ai.neargo.shop.user.mapper.UserMappers.MchEntityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 商家域对外的两个 Port：查询（{@link MerchantQueryPort}）与开通（{@link MerchantAdminPort}）。
 *
 * <p>合成一个实现类而不是两个，因为它们共用同一张表与同一套字段口径；
 * 分开写会出现两处各自维护「什么算 ACTIVE」的判断。接口仍是两个 ——
 * 查询是所有域都用的，开通只有 platform（运营审核通过）能调，权限边界不同。
 *
 * <p>从 {@code MerchantServiceImpl} 抽出：Service 兼任 Port 时，
 * 改本域的商家详情逻辑会不知不觉改掉 trade / settle 依赖的跨域契约。
 */
@Component
public class MerchantPortImpl implements MerchantQueryPort, MerchantAdminPort {

    private static final String ACTIVE = "ACTIVE";
    /** 评分存整数（50 = 5.0 分），避免浮点入库 */
    private static final int RATING_SCALE = 10;
    private static final int RATING_INIT = 50;

    private final MchEntityMapper merchantMapper;
    private final MchEntityCommunityMapper merchantCommunityMapper;
    private final MchPaymentMapper merchantPaymentMapper;
    private final ai.neargo.shop.user.service.MerchantStoreService merchantStoreService;
    private final ai.neargo.shop.user.mapper.UserMappers.CommunityMapper communityMapper;
    private final ai.neargo.shop.spi.platform.MasterDataPort masterDataPort;
    private final ai.neargo.shop.user.mapper.UserMappers.MchAccountMapper staffMapper;
    private final ai.neargo.shop.user.mapper.UserMappers.MchStoreMapper storeMapper;

    public MerchantPortImpl(MchEntityMapper merchantMapper, MchEntityCommunityMapper merchantCommunityMapper,
                            MchPaymentMapper merchantPaymentMapper,
                            ai.neargo.shop.user.service.MerchantStoreService merchantStoreService,
                            ai.neargo.shop.user.mapper.UserMappers.CommunityMapper communityMapper,
                            ai.neargo.shop.spi.platform.MasterDataPort masterDataPort,
                            ai.neargo.shop.user.mapper.UserMappers.MchAccountMapper staffMapper,
                            ai.neargo.shop.user.mapper.UserMappers.MchStoreMapper storeMapper) {
        this.staffMapper = staffMapper;
        this.storeMapper = storeMapper;
        this.masterDataPort = masterDataPort;
        this.communityMapper = communityMapper;
        this.merchantCommunityMapper = merchantCommunityMapper;
        this.merchantPaymentMapper = merchantPaymentMapper;
        this.merchantMapper = merchantMapper;
        this.merchantStoreService = merchantStoreService;
    }

    @Override
    public List<String> reachableCommunities(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            return List.of();
        }
        String scope = m.getServiceScope() == null ? "COMMUNITY" : m.getServiceScope();
        if (!"COMMUNITY".equals(scope)) {
            /*
             * CITY / PLATFORM：覆盖全部社区。一期只有一个城市，所以两档暂时同解 ——
             * 与 MerchantServiceImpl.applyReachable 同一口径（那边也是把 CITY 与 PLATFORM
             * 一起放行）。两处口径不同的后果是「商家页搜得到这家店、商品页搜不到它的货」。
             */
            return DataScopeContext.executeWithoutScope(() ->
                    communityMapper.selectList(Wrappers.<ai.neargo.shop.user.community.entity.CmtCommunity>lambdaQuery()
                            .eq(ai.neargo.shop.user.community.entity.CmtCommunity::getStatus, "OPEN")))
                    .stream().map(ai.neargo.shop.user.community.entity.CmtCommunity::getCommunityNo).toList();
        }
        return DataScopeContext.executeWithoutScope(() ->
                merchantCommunityMapper.selectList(Wrappers.<MchEntityCommunity>lambdaQuery()
                        .eq(MchEntityCommunity::getEntityNo, merchantNo)))
                .stream().map(MchEntityCommunity::getCommunityNo).toList();
    }

    @Override
    public Optional<String> defaultStoreNo(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectOne(Wrappers.<ai.neargo.shop.user.merchant.entity.MchStore>lambdaQuery()
                                .eq(ai.neargo.shop.user.merchant.entity.MchStore::getEntityNo, merchantNo)
                                .eq(ai.neargo.shop.user.merchant.entity.MchStore::getIsDefault, true)
                                .last("limit 1"))))
                .map(ai.neargo.shop.user.merchant.entity.MchStore::getStoreNo);
    }

    @Override
    public Optional<MerchantBrief> find(String merchantNo) {
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        if (m == null) {
            return Optional.empty();
        }
        /*
         * canReceive 目前等同于 ACTIVE；S6 接分账后改为「接收方已报备」（ADR-002）。
         * 调用方（trade / settle）届时一行不用改 —— 这正是 Port 的作用：
         * 「能不能收钱」这个判断的口径变了，问的人不必知道。
         */
        boolean active = ACTIVE.equals(m.getStatus());
        return Optional.of(new MerchantBrief(m.getEntityNo(), m.getName(), active, active,
                m.getLogo(), m.getRating() == null ? 0d : m.getRating() / (double) RATING_SCALE,
                Boolean.TRUE.equals(m.getVerified()),
                m.getBreachCount() == null ? 0 : m.getBreachCount()));
    }

    @Override
    @Transactional
    public String activate(ActivateCommand cmd) {
        String ownerUserNo = cmd.ownerUserNo();
        String name = cmd.name();
        String type = cmd.subject();

        /*
         * scope=COMMUNITY 却一个社区都没给 —— **直接拒绝，不要「先建了再说」**。
         * 建出来的结果是商家上着架却一个订单都不来，而这个故障没有任何报错，
         * 商家和运营都查不出原因（ADR-009）。宁可审核这一步失败，也不要产出一个隐形商家。
         */
        boolean byCommunity = cmd.serviceScope() == null || "COMMUNITY".equals(cmd.serviceScope());
        if (byCommunity && (cmd.communityNos() == null || cmd.communityNos().isEmpty())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * 重复点击「通过」是常态，要幂等 —— 但**判据是这份申请，不是这个人**。
         *
         * 曾经按 owner_user_no 判重：老板申请第二张执照、审核通过时被当成重复点击，
         * 系统去改第一个主体，名称/行业/法律形态被覆盖，两家店变一家，且没有任何报错。
         * 按申请单判之后，同一个人申请几张执照互不干扰。
         */
        MchEntity existing = cmd.activatedEntityNo() == null ? null
                : DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, cmd.activatedEntityNo()).last("limit 1")));
        if (existing != null) {
            existing.setStatus(ACTIVE);
            existing.setServiceScope(cmd.serviceScope());
            applyProfile(existing, cmd);
            merchantMapper.updateById(existing);
            // 重复点击「通过」是常态：全部按幂等写，不重复插
            ensureOwnerStaff(existing.getEntityNo(), ownerUserNo);
            ensureDefaultStore(existing.getEntityNo(), name);
            syncCommunities(existing.getEntityNo(), cmd.communityNos());
            ensurePayment(existing.getEntityNo(), type, cmd.settleAccountType());
            return existing.getEntityNo();
        }

        MchEntity m = new MchEntity();
        m.setEntityNo(BizKey.next(BizKey.MERCHANT));
        m.setName(name);
        m.setLogo("");
        /*
         * **写入一律用权威码**（ADR-010 §4 第 2 步）。
         * 认不出来的取值原样保留而不是兜底成某个值 —— 兜底会把"数据脏了"
         * 悄悄变成"数据是干净的但值不对"，后者查不出来。
         */
        String canonical = masterDataPort.canonicalSubject(type);
        m.setLegalForm(canonical != null ? canonical : type);
        m.setOwnerUserNo(ownerUserNo);
        // 新店从中位分起步，不是 0 分 —— 0 分会让新店在任何按评分排的列表里垫底，
        // 而它还没有任何订单可以证明自己（ADR-009 里「平台推荐位」要解决的也是这个问题）
        m.setRating(RATING_INIT);
        m.setRatingCount(0);
        m.setSalesCount(0);
        m.setGoodsCount(0);
        m.setScoreGoods(RATING_INIT);
        m.setScoreService(RATING_INIT);
        m.setScoreSpeed(RATING_INIT);
        m.setVerified(true);   // 审核通过即带认证标
        m.setBreachCount(0);
        m.setTags("[]");
        applyProfile(m, cmd);
        m.setJoinedAt(System.currentTimeMillis());
        m.setStatus(ACTIVE);
        m.setServiceScope(cmd.serviceScope() == null ? "COMMUNITY" : cmd.serviceScope());
        merchantMapper.insert(m);

        /*
         * 这几件事与建商家**必须同一个事务** —— 少任何一件，商家就是「存在但做不了生意」：
         *   成员行  少了他登录后没有 B 端身份（M1 起身份来源是 mch_account）
         *   默认门店 少了他没有可经营的门店
         *   覆盖范围 少了他对谁都不可见（ADR-009）
         *   分账主体 少了第一笔订单就分不了账（ADR-002）
         */
        ensureOwnerStaff(m.getEntityNo(), ownerUserNo);
        ensureDefaultStore(m.getEntityNo(), name);
        syncCommunities(m.getEntityNo(), cmd.communityNos());
        ensurePayment(m.getEntityNo(), type, cmd.settleAccountType());
        return m.getEntityNo();
    }

    /**
     * 建 owner 成员行。<b>身份来源</b>（M1 起取代 {@code owner_user_no}）。
     *
     * <p>漏掉它的后果最隐蔽：商家审核通过、`mch_entity` 有行、C 端也搜得到这家店，
     * 唯独他自己登录 B 端时 {@code BizContext} 是空的 —— 所有 /biz/** 都 403，
     * 而他看到的只是「打不开」。
     */
    private void ensureOwnerStaff(String merchantNo, String ownerUserNo) {
        if (ownerUserNo == null || ownerUserNo.isBlank()) {
            return;
        }
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                staffMapper.exists(Wrappers.<ai.neargo.shop.user.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.user.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.user.merchant.entity.MchAccount::getUserNo, ownerUserNo)));
        if (exists) {
            return;
        }
        var staff = new ai.neargo.shop.user.merchant.entity.MchAccount();
        staff.setMchAccountNo(BizKey.next(BizKey.MERCHANT_STAFF));
        staff.setEntityNo(merchantNo);
        staff.setUserNo(ownerUserNo);
        staff.setIsOwner(true);
        // 第一个主体自动成为默认；已有主体时不抢默认 —— 那是用户自己的选择
        boolean hasPrimary = DataScopeContext.executeWithoutScope(() ->
                staffMapper.exists(Wrappers.<ai.neargo.shop.user.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.user.merchant.entity.MchAccount::getUserNo, ownerUserNo)
                        .eq(ai.neargo.shop.user.merchant.entity.MchAccount::getIsPrimary, true)));
        staff.setIsPrimary(!hasPrimary);
        staff.setStatus(ai.neargo.shop.user.merchant.entity.MchAccount.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> staffMapper.insert(staff));
    }

    /** 建默认门店。一主体恰好一个，删不掉 —— 它是单店商家的全部。 */
    private void ensureDefaultStore(String merchantNo, String name) {
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                storeMapper.exists(Wrappers.<ai.neargo.shop.user.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.user.merchant.entity.MchStore::getEntityNo, merchantNo)));
        if (exists) {
            return;
        }
        var store = new ai.neargo.shop.user.merchant.entity.MchStore();
        store.setStoreNo(BizKey.next(BizKey.STORE));
        store.setEntityNo(merchantNo);
        store.setName(name);
        store.setIsDefault(true);
        store.setStatus(ai.neargo.shop.user.merchant.entity.MchStore.ACTIVE);
        store.setFeatured("[]");
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(store));
    }

    /**
     * 把申请单上的行业与简介落到商家主体。
     *
     * <p>此前这两项<b>只存在申请单上</b>：
     * <ul>
     *   <li>{@code industry} 决定这家店能不能开小微（微信白名单按行业给）。
     *       商家主体上永远是空的话，进件那一刻才发现主体选错了，而入驻早就通过了；
     *       它还是 {@code points_forced} 默认值的来源 —— 那个字段的注释写着
     *       「按行业强制开」，而行业不存在，那句话一直无法执行。</li>
     *   <li>{@code description} 是 C 端门店页展示的店铺简介。商家认真写的一段话
     *       通过审核后就没了 —— 不报错，只是门店页少一块。</li>
     * </ul>
     *
     * <p>用「非空才覆盖」而不是无条件赋值：重复点通过时，
     * 不该把商家后来在 B 端改过的简介冲回申请时填的那一版。
     */
    private void applyProfile(MchEntity m, ActivateCommand cmd) {
        if (cmd.industry() != null && !cmd.industry().isBlank()) {
            m.setIndustry(cmd.industry());
        }
        if (cmd.description() != null && !cmd.description().isBlank()) {
            m.setDescription(cmd.description());
        }
    }

    /**
     * 覆盖社区。<b>委托给 {@link ai.neargo.shop.user.service.MerchantStoreService}</b> ——
     * 店铺设置页也在改同一件事，两处各写一份迟早对不上，
     * 而对不上的后果是「入驻时配好的范围被店铺设置悄悄清空」。
     */
    private void syncCommunities(String merchantNo, List<String> communityNos) {
        merchantStoreService.syncCommunities(merchantNo, communityNos);
    }

    /**
     * 建分账主体占位记录（ADR-002）。
     *
     * <p>此刻多半还没有真实的开户结果 —— 那要走支付服务商的流程。但记录要先建出来：
     * 否则第一笔订单来了才发现<b>没有收款方</b>，钱已经收了却分不出去。
     * 状态置 APPLYING，商家在 B 端补完资料后推进。
     */
    private void ensurePayment(String merchantNo, String subject, String settleAccountType) {
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                merchantPaymentMapper.exists(Wrappers.<MchPaymentMerchant>lambdaQuery()
                        .eq(MchPaymentMerchant::getEntityNo, merchantNo)));
        if (exists) {
            return;
        }
        MchPaymentMerchant p = new MchPaymentMerchant();
        p.setEntityNo(merchantNo);
        p.setPayChannel(MchPaymentMerchant.WECHAT);
        /*
         * 主体 → 通道进件主体，走主数据而不是在这里再写一遍 if。
         * 这是「PERSONAL 是不是就是小微」的**第三处**实现 —— 前两处在建商家与入驻校验。
         * 三处各写各的，判错一次商家就是进件被拒（ADR-002 §4 / ADR-010）。
         */
        String canonical = masterDataPort.canonicalSubject(subject);
        p.setLegalForm(canonical != null ? canonical : MchPaymentMerchant.INDIVIDUAL);
        p.setApplyStatus(MchPaymentMerchant.APPLYING);
        // 结算账户形态也由主体决定：小微打到个人，其余打到对公。
        // 申请时没填就按主体的默认形态 —— 让商家去猜"该填哪个"是没道理的
        p.setSettleAccountType(settleAccountType != null && !settleAccountType.isBlank()
                ? settleAccountType : masterDataPort.settleAccountType(canonical));
        p.setAppliedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> merchantPaymentMapper.insert(p));
    }

    /**
     * 四级串联：全局 → 社区 → 主体非小微 → 本店开关。
     *
     * <p><b>顺序是有语义的</b>：主体这一级必须排在商家开关之前 ——
     * 小微是「不可开」不是「关着」。顺序反了的话小微商家会看到
     * 「本店未开启积分」，以为自己打开就行，而他打不开。
     */
    @Override
    public String pointsDenyReason(String merchantNo) {
        MchEntity m = merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1"));
        if (m == null) {
            return "商家不存在";
        }

        // L2 社区：上层关，下层一定关。
        // 商家可覆盖多个社区（ADR-009 三档范围），**一个开着就算开** ——
        // 判否要求全部关闭，否则跨社区经营的商家会因为某个未开放的社区被整体禁掉
        List<String> reachable = reachableCommunities(merchantNo);
        if (!reachable.isEmpty()) {
            boolean anyOpen = communityMapper.selectList(
                            Wrappers.<ai.neargo.shop.user.community.entity.CmtCommunity>lambdaQuery()
                                    .in(ai.neargo.shop.user.community.entity.CmtCommunity::getCommunityNo, reachable))
                    .stream().anyMatch(c -> !Boolean.FALSE.equals(c.getPointsEnabled()));
            if (!anyOpen) {
                return "本社区暂未开放积分";
            }
        }

        // 主体：小微一律拒绝，且提示的是「升级后可开启」
        MchPaymentMerchant pay = merchantPaymentMapper.selectOne(
                Wrappers.<MchPaymentMerchant>lambdaQuery()
                        .eq(MchPaymentMerchant::getEntityNo, merchantNo).last("LIMIT 1"));
        if (pay != null && MchPaymentMerchant.MICRO.equals(pay.getLegalForm())) {
            return "本店暂不支持积分（升级为个体工商户后可开启）";
        }

        // L3 本店。forced 为真时商家关不掉
        if (Boolean.FALSE.equals(m.getPointsEnabled()) && !Boolean.TRUE.equals(m.getPointsForced())) {
            return "本店未开启积分";
        }
        return null;
    }

    @Override
    public boolean isPointsForced(String merchantNo) {
        MchEntity m = merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1"));
        return m != null && Boolean.TRUE.equals(m.getPointsForced());
    }

    @Override
    public void setPointsEnabled(String merchantNo, boolean enabled) {
        MchEntity m = merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1"));
        if (m == null) {
            return;
        }
        // 只改开关。**不动已发出的分，也不退已扣的服务费**
        m.setPointsEnabled(enabled);
        merchantMapper.updateById(m);
    }

    @Override
    public long countByIndustry(String industry) {
        return merchantMapper.selectCount(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getIndustry, industry));
    }
}
