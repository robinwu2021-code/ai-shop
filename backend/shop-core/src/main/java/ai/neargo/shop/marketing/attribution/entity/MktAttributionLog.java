package ai.neargo.shop.marketing.attribution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 归因判定留痕（append-only）。
 *
 * <p>**每次判定都记，包括「没有改变归属」的那次**。归因决定费率档（R16），
 * 商家会为「这个客户算不算我带来的」争执 —— 争议发生在几个月后，
 * 那时唯一能还原当时判定的就是这张表。只记「改变了归属」的话，
 * 「为什么我扫了码却没算我的」这个问题永远答不上来。
 */
@Getter
@Setter
@TableName("mkt_attribution_log")
public class MktAttributionLog {

    public static final String CREATED = "CREATED";
    public static final String REPLACED = "REPLACED";
    public static final String KEPT = "KEPT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userNo;
    private String entityNo;
    private String inviterNo;
    private String channel;
    private String source;

    /** CREATED / REPLACED / KEPT */
    private String decision;

    private String prevSource;
    private String prevRef;

    /** 人可读的判定依据 —— 客服要能直接念给商家听。 */
    private String reason;

    private Long at;

    // ---------------------------------------------------------------- V121

    /**
     * 归因链路单号（{@code AT...}）。老行为空时端上回落成 {@code AT{id}}。
     *
     * <p>它是客服与商家争执时唯一能对上的抓手 —— 「你说的是哪一次判定」。
     */
    private String traceNo;

    /**
     * 上报设备号 / IP。
     *
     * <p><b>没有这两列，P-16.2.2「异常裂变（同设备/同 IP）」就退化成
     * 「某人邀请人数多」</b> —— 那不是需求写的那件事，但看起来很像：
     * 一个真的在拉人的店主会被判成刷单，而真刷单的人换个账号就绕过去了。
     */
    private String deviceId;
    private String ip;

    /** 该用户首单，由 {@code ORDER_CREATED} 事件回填。归因值不值钱看这一列。 */
    private String orderNo;

    /** 判定时算出的风控信号，与风险事件同一套口径（不另造一份平行数据）。 */
    private String riskSignals;

    private String tenantNo;
    private LocalDateTime createdAt;
}
