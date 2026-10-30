package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdChannelFulfillmentEvidence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;

/** Only original primary fulfillment owner appends and reads evidence. */
@Mapper
public interface ChannelFulfillmentEvidenceMapper
        extends BaseMapper<OrdChannelFulfillmentEvidence> {}
