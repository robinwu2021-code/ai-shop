package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtActivityScope;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Core persistence adapter for pmt_activity_scope. */
public interface PmtActivityScopeMapper extends BaseMapper<PmtActivityScope> {
    @Select("""
            <script>
            <choose>
            <when test="revision != null">
            SELECT ref_no FROM pmt_activity_scope
            WHERE tenant_no=#{tenant} AND owner_type='ENROLLMENT' AND owner_no=#{enrollment}
              AND version_no=#{revision} AND scope_dimension='TARGET' AND scope_kind='GOODS' AND deleted=0
            ORDER BY sort_no, id
            </when>
            <otherwise>
            SELECT goods_no FROM pmt_enrollment_goods
            WHERE tenant_no=#{tenant} AND enrollment_no=#{enrollment} AND deleted=0 ORDER BY id
            </otherwise>
            </choose>
            </script>
            """)
    List<String> enrollmentGoods(@Param("tenant") String tenant, @Param("enrollment") String enrollment,
                                 @Param("revision") String revision);
}
