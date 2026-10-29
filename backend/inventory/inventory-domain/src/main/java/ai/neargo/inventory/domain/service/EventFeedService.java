package ai.neargo.inventory.domain.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 宿主拉取事件（TDD §2.12，T4.6）。
 *
 * <p><b>为什么拉不推</b>：推要知道每个宿主的地址与可用性；拉只要一个游标，宿主宕机一天回来接着拉。
 * <b>为什么提交后才分配序号</b>：按自增 id 拉，一个先拿到 id、后提交的事务会被游标越过而永远漏掉。
 */
public interface EventFeedService {

    int MAX_PAGE = 500;

    /**
     * @param afterSeq 从哪个序号之后拉；空 = 从上次确认的位置
     * @throws ai.neargo.inventory.api.InventoryException {@code EVENTS_CURSOR_EXPIRED} 游标早于已清理的位置
     */
    Page pull(Caller caller, Long afterSeq, int limit);

    /** 确认到某个序号（只会前进，不会后退）。 */
    void ack(Caller caller, long seq);

    /** 清理已确认的事件（平台运维 / 定时任务）。返回删了几条。 */
    int prune(String ownerId, String systemCode);

    record Page(List<Event> events, long lastSeq, long ackedSeq) {
    }

    record Event(long seq, String eventId, String type, String ownerId, String resourceType, String resourceId,
                 int resourceVersion, LocalDateTime occurredAt, String actor, String traceId, String payload) {
    }
}
