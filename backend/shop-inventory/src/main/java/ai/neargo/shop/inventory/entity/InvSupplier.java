package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 供应商档案：进货单指向的那个稳定对象。
 *
 * <p>进货单此前只有一个 {@code supplier_name} 字符串，进货页上写着
 * 「仅作记录，不建立供应商档案」。名字不够是因为它会漂 —— 同一家「老周粮油」
 * 会被打成三种写法，而进货报表按名字聚合，商家看到的是三个供应商。
 *
 * <p><b>继承 {@link InvMutableEntity}</b>：档案是会被改的（改联系人、停用），
 * 与流水那种一次性写入的表不是一回事。
 */
@Getter
@Setter
@TableName("inv_supplier")
public class InvSupplier extends InvMutableEntity {

    private String supplierNo;

    /**
     * 商家隔离。<b>进销存不走平台的 DataScope</b> —— 那套机制只覆盖平台迁移里的表，
     * {@code inv_*} 一张都不在。每个查询都要显式 {@code .eq(ownerId)}，
     * <b>漏一处就是跨商家泄露，而它不会报错</b>。
     */
    private String ownerId;

    /**
     * 空 = 商家自建；非空 = 引用平台档案，此时名称与联系方式商家只读。
     *
     * <p><b>平台那张表这一轮不建，这一列先留。</b> 留一个可空列的成本接近零，
     * 而事后补的代价是一次人工归并 —— 平台层落地时商家早已自建了一批同名档案。
     */
    private String platformSupplierNo;

    private String name;

    /** 单据列表上显示它：长名换行会把一行撑成两行 */
    private String shortName;

    private String contactName;

    private String contactPhone;

    /** {@code ACTIVE} / {@code ARCHIVED}。<b>停用不删除</b> —— 历史单据要指得回去 */
    private String status;

    /** 引用平台档案时这一列仍归商家写 —— 那是他自己的话 */
    private String remark;
}
