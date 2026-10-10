package ai.neargo.shop.marketing.slot.mapper;

import ai.neargo.shop.marketing.slot.entity.MktContentSlot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 内容位的 Mapper。 */
public final class SlotMappers {

    private SlotMappers() {
    }

    public interface ContentSlotMapper extends BaseMapper<MktContentSlot> {
    }
}
