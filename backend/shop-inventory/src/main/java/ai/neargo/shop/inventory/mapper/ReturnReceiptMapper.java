package ai.neargo.shop.inventory.mapper;

import ai.neargo.shop.inventory.entity.InvReturnReceipt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Both return writers take current claims after canonical original reservation roots. */
public interface ReturnReceiptMapper extends BaseMapper<InvReturnReceipt> {
    @Select("<script>SELECT * FROM inv_return_receipt"
            + " WHERE original_line_id IN"
            + " <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY original_line_id,id FOR UPDATE</script>")
    List<InvReturnReceipt> lockByOriginalLines(@Param("ids") List<Long> ids);
}
