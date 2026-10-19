package ai.neargo.shop.trade.order.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** One old compensated case maps to one new immutable refund attempt. */
@Mapper
public interface OrderPointsRefundRetryMapper {
  @Select("SELECT * FROM ord_points_refund_retry WHERE old_after_sale_no=#{old}")
  Map<String, Object> find(String old);

  @Insert(
      "INSERT INTO ord_points_refund_retry(old_after_sale_no,new_after_sale_no,operator_no,reason)"
          + " VALUES(#{old},#{next},#{operator},#{reason})")
  int create(
      @Param("old") String old,
      @Param("next") String next,
      @Param("operator") String operator,
      @Param("reason") String reason);
}
