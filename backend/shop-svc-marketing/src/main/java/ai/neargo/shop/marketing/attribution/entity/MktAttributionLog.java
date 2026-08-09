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
    private String tenantNo;
    private LocalDateTime createdAt;
}
