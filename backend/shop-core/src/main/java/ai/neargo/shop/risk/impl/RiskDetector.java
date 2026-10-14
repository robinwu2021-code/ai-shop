package ai.neargo.shop.risk.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.risk.entity.RiskEvent;
import ai.neargo.shop.risk.entity.RiskRule;
import ai.neargo.shop.risk.entity.RiskSignalHit;
import ai.neargo.shop.risk.mapper.RiskMappers.RiskEventMapper;
import ai.neargo.shop.risk.mapper.RiskMappers.RiskRuleMapper;
import ai.neargo.shop.risk.mapper.RiskMappers.RiskSignalHitMapper;
import ai.neargo.shop.spi.risk.RiskEventPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 识别：命中记流水 → 流水过阈值开事件。
 *
 * <p><b>不是 Service 也不是 Port</b>，是两者共用的那一段：
 * {@code RiskEventPortImpl}（增长域推来的同设备/同 IP）与 {@code RiskOutboxConsumer}
 * （交易与售后事件）走的是同一套判定，写两遍必然漂移 ——
 * 而漂移的表现是「同样的行为，从这条路进来会开单，从那条路进来不会」。
 *
 * <p>阈值判定的**唯一数据源**是 {@code risk_signal_hit}。不另存用户画像表：
 * 画像就是这份流水的聚合，另存一张迟早出现「画像说 7 次、点进去只有 4 次」。
 */
@Component
public class RiskDetector {

    /**
     * 三类风险的出厂默认值 {@code {阈值, 窗口小时数}}。
     *
     * <p><b>不写在迁移的 INSERT 里</b>：{@code gen-test-schema.py} 只重放 DDL，
     * 迁移里的 INSERT 进不了 {@code schema-test.sql} —— 靠它做种子的话单测库里
     * 永远是空表，而「规则列表是空的」在页面上看起来只是「还没配」。
     */
    static final Map<String, int[]> DEFAULTS = Map.of(
            // 24 小时 10 单：正常家庭买菜到不了这个数，刷手一晚上远超
            RiskEventPort.FAKE_ORDER, new int[]{10, 24},
            // 同设备/同 IP 在归因窗口内带进 5 个不同的人
            RiskEventPort.ABNORMAL_FISSION, new int[]{5, 24 * 30},
            // 30 天 5 次退款
            RiskEventPort.MALICIOUS_REFUND, new int[]{5, 24 * 30});

    private final RiskEventMapper eventMapper;
    private final RiskSignalHitMapper hitMapper;
    private final RiskRuleMapper ruleMapper;

    public RiskDetector(RiskEventMapper eventMapper, RiskSignalHitMapper hitMapper,
                        RiskRuleMapper ruleMapper) {
        this.eventMapper = eventMapper;
        this.hitMapper = hitMapper;
        this.ruleMapper = ruleMapper;
    }

    /**
     * 记一次命中；累计过阈值时开（或追加到）一张风险事件。
     *
     * @return 该主体该类型当前是否已有待处置事件
     */
    @Transactional
    public boolean hit(String type, String subjectType, String subject, String subjectName,
                       String evidenceRef, String detail) {
        if (blank(subject) || blank(evidenceRef)) {
            return false;
        }
        /*
         * 先查后插，**不靠 catch 唯一键异常**：Outbox 是 at-least-once 投递，
         * 同一张订单可能被投两次；而在事务里吃掉一个 DuplicateKeyException 会让
         * Spring 把事务标成 rollback-only —— 消费者看起来成功了，实际什么都没落库，
         * 于是这条事件被无限重投。并发由 ShedLock 挡，唯一索引兜最后一层。
         */
        boolean seen = hitMapper.exists(Wrappers.<RiskSignalHit>lambdaQuery()
                .eq(RiskSignalHit::getType, type)
                .eq(RiskSignalHit::getEvidenceRef, evidenceRef));
        if (seen) {
            return openEventOf(type, subject) != null;
        }

        long now = System.currentTimeMillis();
        RiskSignalHit row = new RiskSignalHit();
        row.setType(type);
        row.setSubjectType(subjectType);
        row.setSubject(subject);
        row.setEvidenceRef(evidenceRef);
        row.setDetail(detail);
        row.setHitAt(now);
        row.setTenantNo("MAIN");
        hitMapper.insert(row);

        RiskRule rule = ruleOf(type);
        long since = now - rule.getWindowHours() * 3600_000L;
        List<RiskSignalHit> window = hitMapper.selectList(Wrappers.<RiskSignalHit>lambdaQuery()
                .eq(RiskSignalHit::getType, type)
                .eq(RiskSignalHit::getSubject, subject)
                .ge(RiskSignalHit::getHitAt, since)
                .orderByDesc(RiskSignalHit::getHitAt));
        if (window.size() < rule.getThreshold()) {
            return false;
        }
        raise(type, subjectType, subject, subjectName, rule, window);
        return true;
    }

