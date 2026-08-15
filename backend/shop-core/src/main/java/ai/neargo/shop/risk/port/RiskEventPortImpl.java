package ai.neargo.shop.risk.port;

import ai.neargo.shop.risk.entity.RiskBlacklist;
import ai.neargo.shop.risk.impl.RiskDetector;
import ai.neargo.shop.risk.mapper.RiskMappers.RiskBlacklistMapper;
import ai.neargo.shop.spi.risk.RiskEventPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * risk 对外的出口。
 *
 * <p>唯一的生产调用方是归因引擎（P-16.2.2 异常裂变：同设备 / 同 IP）——
 * 只有归因链路知道「这个人是被谁、从哪台设备带进来的」。
 *
 * <p>交易与售后那两类**不走这里**：它们由 {@code RiskOutboxConsumer} 订阅
 * 已经在发的领域事件，生产方一行代码都不用改。
 */
@Component
public class RiskEventPortImpl implements RiskEventPort {

    private final RiskDetector detector;
    private final RiskBlacklistMapper blacklistMapper;

    public RiskEventPortImpl(RiskDetector detector, RiskBlacklistMapper blacklistMapper) {
        this.detector = detector;
        this.blacklistMapper = blacklistMapper;
    }

    @Override
    public boolean hit(String type, String subjectType, String subject, String subjectName,
                       String evidenceRef, String detail) {
        return detector.hit(type, subjectType, subject, subjectName, evidenceRef, detail);
    }

    @Override
    public boolean blocked(String subjectType, String subject) {
        return blacklistMapper.exists(Wrappers.<RiskBlacklist>lambdaQuery()
                .eq(RiskBlacklist::getSubjectType, subjectType)
                .eq(RiskBlacklist::getSubject, subject)
                .eq(RiskBlacklist::getActive, true)
                .gt(RiskBlacklist::getUntilAt, LocalDateTime.now()));
    }
}
