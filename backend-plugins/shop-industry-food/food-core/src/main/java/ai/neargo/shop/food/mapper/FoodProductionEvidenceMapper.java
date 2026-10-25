package ai.neargo.shop.food.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface FoodProductionEvidenceMapper {
    @Select(
            "SELECT source_component_ref FROM fnb_order_component WHERE entity_no=#{entity} AND"
                    + " food_line_no=#{line} AND component_no=#{component} AND deleted=0")
    String componentReference(
            @Param("entity") String entity,
            @Param("line") String line,
            @Param("component") String component);
}
