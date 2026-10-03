package ai.neargo.shop.marketing.slot.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.IsoTime;
import ai.neargo.shop.marketing.slot.ContentSlotService;
import ai.neargo.shop.marketing.slot.dto.SlotVOs.ContentSlotVO;
import ai.neargo.shop.marketing.slot.dto.SlotVOs.SlotSaveCmd;
import ai.neargo.shop.marketing.slot.entity.MktContentSlot;
import ai.neargo.shop.marketing.slot.mapper.SlotMappers.ContentSlotMapper;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内容位。<b>平台级配置</b>，不带归属列，因此不进数据域表册 ——
 * 首页展示什么是全平台一致的事，按运营的商家域裁它没有意义。
 */
@Service
public class ContentSlotServiceImpl implements ContentSlotService {

    private static final Set<String> KINDS =
            Set.of(MktContentSlot.HOME_FLOOR, MktContentSlot.BANNER, MktContentSlot.CHANNEL);

    /** 一个楼层里放多少件。超过这个数首页要滑很久，而运营在表单里看不出来。 */
    private static final int MAX_GOODS = 30;

    private final ContentSlotMapper slotMapper;
    private final GoodsQueryPort goodsPort;
    private final ObjectMapper json;

    public ContentSlotServiceImpl(ContentSlotMapper slotMapper, GoodsQueryPort goodsPort, ObjectMapper json) {
        this.slotMapper = slotMapper;
        this.goodsPort = goodsPort;
        this.json = json;
    }

    @Override
    public List<ContentSlotVO> opsSlots(String kind, Boolean enabled, String keyword, boolean showArchived) {
        var w = Wrappers.<MktContentSlot>lambdaQuery()
                .eq(kind != null && !kind.isBlank(), MktContentSlot::getKind, kind)
                .eq(enabled != null, MktContentSlot::getEnabled, enabled)
                .isNull(!showArchived, MktContentSlot::getArchivedAt)
                .orderByAsc(MktContentSlot::getKind)
                .orderByAsc(MktContentSlot::getSortNo)
                .orderByDesc(MktContentSlot::getId);
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(MktContentSlot::getSlotNo, keyword).or().like(MktContentSlot::getTitle, keyword));
        }
        return slotMapper.selectList(w).stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public ContentSlotVO saveSlot(SlotSaveCmd cmd) {
        if (cmd == null || cmd.title() == null || cmd.title().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String kind = cmd.kind() == null ? MktContentSlot.HOME_FLOOR : cmd.kind();
        if (!KINDS.contains(kind)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        long online = requireMillis(cmd.onlineAt());
        long offline = requireMillis(cmd.offlineAt());
        if (offline <= online) {
            // 与运营端 mock 同一条规矩：下线不晚于上线的位子，配好当天就是死的
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<String> goodsNos = checkedGoods(kind, cmd.goodsNos());

        MktContentSlot row = cmd.slotNo() == null || cmd.slotNo().isBlank() ? null : require(cmd.slotNo());
        boolean isNew = row == null;
        if (isNew) {
            row = new MktContentSlot();
            row.setSlotNo(BizKey.next(BizKey.CONTENT_SLOT));
        }
        row.setTitle(cmd.title().trim());
        row.setKind(kind);
        row.setSortNo(cmd.sort() == null ? 0 : cmd.sort());
        row.setCommunityNos(writeJson(dedup(cmd.communityNos())));
        row.setGoodsNos(writeJson(goodsNos));
        row.setOnlineAt(online);
        row.setOfflineAt(offline);
        row.setEnabled(cmd.enabled() == null || cmd.enabled());
        if (isNew) {
            slotMapper.insert(row);
        } else {
            slotMapper.updateById(row);
        }
        return toVO(require(row.getSlotNo()));
    }

    @Override
    @Transactional
    public ContentSlotVO setEnabled(String slotNo, boolean enabled) {
        MktContentSlot row = require(slotNo);
        row.setEnabled(enabled);
        slotMapper.updateById(row);
        return toVO(require(slotNo));
    }

    @Override
    @Transactional
    public ContentSlotVO setSchedule(String slotNo, String onlineAt, String offlineAt) {
        MktContentSlot row = require(slotNo);
        long online = requireMillis(onlineAt);
        long offline = requireMillis(offlineAt);
        if (offline <= online) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        row.setOnlineAt(online);
        row.setOfflineAt(offline);
        slotMapper.updateById(row);
        return toVO(require(slotNo));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 商品校验。<b>HOME_FLOOR 必须有货</b>：没有货的楼层在首页上是一块空白，
     * 而运营看着自己刚保存的配置，以为它在生效。
     *
     * <p>其余形态这一版不带内容（没有承接位），传了也不存 —— 存了会变成
     * 「配置在库里、端上没人读」的第二种假象。
     */
    private List<String> checkedGoods(String kind, List<String> raw) {
        if (!MktContentSlot.HOME_FLOOR.equals(kind)) {
            return List.of();
        }
        List<String> nos = dedup(raw);
        if (nos.isEmpty() || nos.size() > MAX_GOODS) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        for (String no : nos) {
            // 货号打错一位不会有任何报错，那一格在首页上**静默消失**
            if (goodsPort.snapshotOfGoods(no).isEmpty()) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
        }
        return nos;
    }

    /** 去重但**保持顺序** —— 顺序就是展示顺序，用 Set 会把它洗掉。 */
    private static List<String> dedup(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String s : raw) {
            if (s != null && !s.isBlank()) {
                seen.add(s.trim());
            }
        }
        return new ArrayList<>(seen);
    }

    private long requireMillis(String iso) {
        var at = IsoTime.parse(iso);
        if (at == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private MktContentSlot require(String slotNo) {
        MktContentSlot row = slotMapper.selectOne(Wrappers.<MktContentSlot>lambdaQuery()
                .eq(MktContentSlot::getSlotNo, slotNo).last("limit 1"));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    /**
     * 空列表写成 {@code "[]"} 而不是 null。
     *
     * <p><b>写 null 的话「清空」这件事根本不会发生</b>：MyBatis-Plus 的
     * {@code updateById} 会跳过为 null 的字段，那句 set 压根不生成 ——
     * 运营把投放社区删干净、保存、页面提示成功，而库里还是原来那几个社区。
     */
    private String writeJson(List<String> v) {
        return json.writeValueAsString(v == null ? List.of() : v);
    }

    private List<String> readJson(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(v, new tools.jackson.core.type.TypeReference<List<String>>() { });
        } catch (RuntimeException e) {
            // 手改过库、或者早期写坏的行。返回空好过让整页 500
            return List.of();
        }
    }

    private ContentSlotVO toVO(MktContentSlot r) {
        return new ContentSlotVO(r.getSlotNo(), r.getTitle(), r.getKind(),
                r.getSortNo() == null ? 0 : r.getSortNo(),
                readJson(r.getCommunityNos()), readJson(r.getGoodsNos()),
                IsoTime.toIso(r.getOnlineAt()), IsoTime.toIso(r.getOfflineAt()),
                Boolean.TRUE.equals(r.getEnabled()),
                r.getArchivedAt() == null ? null
                        : r.getArchivedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }
}
