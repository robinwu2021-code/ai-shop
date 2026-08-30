package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 物料（零售形态即 SKU）：被计数的东西 */
@Getter
@Setter
@TableName("inv_item")
public class InvItem extends InvMutableEntity {

    /** 本域生成。**不复用平台 sku_no** —— 复用之后，交付给没有 sku_no 的客户时主键要重设计 */
    private String itemId;

    private String ownerId;

    /** 业主自己的货号，给人看的。可空，且空是常态 */
    private String itemCode;

    private String name;

    /** 规格描述「5斤装·精选」。展示用，不参与任何计算 */
    private String specText;

    /** 同款分组，仅用于报表归类。**只是一列，不是一张表** —— 做成实体会诱使人给 SPU 也算库存，而那个数没有意义 */
    private String spuId;

    /** 报表分组 */
    private String categoryCode;

    /** ★ 一旦有流水不可改：从「件」改成「斤」，历史数字一个不变而含义全变，且没有任何地方会报错 */
    private String baseUom;

    /** 1=称重品 */
    private Integer weighed;

    /** 留位，一期恒 0。开启后余额要从一个数变成一组批次 */
    private Integer trackBatch;

    /** 留位 */
    private Integer shelfLifeDays;

    /** 安全库存默认阈值，0=不预警。可被库位覆盖 */
    private Integer safetyStock;

    /** LATEST 最新进价 / MANUAL 手工价。留位 MOVING_AVG / FIFO —— 移动加权漏录一次之后所有历史毛利全错**且不报警** */
    private String costMethod;

    /** 当前成本，最小币种单位 */
    private Long defaultCostMinor;

    /** OWN 自有主数据 / SYNCED 从外部投影。**两种交付形态的唯一分叉点** */
    private String dataSource;

    /**
     * 来源商品还在不在架上：{@code 1} 在架 / {@code 0} 已下架 / {@code null} 还没同步过。
     *
     * <p><b>存在的理由是可分辨</b>：线上量到 13 组同名同规格的货，挑货弹层里几行
     * 完全一样（同库位、库存也一样），商家挑哪一行都不知道挑的是什么。
     * 追到主库发现那是几个真实存在的同名商品，其中只有一个在架 ——
     * 标出「已下架」，至少在架那件能认出来。
     *
     * <p><b>{@code null} 不等于在架。</b> 存量物料都是 null，要等下一次商品变更
     * 同步过来才有值。当成在架就是拿默认值冒充事实。
     */
    private Integer sourceOnSale;

    /** ACTIVE / ARCHIVED。归档不删流水 —— 历史账要能查 */
    private String status;

}
