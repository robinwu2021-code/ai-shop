package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdIndustryFulfillment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Dedicated durable full-suborder journal; unique original suborder prevents alternative-key redispatch. */
@Mapper
public interface IndustryFulfillmentMapper extends BaseMapper<OrdIndustryFulfillment> { }
