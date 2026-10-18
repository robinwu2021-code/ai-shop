package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtPresetTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Catalog reads include disabled rows so the merchant can understand unavailable recommendations. */
public interface PmtPresetTemplateMapper extends BaseMapper<PmtPresetTemplate> {
    @Select("SELECT * FROM pmt_preset_template WHERE tenant_no=#{tenant} AND catalog_kind=#{kind} "
            + "AND deleted=0 ORDER BY template_code,template_version DESC")
    List<PmtPresetTemplate> catalog(@Param("tenant") String tenant, @Param("kind") String kind);
}
