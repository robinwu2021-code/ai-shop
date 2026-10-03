package ai.neargo.shop.marketing.port;

import ai.neargo.shop.marketing.slot.entity.MktContentSlot;
import ai.neargo.shop.marketing.slot.mapper.SlotMappers.ContentSlotMapper;
import ai.neargo.shop.spi.marketing.ContentSlotPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** product → marketing：首页楼层（{@link ContentSlotPort}）。 */
@Component
public class ContentSlotPortImpl implements ContentSlotPort {

    private final ContentSlotMapper slotMapper;
    private final ObjectMapper json;

    public ContentSlotPortImpl(ContentSlotMapper slotMapper, ObjectMapper json) {
        this.slotMapper = slotMapper;
        this.json = json;
    }

    @Override
    public List<String> homeFloorGoodsNos(String communityNo) {
        long now = System.currentTimeMillis();
        List<MktContentSlot> rows = slotMapper.selectList(Wrappers.<MktContentSlot>lambdaQuery()
                .eq(MktContentSlot::getKind, MktContentSlot.HOME_FLOOR)
                .eq(MktContentSlot::getEnabled, true)
                .isNull(MktContentSlot::getArchivedAt)
                .le(MktContentSlot::getOnlineAt, now)
                .gt(MktContentSlot::getOfflineAt, now)
                .orderByAsc(MktContentSlot::getSortNo)
                .orderByAsc(MktContentSlot::getId));

        // 跨楼层去重但保序：同一件货被两个楼层都配上时，按先出现的那个位置展示
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (MktContentSlot r : rows) {
            if (!hits(r.getCommunityNos(), communityNo)) {
                continue;
            }
            out.addAll(readList(r.getGoodsNos()));
        }
        return new ArrayList<>(out);
    }

    /**
     * 投放范围命中判断。<b>位子没写社区 = 投全部社区</b>（与商品社区池相反的默认，
     * 因为版位是运营给全平台配的，而商品是商家一件件铺进社区的）。
     */
    private boolean hits(String communityNosJson, String communityNo) {
        List<String> scope = readList(communityNosJson);
        if (scope.isEmpty()) {
            return true;
        }
        return communityNo != null && !communityNo.isBlank() && scope.contains(communityNo);
    }

    private List<String> readList(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(v, new tools.jackson.core.type.TypeReference<List<String>>() { });
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
