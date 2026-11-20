package ai.neargo.shop.paybridge.mapper;
import ai.neargo.shop.trade.entity.PmtMigrationReturn;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface LegacyCouponHistoricalReturnMapper extends BaseMapper<PmtMigrationReturn> {
    @Select("SELECT * FROM pmt_apply WHERE tenant_no=#{tenant} AND order_no=#{order} AND promo_type='COUPON' ORDER BY apply_no FOR UPDATE")
    List<PmtApply> originalUses(@Param("tenant") String tenant,@Param("order") String order);
    @Select("SELECT * FROM pmt_user_coupon WHERE tenant_no=#{tenant} AND user_coupon_no=#{no}")
    PmtUserCoupon originalInstance(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM pmt_migration_return WHERE tenant_no=#{tenant} AND source_usage_no=#{no} AND deleted=0 FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    PmtMigrationReturn lockSource(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM pmt_migration_return WHERE tenant_no=#{tenant} AND return_no=#{no} AND deleted=0 FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    PmtMigrationReturn lockReturn(@Param("tenant") String tenant,@Param("no") String no);
    @Update("UPDATE pmt_migration_return SET pay_state='RESTORED',pay_receipt_no=#{receipt},pay_acked_at=#{at},updated_at=#{at},updated_by=#{actor},version=version+1 "
            +"WHERE tenant_no=#{tenant} AND return_no=#{no} AND version=#{revision} AND pay_state='PENDING' AND deleted=0")
    int acknowledge(@Param("tenant") String tenant,@Param("no") String no,@Param("revision") long revision,
            @Param("receipt") String receipt,@Param("at") LocalDateTime at,@Param("actor") String actor);
    @Select("SELECT c.title FROM pmt_user_coupon u JOIN pmt_coupon c ON c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no WHERE u.tenant_no=#{tenant} AND u.user_coupon_no=#{no}")
    String originalTitle(@Param("tenant") String tenant,@Param("no") String no);
}
