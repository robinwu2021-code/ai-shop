package ai.neargo.shop.marketing.attribution.impl;

import ai.neargo.shop.marketing.attribution.AttributionService;

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

    /** 归因窗口期。默认 30 天（B1），运营可配（P-9.1.2）。 */
    @Value("${shop.attribution.window-days:30}")
    private int windowDays;

    private final AttributionMapper attributionMapper;
    private final AttributionLogMapper logMapper;

    public AttributionServiceImpl(AttributionMapper attributionMapper, AttributionLogMapper logMapper) {
        this.attributionMapper = attributionMapper;
        this.logMapper = logMapper;
    }

    @Override
    @Transactional
    public AttributionVO report(String userNo, Clue clue) {
        String source = sourceOf(clue);
        if (source == null) {
            // 三个线索都没有：不产生归属，也不留痕（没有可判定的东西）
            return current(userNo);
        }

        MktAttribution existing = find(userNo);
        long now = System.currentTimeMillis();
        boolean expired = existing != null
                && (existing.getExpireAt() == null || existing.getExpireAt() < now);

        if (existing != null && !expired
                && MktAttribution.weightOf(existing.getSource()) > MktAttribution.weightOf(source)) {
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
        row.setExpireAt(now + Duration.ofDays(windowDays).toMillis());

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

    /** 线索 → 命中来源。这一处就是优先级的唯一实现。 */
    private String sourceOf(Clue clue) {
        if (notBlank(clue.merchantNo())) {
            return MktAttribution.STORE_CODE;
        }
        if (notBlank(clue.inviterNo())) {
            return MktAttribution.INVITER;
        }
        if (notBlank(clue.channel())) {
            return MktAttribution.CHANNEL;
        }
        return null;
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