    /**
     * 读时自愈：缺的那条按出厂值补上。
     *
     * <p>包内可见 —— {@code RiskServiceImpl} 的规则列表走同一个入口，
     * 否则运营端第一次打开「拦截规则」会看到空列表，而识别其实已经在按默认值跑了。
     */
    @Transactional
    RiskRule ruleOf(String type) {
        RiskRule r = ruleMapper.selectOne(Wrappers.<RiskRule>lambdaQuery()
                .eq(RiskRule::getType, type).last("limit 1"));
        if (r != null) {
            return r;
        }
        int[] def = DEFAULTS.getOrDefault(type, new int[]{10, 24});
        RiskRule fresh = new RiskRule();
        fresh.setType(type);
        fresh.setThreshold(def[0]);
        fresh.setWindowHours(def[1]);
        fresh.setAutoBlock(false);
        fresh.setTenantNo("MAIN");
        ruleMapper.insert(fresh);
        return fresh;
    }

    /**
     * 开单或追加。
     *
     * <p><b>同一 (type, subject) 在处置完成前只有一张待办</b> —— 刷单的人一晚上下 200 单，
     * 逐单开事件会让队列直接失去可用性；而队列一旦没法用，风控就等于没做。
     */
    private void raise(String type, String subjectType, String subject, String subjectName,
                       RiskRule rule, List<RiskSignalHit> window) {
        String signal = window.size() + " 次命中 / " + windowText(rule.getWindowHours());
        List<String> refs = window.stream().map(RiskSignalHit::getEvidenceRef).limit(20).toList();

        RiskEvent open = openEventOf(type, subject);
        if (open != null) {
            open.setHitCount(window.size());
            open.setSignals(joinDistinct(open.getSignals(), signal, latestDetail(window)));
            open.setRefs(String.join(",", refs));
            eventMapper.updateById(open);
            return;
        }

        RiskEvent event = new RiskEvent();
        event.setRiskEventNo(BizKey.next(BizKey.RISK_EVENT));
        event.setType(type);
        event.setSubjectType(subjectType);
        event.setSubject(subject);
        event.setSubjectName(subjectName);
        event.setSignals(joinDistinct(null, signal, latestDetail(window)));
        event.setRefs(String.join(",", refs));
        event.setHitCount(window.size());
        event.setStatus(RiskEvent.PENDING);
        event.setDedupKey(RiskEvent.openKey(type, subject));
        event.setTenantNo("MAIN");
        eventMapper.insert(event);
    }

    private RiskEvent openEventOf(String type, String subject) {
        return eventMapper.selectOne(Wrappers.<RiskEvent>lambdaQuery()
                .eq(RiskEvent::getDedupKey, RiskEvent.openKey(type, subject)).last("limit 1"));
    }

    private static String latestDetail(List<RiskSignalHit> window) {
        return window.isEmpty() ? null : window.getFirst().getDetail();
    }

    private static String windowText(int hours) {
        return hours % 24 == 0 ? (hours / 24) + " 天" : hours + " 小时";
    }

    /** 信号是给人看的证据摘要，重复的短语只留一条。 */
    private static String joinDistinct(String existing, String... more) {
        Set<String> all = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            all.addAll(Arrays.asList(existing.split(",")));
        }
        for (String s : more) {
            if (!blank(s)) {
                all.add(s.trim());
            }
        }
        return String.join(",", all);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
