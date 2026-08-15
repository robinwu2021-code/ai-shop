package ai.neargo.shop.marketing.attribution.impl;

import ai.neargo.shop.marketing.attribution.AttributionRuleService;
import ai.neargo.shop.marketing.attribution.AttributionService;
import ai.neargo.shop.marketing.attribution.entity.MktAttributionRule;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.marketing.AttributionPort;
import ai.neargo.shop.marketing.attribution.dto.AttributionVO;
import ai.neargo.shop.marketing.attribution.entity.MktAttribution;
import ai.neargo.shop.marketing.attribution.entity.MktAttributionLog;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.AttributionLogMapper;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.AttributionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class AttributionServiceImpl implements AttributionService {

    /**
     * 归因窗口期的**兜底值**。真值来自 {@link AttributionRuleService#current()}（V121）——
     * 此前这里是唯一来源，于是运营端 P-9.1.2 那一页改完什么都不会发生。
     * 配置项保留，只在规则表还没有行时生效。
     */
    @Value("${shop.attribution.window-days:30}")
    private int windowDays;

    private final AttributionMapper attributionMapper;
    private final AttributionLogMapper logMapper;
    /** 规则驱动引擎：优先级、窗口期、冲突策略都读它 */
    private final AttributionRuleService ruleService;

    public AttributionServiceImpl(AttributionMapper attributionMapper, AttributionLogMapper logMapper,
                                  AttributionRuleService ruleService) {
        this.attributionMapper = attributionMapper;
        this.logMapper = logMapper;
        this.ruleService = ruleService;
    }

    @Override
    @Transactional
    public AttributionVO report(String userNo, Clue clue) {
        // 每次判定都读一次规则 —— 运营改完下一单就生效，这正是这张表存在的意义
        AttributionRuleService.RuleVO rule = ruleService.current();
        String source = sourceOf(clue, rule.priorityList());
        if (source == null) {
            // 三个线索都没有：不产生归属，也不留痕（没有可判定的东西）
            return current(userNo);
        }

        MktAttribution existing = find(userNo);
        long now = System.currentTimeMillis();
        boolean expired = existing != null
                && (existing.getExpireAt() == null || existing.getExpireAt() < now);

        /*
         * KEEP_FIRST：窗口期内先来的说了算，任何来源都不覆盖。
         * 与「弱来源不覆盖强来源」是两条独立的闸 —— OVERWRITE 下仍然要判后者。
         *
         * ASK_USER 一期不实现（要端上交互），按 OVERWRITE 走；
         * 存得下是为了运营端能先配、后端后补，但**行为上不假装它生效了**。
         */
        boolean keepFirst = MktAttributionRule.KEEP_FIRST.equals(rule.conflictPolicy());
        if (existing != null && !expired && keepFirst) {
            log(userNo, clue, source, MktAttributionLog.KEPT, existing,
                    "冲突策略 KEEP_FIRST：窗口期内保留原归属 " + existing.getSource());
            return toVO(existing);
        }

        if (existing != null && !expired
                && weightOf(existing.getSource(), rule.priorityList())
                        > weightOf(source, rule.priorityList())) {
            // 弱来源不覆盖强来源。**这一次也要留痕** —— 商家问「我的码为什么没算」时要能答
            log(userNo, clue, source, MktAttributionLog.KEPT, existing,
                    "已有更强来源 " + existing.getSource() + "，本次 " + source + " 不覆盖");
            return toVO(existing);
        }

        String decision = existing == null ? MktAttributionLog.CREATED
                : (expired ? MktAttributionLog.CREATED : MktAttributionLog.REPLACED);
        String reason = existing == null ? "首次归因"
                : (expired ? "原归属已过窗口期，重建" : "同级或更强来源覆盖");
        log(userNo, clue, source, decision, existing, reason);

        MktAttribution row = existing == null ? new MktAttribution() : existing;
        row.setUserNo(userNo);
        row.setSource(source);
        // 只写命中来源对应的字段，其余留空 —— 混着写会让「到底按哪个算的」变成猜谜
        row.setEntityNo(MktAttribution.STORE_CODE.equals(source) ? clue.merchantNo() : null);
        row.setInviterNo(MktAttribution.INVITER.equals(source) ? clue.inviterNo() : null);
        row.setChannel(MktAttribution.CHANNEL.equals(source) ? clue.channel() : null);
        // 窗口期取规则值（配置项只在规则表还没有行时兜底）
        row.setExpireAt(now + Duration.ofDays(rule.windowDays()).toMillis());

        DataScopeContext.executeWithoutScope(() ->
                existing == null ? attributionMapper.insert(row) : attributionMapper.updateById(row));
        return toVO(row);
    }

    @Override
    public AttributionVO current(String userNo) {
        MktAttribution row = find(userNo);
        if (row == null || row.getExpireAt() == null || row.getExpireAt() < System.currentTimeMillis()) {
            return null;
        }
        return toVO(row);
    }

    /**
     * 线索 → 命中来源，<b>按规则里的优先级顺序</b>取第一个有值的。
     *
     * <p>此前这里是写死的 if 链，于是运营端 P-9.1.1 那一页调整优先级不会有任何效果。
     * 现在顺序来自规则表，默认值与原先的 if 链逐字等价（STORE_CODE > INVITER > CHANNEL）。
     */
    private String sourceOf(Clue clue, java.util.List<String> priority) {
        for (String source : priority) {
            String value = switch (source) {
                case MktAttribution.STORE_CODE -> clue.merchantNo();
                case MktAttribution.INVITER -> clue.inviterNo();
                case MktAttribution.CHANNEL -> clue.channel();
                default -> null;
            };
            if (notBlank(value)) {
                return source;
            }
        }
        return null;
    }

    /**
     * 按规则里的优先级算权重：越靠前越强。
     *
     * <p>不再用 {@link MktAttribution#weightOf} 那份写死的表 —— 两处各算一遍的话，
     * 运营调了顺序之后「命中哪个来源」变了、而「谁能覆盖谁」没变，
     * 得到的归因结果没有任何一致的解释。
     */
    private static int weightOf(String source, java.util.List<String> priority) {
        int idx = priority.indexOf(source);
        // 不在优先级表里的来源权重最低（存量数据可能有历史来源值）
        return idx < 0 ? 0 : priority.size() - idx;
    }

    private MktAttribution find(String userNo) {
        return DataScopeContext.executeWithoutScope(() ->
                attributionMapper.selectOne(Wrappers.<MktAttribution>lambdaQuery()
                        .eq(MktAttribution::getUserNo, userNo).last("limit 1")));
    }

    private void log(String userNo, Clue clue, String source, String decision,
                     MktAttribution prev, String reason) {
        MktAttributionLog entry = new MktAttributionLog();
        entry.setUserNo(userNo);
        entry.setEntityNo(clue.merchantNo());
        entry.setInviterNo(clue.inviterNo());
        entry.setChannel(clue.channel());
        entry.setSource(source);
        entry.setDecision(decision);
        entry.setPrevSource(prev == null ? null : prev.getSource());
        entry.setPrevRef(prev == null ? null : firstNonBlank(prev.getEntityNo(),
                prev.getInviterNo(), prev.getChannel()));
        entry.setReason(reason);
        entry.setAt(System.currentTimeMillis());
        entry.setTenantNo("MAIN");
        entry.setCreatedAt(LocalDateTime.now());
        logMapper.insert(entry);
    }

    private AttributionVO toVO(MktAttribution row) {
        // 只有店铺码归因算商家自带客流（ADR-004 §6）
        String traffic = MktAttribution.STORE_CODE.equals(row.getSource())
                ? AttributionPort.MERCHANT_OWNED : AttributionPort.PLATFORM;
        return new AttributionVO(row.getEntityNo(), row.getInviterNo(), row.getChannel(),
                row.getSource(), traffic, row.getExpireAt() == null ? 0L : row.getExpireAt());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (notBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
