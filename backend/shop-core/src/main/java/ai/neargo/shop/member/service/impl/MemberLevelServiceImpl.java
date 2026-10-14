package ai.neargo.shop.member.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrMemberSource;
import ai.neargo.shop.member.entity.MbrMemberStore;
import ai.neargo.shop.member.mapper.MemberMappers.MemberMapper;
import ai.neargo.shop.member.mapper.MemberMappers.MemberSourceMapper;
import ai.neargo.shop.member.mapper.MemberMappers.MemberStoreMapper;
import ai.neargo.shop.member.service.LevelPolicy;
import ai.neargo.shop.member.service.MemberLevelService;
import ai.neargo.shop.spi.platform.SettingPort;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分层口径与每日重算。
 *
 * <p><b>近 90 天单数只从会员域自己的来源台账数</b>（{@code mbr_member_source} 里
 * {@code source_type=ORDER} 的行，每笔支付一行、按子订单号幂等），不跨域去查订单表 ——
 * 那会是每天一次对订单大表的全主体聚合，外加一条会员域对交易域的依赖。
 */
@Service
public class MemberLevelServiceImpl implements MemberLevelService {

    private static final Logger log = LoggerFactory.getLogger(MemberLevelServiceImpl.class);

    static final String KEY_LAST_RUN = "member.level.last-run";
    private static final long D90 = 90L * 86_400_000L;
    /** 一次取多少个会员。只是分页粒度，不是上限 */
    private static final int PAGE = 500;
    private static final String SYSTEM = "SYSTEM";

    private final MemberMapper memberMapper;
    private final MemberStoreMapper storeMapper;
    private final MemberSourceMapper sourceMapper;
    private final SettingPort settingPort;
    private final ObjectMapper json;

    public MemberLevelServiceImpl(MemberMapper memberMapper, MemberStoreMapper storeMapper,
                                  MemberSourceMapper sourceMapper, SettingPort settingPort,
                                  ObjectMapper json) {
        this.memberMapper = memberMapper;
        this.storeMapper = storeMapper;
        this.sourceMapper = sourceMapper;
        this.settingPort = settingPort;
        this.json = json;
    }

    @Override
    public LevelPolicy policy() {
        String raw = settingPort.get(LevelPolicy.KEY, null);
        if (raw == null || raw.isBlank()) {
            return LevelPolicy.DEFAULT;
        }
        try {
            LevelPolicy p = json.readValue(raw, LevelPolicy.class);
            if (p.valid()) {
                return p;
            }
            log.warn("[会员分层] 口径不自洽 {}，按默认 {}", raw, LevelPolicy.DEFAULT);
        } catch (RuntimeException e) {
            // 配置坏了不该让下单入会失败 —— 按默认口径算，并留一条 WARN 让人看得到
            log.warn("[会员分层] 口径读不出来 {}，按默认 {}", raw, LevelPolicy.DEFAULT);
        }
        return LevelPolicy.DEFAULT;
    }

