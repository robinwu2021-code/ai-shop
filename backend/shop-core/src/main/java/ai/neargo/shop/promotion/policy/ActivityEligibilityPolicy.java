package ai.neargo.shop.promotion.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalTime;
import org.springframework.stereotype.Component;

@Component
public class ActivityEligibilityPolicy {
    private final MemberQueryPort members;
    public ActivityEligibilityPolicy(MemberQueryPort members) { this.members = members; }
    public void requireEligible(UnifiedActivityDefinition d, String audience, ReserveActivity command, Instant now) {
        var s = d.schedule();
        if (!d.currency().equals(command.currency()) || now.isBefore(s.startAt())
                || (s.endAt() != null && !now.isBefore(s.endAt()))) deny();
        var scope = d.storeParticipationScope();
        if (!"ALL".equals(scope.mode()) && !scope.refs().contains(command.storeNo())) deny();
        var local = now.atZone(ZoneId.of(s.zone()));
        if (!s.weekdays().isEmpty() && !s.weekdays().contains(local.getDayOfWeek().getValue())) deny();
        if (s.from() != null && s.to() != null) {
            var from = LocalTime.parse(s.from()); var to = LocalTime.parse(s.to()); var t = local.toLocalTime();
            boolean valid = from.isBefore(to) ? !t.isBefore(from) && t.isBefore(to)
                    : !t.isBefore(from) || t.isBefore(to);
            if (!valid) deny();
        }
        if (audience != null && !members.matchesRule(command.entityNo(), command.userNo(), audience)) deny();
    }
    private static void deny() { throw BizException.of(ErrorCode.FORBIDDEN); }
}
