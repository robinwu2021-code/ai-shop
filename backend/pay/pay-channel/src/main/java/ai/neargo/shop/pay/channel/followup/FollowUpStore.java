package ai.neargo.shop.pay.channel.followup;

import java.time.Instant;
import java.util.List;

/** {@code pay_channel_followup} 的读写口 */
public interface FollowUpStore {

    /** 登记；同一对象同一操作已有进行中的（{@code open_key} 撞键）→ 不重复登记，返回 false */
    boolean open(FollowUpTask task);

    List<FollowUpTask> due(Instant now, int limit);

    /** 乐观锁领取：把下次时间推到 {@code leaseUntil}，领不到（别的实例先领了）返回 false */
    boolean claim(FollowUpTask task, Instant leaseUntil);

    void resolve(FollowUpTask task, String summary);

    void retry(FollowUpTask task, Instant nextAt, String summary);

    void escalate(FollowUpTask task, String summary);
}
