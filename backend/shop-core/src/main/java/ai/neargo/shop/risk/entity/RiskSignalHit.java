package ai.neargo.shop.risk.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 风控命中流水（append-only）。**阈值判定的唯一数据源。**
 *
 * <p>不另存「恶意退款画像」表：画像就是这份流水的聚合，
 * 另存一张迟早出现「画像说 7 次、点进去只有 4 次」。
 *
 * <p>{@code (type, evidenceRef)} 上的唯一索引是**幂等键** —— Outbox 是至少一次投递，
 * 同一张订单/售后单重投多少次都只计一次。没有它，投递器重启一次就能把一个正常用户
 * 送进黑名单，而这种错事后极难还原。
 */
@Getter
@Setter
@TableName("risk_signal_hit")
public class RiskSignalHit extends BaseEntity {

    private String type;
    private String subjectType;
    private String subject;

    /** 证据单号：orderNo / afterSaleNo / traceNo。 */
    private String evidenceRef;

    private String detail;

    /** epoch ms。窗口计算用 —— 存毫秒而不是 DATETIME，是为了让「最近 N 小时」是一次纯数值比较。 */
    private Long hitAt;
}
