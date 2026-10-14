package ai.neargo.shop.inventory.service;

import ai.neargo.shop.inventory.entity.InvSupplier;

import java.util.List;

/**
 * 供应商档案。进货单指向的那个**稳定对象** —— 在它之前只有一个
 * {@code supplier_name} 字符串，而名字会漂（同一家「老周粮油」三种写法，
 * 进货报表按名字聚合就成了三个供应商）。
 *
 * <p><b>每个方法都收 ownerId，且实现里必须显式 {@code .eq(ownerId)}。</b>
 * 进销存不走平台的 DataScope —— 那套机制只覆盖平台迁移里的表，
 * {@code inv_*} 一张都不在。漏一处就是跨商家泄露，而它不会报错。
 */
public interface SupplierService {

    /**
     * 列表。
     *
     * @param keyword    名称模糊匹配；空 = 不筛
     * @param activeOnly true = 只要在用的。**挑供应商时传 true，管理页传 false** ——
     *                   停用的不该出现在新单据的选择器里，但管理页要看得见它
     */
    List<InvSupplier> list(String ownerId, String keyword, boolean activeOnly);

    /**
     * 建档，返回 {@code supplierNo}。
     *
     * @throws ai.neargo.shop.common.BizException {@code CONFLICT} —— 同一商家已有同名。
     *         <b>这条不是可选的校验</b>：不拦的话，漂移只是从「单据上的名字」
     *         换到「档案里的名字」继续长，而这张表存在的理由就没了。
     */
    String create(String ownerId, InvSupplier form, String operator);

    /**
     * 改档。
     *
     * <p><b>引用平台档案的（{@code platformSupplierNo} 非空）只能改备注</b> ——
     * 名称与联系方式跟平台走，商家在这儿改了，下次平台同步就被盖掉，
     * 而他不会知道自己改的东西没了。
     */
    void update(String ownerId, String supplierNo, InvSupplier form, String operator);

    /**
     * 停用 / 启用。**不删除** —— 历史单据要指得回去。
     */
    void setActive(String ownerId, String supplierNo, boolean active, String operator);
}
