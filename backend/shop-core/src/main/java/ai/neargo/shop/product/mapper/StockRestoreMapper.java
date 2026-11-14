package ai.neargo.shop.product.mapper;

import ai.neargo.shop.product.entity.PrdStockRestore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** Original stock roots serialize claims; current reads include deleted/invalid facts for fail-closed validation. */
public interface StockRestoreMapper extends BaseMapper<PrdStockRestore> {
    @Select("""
            <script>SELECT * FROM prd_stock_restore WHERE tenant_no='MAIN' AND original_lock_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY original_lock_id,id FOR UPDATE</script>
            """)
    List<PrdStockRestore> lockOriginalClaims(@Param("ids") List<Long> ids);
}
