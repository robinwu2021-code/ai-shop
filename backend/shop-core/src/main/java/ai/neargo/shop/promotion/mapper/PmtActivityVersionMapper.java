package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Core persistence adapter for pmt_activity_version. */
public interface PmtActivityVersionMapper extends BaseMapper<PmtActivityVersion> {
    @org.apache.ibatis.annotations.Select("SELECT COALESCE(MAX(version_index),0) FROM pmt_activity_version "
            + "WHERE tenant_no=#{tenant} AND activity_no=#{activity} AND deleted=0")
    int lastIndex(@org.apache.ibatis.annotations.Param("tenant") String tenant,
                  @org.apache.ibatis.annotations.Param("activity") String activity);
}
