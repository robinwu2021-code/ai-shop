package ai.neargo.shop.trade.order.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 子单的售后占位（V406）：一张订单同时只有一张进行中售后，由库保证。不动 version，与状态 CAS 互不干扰。
 * 读写都只走这里 —— 实体里故意不映射这一列（见 V406 注释）。
 */
@Mapper
public interface AfterSaleSlotMapper {

    /** 空位或就是自己（重放）才写入；0 行 = 已有别的进行中售后 */
    @Update(
            "UPDATE ord_sub_order SET open_after_sale_no = #{as} WHERE sub_order_no = #{sub} AND"
                + " admission_kind='LEGACY_FUNDED' AND (open_after_sale_no IS NULL OR"
                + " open_after_sale_no = #{as})")
    int occupy(@Param("sub") String subOrderNo, @Param("as") String afterSaleNo);

    /** 只释放自己占的那一格 */
    @Update("UPDATE ord_sub_order SET open_after_sale_no = NULL WHERE sub_order_no = #{sub} "
            + "AND open_after_sale_no = #{as}")
    int release(@Param("sub") String subOrderNo, @Param("as") String afterSaleNo);
}
