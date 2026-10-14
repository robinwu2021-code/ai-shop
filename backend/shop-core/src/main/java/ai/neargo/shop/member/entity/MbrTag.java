package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 标签字典。<b>{@code tagNo} 不可变、{@code name} 可改</b> ——
 * 关系表存的是号，所以改名一行关系都不用动，历史统计也不断。
 *
 * <p><b>不存人数</b>：那是个会漂的缓存，而标签总量只有几十个，要用时 COUNT 比维护一致性便宜。
 */
@Getter
@Setter
@TableName("mbr_tag")
public class MbrTag extends BaseEntity {

    /** 系统按公开口径算的（沉睡、熟客…）。<b>不可改名、不可合并、商家不可手动打</b> */
    public static final String SYS = "SYS";
    /** 商家自己打的 */
    public static final String MCH = "MCH";

    public static final String ACTIVE = "ACTIVE";
    /** 停用：新的打不上去，已经打的照常显示与可筛 —— 直接删会让历史筛选条件突然少一半人 */
    public static final String DISABLED = "DISABLED";
    /** 已并入别的标签。<b>保留不删</b>：活动受众与筛选条件可能还引用着它 */
    public static final String MERGED = "MERGED";

    private String tagNo;
    private String entityNo;
    private String name;
    private String tagType;
    private String status;
    private String mergedInto;
}
