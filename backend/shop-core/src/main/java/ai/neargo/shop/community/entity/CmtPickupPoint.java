package ai.neargo.shop.community.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 自提点（ADR-005 / E14）。**取代了早期的 {@code Merchant.isPickupPoint} 布尔位** ——
 * 那个布尔位表达不了「承接方是一个用户（邻居家）」这种情况。
 *
 * <p>两种类型的规则完全不同，靠 {@link #type} 分流：
 * <ul>
 *   <li>{@code STORE}：商家门店，常驻，收履约服务费，进 B 端履约台</li>
 *   <li>{@code NEIGHBOR}：团发起人自己家，**零报酬**，作用域限一个团，走 C 端轻核销</li>
 * </ul>
 */
@Getter
@Setter
@TableName("cmt_pickup_point")
public class CmtPickupPoint extends BaseEntity {

    private String pickupNo;
    private String communityNo;
    private String name;
    private String address;

    private Integer latE6;
    private Integer lngE6;

    /** STORE / NEIGHBOR */
    private String type;

    /** PERMANENT / GROUP_INSTANCE —— NEIGHBOR 必然是 GROUP_INSTANCE。 */
    private String scope;

    /** 承接方：STORE 填 merchantNo，NEIGHBOR 填 userNo。 */
    private String ownerRef;

    /** NEIGHBOR 专用：限定的团。为空表示常驻点。 */
    private String groupNo;

    private String openHours;

    /** 「每晚 7 点前到货」这类描述，直接展示给用户。 */
    private String arrivalDesc;

    /**
     * 履约服务费费率（万分比，R15/B9 口径待定）。
     * <b>{@code type=NEIGHBOR} 时必须为 0</b> —— 一旦临时点能收钱，就是团长招募换了个名字，
     * ADR-004 消掉的合规问题会原样回来。由 DB 约束 + {@code NeighborPickupZeroFeeTest} 双重拦截。
     */
    private Integer serviceFeeRate;

    /** ACTIVE / SUSPENDED */
    private String status;
    /** 约定取货时段：邻居家不能一直堆着货（B15）。 */
    private String timeSlot;

    /**
     * 按**件**计的履约服务费（分）。与 {@code serviceFeeRate}（费率）是两个口径，不能互相换算。
     * 用哪一个由 {@link #feeMode} 判别 —— 两列并存而没有判别位时，结算侧只能猜，
     * 猜错就是给自提点少付或多付钱。
     * 邻里自提（type=NEIGHBOR）**必须为 0**，否则承接的邻居就成了团长（ADR-005）。
     */
    private Long serviceFeePerItemMinor;

    /**
     * 计费口径：{@code NONE} / {@code PER_ITEM} / {@code RATE}（V18，ADR-009）。
     *
     * <p>之所以两种口径都留而不是统一成一种：平台自提点的费率是**线下逐点协商**的，
     * 必然出现有的点谈成按件、有的谈成按成交额抽成。硬统一会让运营在谈判桌上没有筹码。
     *
     * <p>{@code type=STORE}（商家自有门店）与 {@code NEIGHBOR}（邻居家）恒为 {@code NONE}。
     */
    private String feeMode;

    /**
     * 归档时间。<b>软删除标记</b> —— 有值即从运营端默认列表消失，业务数据全保留。
     *
     * <p>与 {@code status} <b>正交</b>：暂停的还在列表里等着被恢复，
     * 归档的从列表消失。挤进同一列的话，「暂停后归档」会丢掉其中一个状态。
     */
    private java.time.LocalDateTime archivedAt;

}
