package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.member.dto.MemberVOs.MemberDetailVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.dto.MemberVOs.MemberSourceVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberSettingVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberStatsVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberStoreVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberVO;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrMemberSource;
import ai.neargo.shop.member.entity.MbrMemberStore;
import ai.neargo.shop.member.entity.MbrMemberTag;
import ai.neargo.shop.member.entity.MbrSetting;
import ai.neargo.shop.member.mapper.MemberMappers.MemberMapper;
import ai.neargo.shop.member.mapper.MemberMappers.MemberSourceMapper;
import ai.neargo.shop.member.mapper.MemberMappers.MemberStoreMapper;
import ai.neargo.shop.member.mapper.MemberMappers.SettingMapper;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.spi.user.PersonPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** 见 {@link MemberService}。 */
@Service
public class MemberServiceImpl implements MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberServiceImpl.class);

    private static final long DAY = 86_400_000L;
    private static final long D90 = 90 * DAY;

    private final MemberMapper memberMapper;
    private final MemberStoreMapper storeMapper;
    private final MemberSourceMapper sourceMapper;
    private final SettingMapper settingMapper;
    private final PersonPort personPort;
    /** 详情页要显示他身上的标签。同域直接依赖，不必绕 Port */
    private final ai.neargo.shop.member.service.MemberTagService tagService;

    /** 按标签筛人要读关系表。判 tagNo 存不存在是标签服务的事，这里只做交集 */
    private final ai.neargo.shop.member.mapper.MemberMappers.MemberTagMapper memberTagMapper;

    public MemberServiceImpl(MemberMapper memberMapper, MemberStoreMapper storeMapper,
                             MemberSourceMapper sourceMapper, SettingMapper settingMapper,
                             PersonPort personPort,
                             ai.neargo.shop.member.service.MemberTagService tagService,
                             ai.neargo.shop.member.mapper.MemberMappers.MemberTagMapper
                                     memberTagMapper) {
        this.memberTagMapper = memberTagMapper;
        this.memberMapper = memberMapper;
        this.storeMapper = storeMapper;
        this.sourceMapper = sourceMapper;
        this.settingMapper = settingMapper;
        this.personPort = personPort;
        this.tagService = tagService;
    }

    // ---------------------------------------------------------------- 写

    @Override
    @Transactional
    public void onOrderPaid(String subOrderNo, String userNo, String personNo,
                            String entityNo, String storeNo, long amountMinor, long paidAt) {
        if (personNo == null || personNo.isBlank()) {
            /*
             * 没有人档 = 他还没绑手机号。**这一单照常成立，只是不计进任何会员名单** ——
             * 会员必须有已验证手机号是准入规则，而交易永远优先于会员。
             * 商家会在会员页顶部看到「另有 N 位买家未绑手机号，未计入」，差额有解释。
             */
            return;
        }
        if (subOrderNo != null && alreadyCounted(entityNo, subOrderNo)) {
            return;   // 幂等：支付回调会重发
        }

        MbrMember m = find(entityNo, personNo).orElse(null);
        boolean fresh = m == null;
        if (fresh) {
            m = create(entityNo, personNo, MbrMember.SOURCE_ORDER, storeNo, paidAt);
        }
        m.setLastOrderAt(paidAt);
        if (m.getFirstOrderAt() == null) {
            m.setFirstOrderAt(paidAt);
        }
        m.setOrderCount(nz(m.getOrderCount()) + 1);
        m.setTotalSpentMinor(nz(m.getTotalSpentMinor()) + amountMinor);
        if (paidAt >= System.currentTimeMillis() - D90) {
            m.setD90OrderCount(nz(m.getD90OrderCount()) + 1);
            m.setD90SpentMinor(nz(m.getD90SpentMinor()) + amountMinor);
        }
        m.setLevel(levelOf(m.getD90OrderCount(), m.getLastOrderAt()));
        memberMapper.updateById(m);

        applyStore(m, storeNo, amountMinor, paidAt, fresh);
        recordSource(m, MbrMember.SOURCE_ORDER, storeNo, fresh, paidAt,
                null, null, null, null, subOrderNo);
    }

    @Override
    @Transactional
    public MbrMember join(String entityNo, String personNo, String storeNo) {
        if (personNo == null || personNo.isBlank()) {
            // 端上据此弹一次手机号授权 —— 这不是报错，是引导
            throw BizException.of(ErrorCode.MEMBER_PHONE_REQUIRED);
        }
        Optional<MbrMember> exist = find(entityNo, personNo);
        if (exist.isPresent()) {
            return exist.get();     // 重复点「加入」是常态，不该报错
        }
        long now = System.currentTimeMillis();
        MbrMember m = create(entityNo, personNo, MbrMember.SOURCE_SEARCH, storeNo, now);
        recordSource(m, MbrMember.SOURCE_SEARCH, storeNo, true, now, null, null, null, null, null);
        return m;
    }

    @Override
    @Transactional
    public MbrMember enroll(String entityNo, String phone, String remark, List<String> tagNos,
                            String storeNo, String operatorNo) {
        if (phone == null || phone.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 人档以手机号为准：本人还没注册也照样有一份，这正是线索能落地的原因
        var person = personPort.resolveOrCreateByPhone(phone);
        boolean registered = person.userNo() != null && !person.userNo().isBlank();

        MbrMember m = find(entityNo, person.personNo()).orElse(null);
        long now = System.currentTimeMillis();
        if (m == null) {
            m = create(entityNo, person.personNo(), MbrMember.SOURCE_MANUAL, storeNo, now);
            /*
             * 本人还没绑账号 = 线索。**不可触达、不进受众** ——
             * 录入手机号不等于拿到推送许可，这条是合规边界，不是产品取舍。
             */
            m.setStatus(registered ? MbrMember.ACTIVE : MbrMember.LEAD);
            recordSource(m, MbrMember.SOURCE_MANUAL, storeNo, true, now,
                    null, null, null, operatorNo, null);
        }
        // 已存在就把备注并进去，不报错 —— 店员重复录入是常态
        if (remark != null && !remark.isBlank()) {
            m.setRemark(remark.trim());
        }
        memberMapper.updateById(m);
        if (tagNos != null && !tagNos.isEmpty()) {
            tagService.tag(entityNo, List.of(m.getMemberNo()), tagNos, List.of(), operatorNo);
        }
        return m;
    }

    @Override
    @Transactional
    public int claimByPerson(String personNo) {
        if (personNo == null || personNo.isBlank()) {
            return 0;
        }
        List<MbrMember> leads = memberMapper.selectList(Wrappers.<MbrMember>lambdaQuery()
                .eq(MbrMember::getPersonNo, personNo)
                .eq(MbrMember::getStatus, MbrMember.LEAD));
        long now = System.currentTimeMillis();
        for (MbrMember m : leads) {
            m.setStatus(MbrMember.ACTIVE);
            m.setClaimedAt(now);
            memberMapper.updateById(m);
        }
        if (!leads.isEmpty()) {
            log.info("[member] 人档 {} 绑定账号，{} 条线索会员转正", personNo, leads.size());
        }
        return leads.size();
    }

    @Override
    @Transactional
    public MbrMember patch(String entityNo, String memberNo, String remark, String status) {
        MbrMember m = memberMapper.selectOne(Wrappers.<MbrMember>lambdaQuery()
                .eq(MbrMember::getEntityNo, entityNo)
                .eq(MbrMember::getMemberNo, memberNo).last("limit 1"));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (remark != null) {
            m.setRemark(remark.isBlank() ? null : remark.trim());
        }
        if (status != null && (MbrMember.ACTIVE.equals(status) || MbrMember.BLOCKED.equals(status))) {
            // 线索不能被改成 ACTIVE：转正只能由本人绑定账号触发，不能由商家点一下完成
            if (!MbrMember.LEAD.equals(m.getStatus())) {
                m.setStatus(status);
            }
        }
        memberMapper.update(null, Wrappers.<MbrMember>lambdaUpdate()
                .eq(MbrMember::getMemberNo, memberNo)
                // 显式 set：updateById 默认跳过 null，清空备注那一下会静默失败
                .set(MbrMember::getRemark, m.getRemark())
                .set(MbrMember::getStatus, m.getStatus()));
        return m;
    }

    // ---------------------------------------------------------------- 读

    @Override
    public PageData<MemberVO> list(String entityNo, MemberQuery q) {
        LambdaQueryWrapper<MbrMember> w = baseQuery(entityNo, q);
        // 沉睡的排最前：那是店主唯一能立刻行动的信号，埋在列表底部等于没有
        w.last("order by case when level = 'SLEEPING' then 0 else 1 end, last_order_at desc");

        long pageNo = Math.max(q.page(), 1);
        long size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        Page<MbrMember> page = memberMapper.selectPage(Page.of(pageNo, size), w);
        return PageData.of(page.getRecords().stream().map(m -> vo(m, q.storeNo())).toList(),
                page.getTotal(), pageNo, size);
    }

    @Override
    public List<String> match(String entityNo, MemberQuery q) {
        return memberMapper.selectList(baseQuery(entityNo, q)).stream()
                .map(MbrMember::getMemberNo).toList();
    }

    @Override
    public List<String> matchReachable(String entityNo, MemberQuery q) {
        return memberMapper.selectList(baseQuery(entityNo, q)).stream()
                .filter(MbrMember::reachable).map(MbrMember::getMemberNo).toList();
    }

    /**
     * 筛选条件 → 查询。<b>列表、人群试算、发放共用这一处</b> ——
     * 三处各写一遍，同一群人会算出三个数，而商家分不清哪个对。
     *
     * <p><b>按门店筛时走的是门店行</b>（{@code mbr_member_store}）：
     * 「南门店的沉睡老客」问的是他在南门店的往来，不是他在整个主体的往来。
     */
    private LambdaQueryWrapper<MbrMember> baseQuery(String entityNo, MemberQuery q) {
        LambdaQueryWrapper<MbrMember> w = Wrappers.<MbrMember>lambdaQuery()
                .eq(MbrMember::getEntityNo, entityNo)
                .eq(q.status() != null, MbrMember::getStatus, q.status())
                // 门店口径下 level 由门店行判（见下），主表这条只在按主体时生效
                .eq(q.level() != null && (q.storeNo() == null || q.storeNo().isBlank()),
                        MbrMember::getLevel, q.level())
                .eq(q.source() != null, MbrMember::getSource, q.source())
                .le(q.lastOrderBefore() != null, MbrMember::getLastOrderAt, q.lastOrderBefore())
                .ge(q.lastOrderAfter() != null, MbrMember::getLastOrderAt, q.lastOrderAfter())
                .ge(q.spentMin() != null, MbrMember::getTotalSpentMinor, q.spentMin())
                .le(q.spentMax() != null, MbrMember::getTotalSpentMinor, q.spentMax());

        /*
         * 按手机号找人：**必须是完整号**。
         * 前缀模糊查询会把会员库变成一本通讯录 —— 输入「138」就能翻出一屏人。
         * 完整号先经人档解析成 personNo，再按它精确匹配；号不存在时返回空页而不是报错。
         */
        if (q.phone() != null && !q.phone().isBlank()) {
            String personNo = personPort.resolveOrCreateByPhone(q.phone()).personNo();
            w.eq(MbrMember::getPersonNo, personNo);
        }
        /*
         * 标签取交集：选两个标签是「都要满足」。并集会算出比单选任何一个都大的人群 ——
         * 商家点第二个标签本意是收窄，人数反而涨了，没人看得懂。
         *
         * 用「按会员号分组、数够个数」而不是 N 次 in：标签少（每人最多十几个），
         * 一次取回来在内存里数比拼 N 个子查询清楚，也不用写 XML。
         */
        if (q.tagNos() != null && !q.tagNos().isEmpty()) {
            List<String> nos = memberTagMapper.selectList(Wrappers.<MbrMemberTag>lambdaQuery()
                            .eq(MbrMemberTag::getEntityNo, entityNo)
                            .in(MbrMemberTag::getTagNo, q.tagNos()))
                    .stream().collect(java.util.stream.Collectors.groupingBy(
                            MbrMemberTag::getMemberNo, java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .filter(e -> e.getValue() >= q.tagNos().size())
                    .map(java.util.Map.Entry::getKey).toList();
            w.in(MbrMember::getMemberNo, nos.isEmpty() ? List.of("__none__") : nos);
        }

        /*
         * 门店口径：先按门店行筛出人，再用他们的会员号收窄主表查询。
         * 门店行里没有身份字段（状态、来源、标签都在主表），所以两边各筛各的那部分。
         */
        if (q.storeNo() != null && !q.storeNo().isBlank()) {
            var storeRows = storeMapper.selectList(Wrappers.<MbrMemberStore>lambdaQuery()
                    .eq(MbrMemberStore::getEntityNo, entityNo)
                    .eq(MbrMemberStore::getStoreNo, q.storeNo())
                    .eq(q.level() != null, MbrMemberStore::getLevel, q.level())
                    .le(q.lastOrderBefore() != null, MbrMemberStore::getLastOrderAt, q.lastOrderBefore())
                    .ge(q.lastOrderAfter() != null, MbrMemberStore::getLastOrderAt, q.lastOrderAfter()));
            List<String> nos = storeRows.stream().map(MbrMemberStore::getMemberNo).toList();
            // 一个都没有时给一个不可能命中的值，否则 in() 空集合会被 MyBatis-Plus 忽略掉，
            // 变成「整个主体的会员」—— 那正好是反的
            w.in(MbrMember::getMemberNo, nos.isEmpty() ? List.of("__none__") : nos);
        }
        return w;
    }

    @Override
    public MemberStatsVO stats(String entityNo, String storeNo) {
        /*
         * 按门店经营时，四个数字要按门店行算 —— 「新客」的含义随之变成
         * **对这家店第一次买**（在别的店买过也算）。这正是十公里外那家店要的判断，
         * 而按主体口径下那个人会被算成熟客、漏在新客活动之外。
         */
        if (storeNo != null && !storeNo.isBlank()) {
            return storeStats(entityNo, storeNo);
        }
        List<MbrMember> all = memberMapper.selectList(Wrappers.<MbrMember>lambdaQuery()
                .eq(MbrMember::getEntityNo, entityNo));
        long monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int neu = 0;
        int regular = 0;
        int loyal = 0;
        int sleeping = 0;
        int reachable = 0;
        int newThisMonth = 0;
        for (MbrMember m : all) {
            switch (nzs(m.getLevel())) {
                case MbrMember.LEVEL_REGULAR -> regular++;
                case MbrMember.LEVEL_LOYAL -> loyal++;
                case MbrMember.LEVEL_SLEEPING -> sleeping++;
                default -> neu++;
            }
            if (m.reachable()) {
                reachable++;
            }
            if (nz(m.getJoinedAt()) >= monthStart) {
                newThisMonth++;
            }
        }
        // unlinkedBuyers 由应用层用订单数与会员数的差额算（会员域不认识订单表），这里给 0
        return new MemberStatsVO(neu, regular, loyal, sleeping, reachable, newThisMonth, 0);
    }

    /** 门店口径的四个数字。可触达与本月新增仍按人算 —— 那两个与门店无关 */
    private MemberStatsVO storeStats(String entityNo, String storeNo) {
        List<MbrMemberStore> rows = storeMapper.selectList(Wrappers.<MbrMemberStore>lambdaQuery()
                .eq(MbrMemberStore::getEntityNo, entityNo)
                .eq(MbrMemberStore::getStoreNo, storeNo));
        int neu = 0;
        int regular = 0;
        int loyal = 0;
        int sleeping = 0;
        for (MbrMemberStore r : rows) {
            switch (nzs(r.getLevel())) {
                case MbrMember.LEVEL_REGULAR -> regular++;
                case MbrMember.LEVEL_LOYAL -> loyal++;
                case MbrMember.LEVEL_SLEEPING -> sleeping++;
                default -> neu++;
            }
        }
        long monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int reachable = 0;
        int newThisMonth = 0;
        for (MbrMemberStore r : rows) {
            MbrMember m = memberMapper.selectOne(Wrappers.<MbrMember>lambdaQuery()
                    .eq(MbrMember::getMemberNo, r.getMemberNo()).last("limit 1"));
            if (m == null) {
                continue;
            }
            if (m.reachable()) {
                reachable++;
            }
            if (nz(m.getJoinedAt()) >= monthStart) {
                newThisMonth++;
            }
        }
        return new MemberStatsVO(neu, regular, loyal, sleeping, reachable, newThisMonth, 0);
    }

    @Override
    public MemberSettingVO settings(String entityNo) {
        MbrSetting s = settingRow(entityNo);
        return new MemberSettingVO(
                s == null || s.getMemberScope() == null ? MbrSetting.ENTITY : s.getMemberScope(),
                s == null || s.getAutoJoinOnOrder() == null || s.getAutoJoinOnOrder() == 1);
    }

    @Override
    @Transactional
    public MemberSettingVO saveSettings(String entityNo, String memberScope,
                                        Boolean autoJoinOnOrder) {
        MbrSetting s = settingRow(entityNo);
        if (s == null) {
            s = new MbrSetting();
            s.setEntityNo(entityNo);
            s.setMemberScope(MbrSetting.ENTITY);
            s.setAutoJoinOnOrder(1);
            settingMapper.insert(s);
        }
        if (memberScope != null
                && (MbrSetting.ENTITY.equals(memberScope) || MbrSetting.STORE.equals(memberScope))) {
            s.setMemberScope(memberScope);
        }
        if (autoJoinOnOrder != null) {
            s.setAutoJoinOnOrder(autoJoinOnOrder ? 1 : 0);
        }
        settingMapper.updateById(s);
        return new MemberSettingVO(s.getMemberScope(), nz(s.getAutoJoinOnOrder()) == 1);
    }

    private MbrSetting settingRow(String entityNo) {
        return settingMapper.selectOne(Wrappers.<MbrSetting>lambdaQuery()
                .eq(MbrSetting::getEntityNo, entityNo).last("limit 1"));
    }

    @Override
    public Optional<MemberDetailVO> detail(String entityNo, String memberNo) {
        MbrMember m = memberMapper.selectOne(Wrappers.<MbrMember>lambdaQuery()
                .eq(MbrMember::getEntityNo, entityNo)
                .eq(MbrMember::getMemberNo, memberNo).last("limit 1"));
        if (m == null) {
            return Optional.empty();
        }
        List<MemberStoreVO> stores = storeMapper.selectList(Wrappers.<MbrMemberStore>lambdaQuery()
                        .eq(MbrMemberStore::getMemberNo, memberNo)
                        .orderByDesc(MbrMemberStore::getLastOrderAt)).stream()
                .map(s -> new MemberStoreVO(s.getStoreNo(), s.getOrderCount(),
                        s.getTotalSpentMinor(), s.getLastOrderAt(), nz(s.getIsFirstStore()) == 1))
                .toList();
        List<MemberSourceVO> sources = sourceMapper.selectList(Wrappers.<MbrMemberSource>lambdaQuery()
                        .eq(MbrMemberSource::getMemberNo, memberNo)
                        .orderByDesc(MbrMemberSource::getOccurredAt)).stream()
                .map(s -> new MemberSourceVO(s.getSourceType(), s.getStoreNo(), s.getLinkNo(),
                        s.getInviterUserNo(), s.getInviterRole(), s.getOperatorNo(),
                        s.getActivityNo(), nz(s.getIsFirst()) == 1, nz(s.getOccurredAt())))
                .toList();
        return Optional.of(new MemberDetailVO(vo(m), stores, sources,
                tagService.tagsOf(entityNo, memberNo)));
    }

    @Override
    public Optional<MbrMember> find(String entityNo, String personNo) {
        return Optional.ofNullable(memberMapper.selectOne(Wrappers.<MbrMember>lambdaQuery()
                .eq(MbrMember::getEntityNo, entityNo)
                .eq(MbrMember::getPersonNo, personNo).last("limit 1")));
    }

    // ---------------------------------------------------------------- 内部

    private MbrMember create(String entityNo, String personNo, String source,
                             String storeNo, long now) {
        MbrMember m = new MbrMember();
        m.setMemberNo(BizKey.next(BizKey.MEMBER));
        m.setEntityNo(entityNo);
        m.setPersonNo(personNo);
        // 有人档就是正式会员：人档以已验证手机号为准，线索态只在商家手工录入时出现（P2）
        m.setStatus(MbrMember.ACTIVE);
        m.setSource(source);
        m.setFirstStoreNo(storeNo);
        m.setOrderCount(0);
        m.setTotalSpentMinor(0L);
        m.setD90OrderCount(0);
        m.setD90SpentMinor(0L);
        m.setLevel(MbrMember.LEVEL_NEW);
        m.setReachOptOut(0);
        m.setJoinedAt(now);
        try {
            memberMapper.insert(m);
        } catch (DuplicateKeyException e) {
            // 并发：同一个人的两单同时回调。回读那一条，不报错
            return find(entityNo, personNo).orElseThrow(() -> e);
        }
        return m;
    }

    /**
     * 门店维度。<b>单店主体不写</b> —— 那一行等于主表的复制，读不到时回落主表即可。
     * 判据是「这家主体有没有第二家门店的会员往来」，由调用方传 storeNo 决定：
     * storeNo 为空（单店场景下端上不传）就跳过。
     */
    private void applyStore(MbrMember m, String storeNo, long amountMinor, long paidAt,
                            boolean firstEver) {
        if (storeNo == null || storeNo.isBlank()) {
            return;
        }
        MbrMemberStore s = storeMapper.selectOne(Wrappers.<MbrMemberStore>lambdaQuery()
                .eq(MbrMemberStore::getMemberNo, m.getMemberNo())
                .eq(MbrMemberStore::getStoreNo, storeNo).last("limit 1"));
        if (s == null) {
            s = new MbrMemberStore();
            s.setMemberNo(m.getMemberNo());
            s.setEntityNo(m.getEntityNo());
            s.setStoreNo(storeNo);
            s.setOrderCount(0);
            s.setTotalSpentMinor(0L);
            s.setD90OrderCount(0);
            s.setD90SpentMinor(0L);
            s.setIsFirstStore(firstEver ? 1 : 0);
            s.setFirstOrderAt(paidAt);
            storeMapper.insert(s);
        }
        s.setLastOrderAt(paidAt);
        s.setOrderCount(nz(s.getOrderCount()) + 1);
        s.setTotalSpentMinor(nz(s.getTotalSpentMinor()) + amountMinor);
        if (paidAt >= System.currentTimeMillis() - D90) {
            s.setD90OrderCount(nz(s.getD90OrderCount()) + 1);
            s.setD90SpentMinor(nz(s.getD90SpentMinor()) + amountMinor);
        }
        s.setLevel(levelOf(s.getD90OrderCount(), s.getLastOrderAt()));
        storeMapper.updateById(s);
    }

    private void recordSource(MbrMember m, String type, String storeNo, boolean first, long at,
                              String linkNo, String inviterUserNo, String inviterRole,
                              String operatorNo, String refNo) {
        MbrMemberSource row = new MbrMemberSource();
        row.setSourceNo(BizKey.next(BizKey.MEMBER_SOURCE));
        row.setMemberNo(m.getMemberNo());
        row.setEntityNo(m.getEntityNo());
        row.setSourceType(type);
        row.setStoreNo(storeNo);
        row.setLinkNo(linkNo);
        row.setInviterUserNo(inviterUserNo);
        row.setInviterRole(inviterRole);
        row.setOperatorNo(operatorNo);
        row.setRefNo(refNo);
        row.setIsFirst(first ? 1 : 0);
        row.setOccurredAt(at);
        sourceMapper.insert(row);
    }

    /**
     * 这一单是不是已经算过。
     *
     * <p>用来源明细当幂等台账：支付回调重发时，同一张子订单只该被算一次。
     * 单独建一张「已处理事件表」也行，但那是第二处需要维护的东西，
     * 而来源明细本来就要一行行留着。
     */
    private boolean alreadyCounted(String entityNo, String subOrderNo) {
        return sourceMapper.exists(Wrappers.<MbrMemberSource>lambdaQuery()
                .eq(MbrMemberSource::getEntityNo, entityNo)
                .eq(MbrMemberSource::getRefNo, subOrderNo));
    }

    /**
     * 分层口径。**先判沉睡再判活跃** —— 一个曾经的熟客三个月没来，
     * 商家要看到的是「沉睡」，不是「熟客」。
     */
    private static String levelOf(Integer d90Orders, Long lastOrderAt) {
        long idleDays = lastOrderAt == null ? Long.MAX_VALUE
                : (System.currentTimeMillis() - lastOrderAt) / DAY;
        if (lastOrderAt != null && idleDays > 60) {
            return MbrMember.LEVEL_SLEEPING;
        }
        int n = nz(d90Orders);
        if (n >= 6) {
            return MbrMember.LEVEL_LOYAL;
        }
        return n >= 2 ? MbrMember.LEVEL_REGULAR : MbrMember.LEVEL_NEW;
    }

    private MemberVO vo(MbrMember m) {
        return vo(m, null);
    }

    /**
     * @param storeNo 非空时，<b>数字与分层都取这家店的</b> ——
     *                按门店经营的商家看的是「他在我这家店买过几次」，
     *                而不是「他在这个主体买过几次」。这两个数在多店主体上差得很远
     */
    private MemberVO vo(MbrMember m, String storeNo) {
        String tail = personPort.find(m.getPersonNo()).map(PersonPort.PersonView::phoneTail)
                .orElse(null);
        String level = m.getLevel();
        Integer orders = m.getOrderCount();
        Long spent = m.getTotalSpentMinor();
        Integer d90 = m.getD90OrderCount();
        Long last = m.getLastOrderAt();
        if (storeNo != null && !storeNo.isBlank()) {
            MbrMemberStore st = storeMapper.selectOne(Wrappers.<MbrMemberStore>lambdaQuery()
                    .eq(MbrMemberStore::getMemberNo, m.getMemberNo())
                    .eq(MbrMemberStore::getStoreNo, storeNo).last("limit 1"));
            if (st != null) {
                level = st.getLevel();
                orders = st.getOrderCount();
                spent = st.getTotalSpentMinor();
                d90 = st.getD90OrderCount();
                last = st.getLastOrderAt();
            }
        }
        Integer days = last == null ? null : (int) ((System.currentTimeMillis() - last) / DAY);
        return new MemberVO(m.getMemberNo(), m.getPersonNo(), tail, m.getStatus(), m.getSource(),
                level, m.getFirstStoreNo(), orders, spent, d90, last, days,
                nz(m.getReachOptOut()) == 1, m.getRemark(), nz(m.getJoinedAt()));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static String nzs(String v) {
        return v == null ? "" : v;
    }
}