    @Override
    public LevelPolicy savePolicy(LevelPolicy policy, String operatorNo) {
        if (policy == null || !policy.valid()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        settingPort.put(LevelPolicy.KEY, json.writeValueAsString(policy), operatorNo);
        return policy;
    }

    @Override
    public RecomputeResult lastRun() {
        String raw = settingPort.get(KEY_LAST_RUN, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, RecomputeResult.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public RecomputeResult recompute(long now) {
        long started = System.currentTimeMillis();
        LevelPolicy policy = policy();
        /*
         * 任务线程没有登录上下文，而 mbr_* 按 entity_no 登记了数据域：
         * 不绕开的话 SELECT 查出空、UPDATE 影响 0 行，任务报成功而一行都没改。
         */
        int[] totals = DataScopeContext.executeWithoutScope(() -> {
            int[] t = new int[3];
            for (String entityNo : entities()) {
                int[] r = recomputeEntity(entityNo, policy, now);
                t[0] += r[0];
                t[1] += r[1];
                t[2] += r[2];
            }
            return t;
        });
        RecomputeResult result = new RecomputeResult(now, totals[0], totals[1], totals[2],
                System.currentTimeMillis() - started);
        settingPort.put(KEY_LAST_RUN, json.writeValueAsString(result), SYSTEM);
        return result;
    }

    private List<String> entities() {
        return memberMapper.selectObjs(new QueryWrapper<MbrMember>().select("DISTINCT entity_no"))
                .stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    /** @return {扫描数, 变更数, 新变沉睡数}（主体级） */
    private int[] recomputeEntity(String entityNo, LevelPolicy policy, long now) {
        long since = now - D90;
        Map<String, Integer> d90 = countOrders(entityNo, since, "member_no");
        Map<String, Integer> d90ByStore = countOrders(entityNo, since, "member_no, store_no");

        int scanned = 0;
        int changed = 0;
        int newlySleeping = 0;
        long cursor = 0;
        while (true) {
            List<MbrMember> page = memberMapper.selectList(Wrappers.<MbrMember>lambdaQuery()
                    .eq(MbrMember::getEntityNo, entityNo)
                    .gt(MbrMember::getId, cursor)
                    .orderByAsc(MbrMember::getId)
                    .last("limit " + PAGE));
            if (page.isEmpty()) {
                break;
            }
            for (MbrMember m : page) {
                cursor = m.getId();
                scanned++;
                int n = d90.getOrDefault(m.getMemberNo(), 0);
                String level = policy.levelOf(n, m.getLastOrderAt(), now);
                if (n == nz(m.getD90OrderCount()) && level.equals(m.getLevel())) {
                    continue;   // 没变就不写：重算要幂等，updated_at 与 version 都不该动
                }
                boolean toSleeping = MbrMember.LEVEL_SLEEPING.equals(level)
                        && !MbrMember.LEVEL_SLEEPING.equals(m.getLevel());
                MbrMember patch = new MbrMember();
                patch.setId(m.getId());
                patch.setVersion(m.getVersion());
                patch.setD90OrderCount(n);
                patch.setLevel(level);
                /*
                 * 带 version 更新：读与写之间若有一笔支付回调改了这一行，这里影响 0 行、跳过 ——
                 * 那一笔已经按即时口径算过，明天再对齐。反过来用无版本的 UPDATE 会把那笔抹掉。
                 */
                if (memberMapper.updateById(patch) == 1) {
                    changed++;
                    if (toSleeping) {
                        newlySleeping++;
                    }
                }
            }
        }
        recomputeStores(entityNo, d90ByStore, policy, now);
        return new int[] {scanned, changed, newlySleeping};
    }

    /** 门店级那一份（按门店经营的商家看的是它）。口径同主体级 */
    private void recomputeStores(String entityNo, Map<String, Integer> d90ByStore,
                                 LevelPolicy policy, long now) {
        long cursor = 0;
        while (true) {
            List<MbrMemberStore> page = storeMapper.selectList(Wrappers.<MbrMemberStore>lambdaQuery()
                    .eq(MbrMemberStore::getEntityNo, entityNo)
                    .gt(MbrMemberStore::getId, cursor)
                    .orderByAsc(MbrMemberStore::getId)
                    .last("limit " + PAGE));
            if (page.isEmpty()) {
                return;
            }
            for (MbrMemberStore s : page) {
                cursor = s.getId();
                int n = d90ByStore.getOrDefault(s.getMemberNo() + "|" + s.getStoreNo(), 0);
                String level = policy.levelOf(n, s.getLastOrderAt(), now);
                if (n == nz(s.getD90OrderCount()) && level.equals(s.getLevel())) {
                    continue;
                }
                MbrMemberStore patch = new MbrMemberStore();
                patch.setId(s.getId());
                patch.setVersion(s.getVersion());
                patch.setD90OrderCount(n);
                patch.setLevel(level);
                storeMapper.updateById(patch);
            }
        }
    }

    /**
     * 按主体数近 90 天的支付笔数。{@code groupBy} 为 {@code member_no} 时键是会员号，
     * 为 {@code member_no, store_no} 时键是「会员号|门店号」（门店号为空的行不计入门店级）。
     */
    private Map<String, Integer> countOrders(String entityNo, long since, String groupBy) {
        boolean byStore = groupBy.contains("store_no");
        QueryWrapper<MbrMemberSource> q = new QueryWrapper<MbrMemberSource>()
                .select(groupBy + ", COUNT(*) AS n")
                .eq("entity_no", entityNo)
                .eq("source_type", MbrMember.SOURCE_ORDER)
                .ge("occurred_at", since)
                .groupBy(groupBy);
        if (byStore) {
            q.isNotNull("store_no");
        }
        Map<String, Integer> out = new HashMap<>();
        for (Map<String, Object> row : sourceMapper.selectMaps(q)) {
            String member = str(row, "member_no");
            if (member == null) {
                continue;
            }
            String key = byStore ? member + "|" + str(row, "store_no") : member;
            Object n = col(row, "n");
            out.put(key, n == null ? 0 : ((Number) n).intValue());
        }
        return out;
    }

    /** H2 返回的列名是大写、MySQL 是原样 —— 按名字取之前先忽略大小写 */
    private static Object col(Map<String, Object> row, String name) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String str(Map<String, Object> row, String name) {
        Object v = col(row, name);
        return v == null ? null : String.valueOf(v);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
