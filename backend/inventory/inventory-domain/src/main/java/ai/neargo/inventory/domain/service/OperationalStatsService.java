package ai.neargo.inventory.domain.service;

/**
 * 运行指标的数据源（TDD §2.18 T6.2）：事件积压、游标延迟、冻结超时。独立进程把它们挂成监控指标。
 *
 * <p>全部是全库汇总、不分业主：告警看的是「这一套有没有出事」，具体是谁再查明细。
 */
public interface OperationalStatsService {

    /** 业务已提交、还没被拉取方排上序号的事件条数。持续增长 = 宿主不来拉了。 */
    long pendingEvents();

    /** 已排序、宿主还没确认的事件条数（各订阅的确认位置之后）。 */
    long unackedEvents();

    /** 开着的盘点里最老的一张开了多少分钟。盘点冻结着范围内的进出，挂一夜就是事故。 */
    long oldestOpenCountMinutes();

    /** 「退货待确认」冻结里最老的一条挂了多少分钟：宿主忘了做业务确认，设备就一直发不出去。 */
    long oldestReturnHoldMinutes();
}
