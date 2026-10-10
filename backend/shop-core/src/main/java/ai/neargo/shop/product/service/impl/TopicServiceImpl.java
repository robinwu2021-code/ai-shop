package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdTopic;
import ai.neargo.shop.product.entity.PrdTopicGoods;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.TopicGoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.TopicMapper;
import ai.neargo.shop.product.service.GoodsService;
import ai.neargo.shop.product.service.TopicService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** {@link TopicService} 实现。 */
@Service
public class TopicServiceImpl implements TopicService {

    private static final String ACTIVE = "ACTIVE";
    private static final String ARCHIVED = "ARCHIVED";
    private static final String APPROVED = "APPROVED";

    private final TopicMapper topicMapper;
    private final TopicGoodsMapper topicGoodsMapper;
    private final GoodsMapper goodsMapper;
    private final GoodsService goodsService;

    public TopicServiceImpl(TopicMapper topicMapper, TopicGoodsMapper topicGoodsMapper,
                            GoodsMapper goodsMapper, GoodsService goodsService) {
        this.topicMapper = topicMapper;
        this.topicGoodsMapper = topicGoodsMapper;
        this.goodsMapper = goodsMapper;
        this.goodsService = goodsService;
    }

    @Override
    public List<TopicVO> list(boolean includeArchived) {
        /*
         * 主题是**全平台主数据**，与商家无关 —— 与类目同一类东西，所以同样豁免数据域。
         * 不豁免的话 C 端（SELF 维度）拿到的是空列表，而首页那几个入口会静默消失。
         */
        List<PrdTopic> rows = DataScopeContext.executeWithoutScope(() ->
                topicMapper.selectList(Wrappers.<PrdTopic>lambdaQuery()
                        .eq(!includeArchived, PrdTopic::getStatus, ACTIVE)
                        .orderByAsc(PrdTopic::getSort)));
        Map<String, Integer> counts = countsOf(rows.stream().map(PrdTopic::getTopicNo).toList());
        return rows.stream().map(t -> toVO(t, counts.getOrDefault(t.getTopicNo(), 0))).toList();
    }

    @Override
    @Transactional
    public TopicVO save(SaveCommand cmd) {
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 起止时间：**结束早于开始直接拒**。
         *
         * 不拦的话那个专题从建出来的第一秒就不生效 —— 而运营端列表里它看着完全正常，
         * 只有 C 端什么都不显示，没人会把两件事联系起来。
         */
        if (cmd.startAt() != null && cmd.endAt() != null && cmd.endAt() < cmd.startAt()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean isNew = cmd.topicNo() == null || cmd.topicNo().isBlank();
        PrdTopic t = isNew ? newTopic() : require(cmd.topicNo());
        t.setTitle(cmd.title().trim());
        t.setSubtitle(cmd.subtitle() == null || cmd.subtitle().isBlank() ? null : cmd.subtitle().trim());
        t.setCover(cmd.cover());
        if (cmd.sort() != null) {
            t.setSort(cmd.sort());
        }
        // 时间显式传 null 是「取消档期」，不是「不改」—— 常设专题正是这样从限时改回长期的
        t.setStartAt(toLocal(cmd.startAt()));
        t.setEndAt(toLocal(cmd.endAt()));
        if (isNew) {
            DataScopeContext.executeWithoutScope(() -> topicMapper.insert(t));
        } else {
            DataScopeContext.executeWithoutScope(() -> topicMapper.updateById(t));
        }
        return toVO(t, countsOf(List.of(t.getTopicNo())).getOrDefault(t.getTopicNo(), 0));
    }

    @Override
    @Transactional
    public TopicVO setArchived(String topicNo, boolean archived) {
        PrdTopic t = require(topicNo);
        t.setStatus(archived ? ARCHIVED : ACTIVE);
        DataScopeContext.executeWithoutScope(() -> topicMapper.updateById(t));
        return toVO(t, countsOf(List.of(topicNo)).getOrDefault(topicNo, 0));
    }

    @Override
    public PageData<GoodsVO> goods(String topicNo, long page, long size) {
        List<PrdTopicGoods> rows = rowsOf(topicNo);
        if (rows.isEmpty()) {
            return PageData.of(List.of(), 0, page, size);
        }
        /*
         * 顺序由 `sort` 定，**分页在内存里切**：一个专题几十件货，
         * 为它写一条带 join 的分页 SQL 换不来什么，却要多维护一段手写 SQL。
         */
        List<String> ordered = rows.stream().map(PrdTopicGoods::getGoodsNo).toList();
        int from = (int) Math.max(0, (page - 1) * size);
        List<String> pageNos = from >= ordered.size() ? List.of()
                : ordered.subList(from, (int) Math.min(ordered.size(), from + size));
        if (pageNos.isEmpty()) {
            return PageData.of(List.of(), ordered.size(), page, size);
        }
        Map<String, GoodsVO> byNo = goodsService.detailAll(pageNos);
        List<GoodsVO> out = pageNos.stream().map(byNo::get).filter(java.util.Objects::nonNull).toList();
        return PageData.of(out, ordered.size(), page, size);
    }

    @Override
    @Transactional
    public void setGoods(String topicNo, List<String> goodsNos) {
        require(topicNo);
        List<String> want = goodsNos == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(goodsNos));
        /*
         * **只收在架商品**。
         *
         * 把一件下架 / 待审 / 草稿的货摆进专题，C 端点进去就是个空位 ——
         * 而运营在后台看到它明明在这个专题里。与其让两个页面对同一件货给出
         * 相反的答案，不如在写入这一刻就说清楚。
         */
        Map<String, PrdGoods> live = want.isEmpty() ? Map.of()
                : DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PrdGoods>lambdaQuery()
                                .in(PrdGoods::getGoodsNo, want)
                                .eq(PrdGoods::getOnSale, true)
                                .eq(PrdGoods::getAuditStatus, APPROVED)))
                .stream().collect(Collectors.toMap(PrdGoods::getGoodsNo, Function.identity(), (a, b) -> a));
        for (String no : want) {
            if (!live.containsKey(no)) {
                throw BizException.of(ErrorCode.GOODS_NOT_APPROVED);
            }
        }

        List<PrdTopicGoods> existing = rowsOf(topicNo);
        Set<String> keep = new LinkedHashSet<>(want);
        for (PrdTopicGoods row : existing) {
            if (!keep.contains(row.getGoodsNo())) {
                DataScopeContext.executeWithoutScope(() -> topicGoodsMapper.deleteById(row.getId()));
            }
        }
        Map<String, PrdTopicGoods> byGoods = existing.stream()
                .collect(Collectors.toMap(PrdTopicGoods::getGoodsNo, Function.identity(), (a, b) -> a));
        int i = 0;
        for (String no : want) {
            PrdTopicGoods row = byGoods.get(no);
            boolean fresh = row == null;
            if (fresh) {
                row = new PrdTopicGoods();
                row.setTopicNo(topicNo);
                row.setGoodsNo(no);
                row.setEntityNo(live.get(no).getEntityNo());
            }
            row.setSort(i++);
            PrdTopicGoods toSave = row;
            DataScopeContext.executeWithoutScope(() ->
                    fresh ? topicGoodsMapper.insert(toSave) : topicGoodsMapper.updateById(toSave));
        }
    }

    // ---------------------------------------------------------------- helpers

    private PrdTopic newTopic() {
        PrdTopic t = new PrdTopic();
        t.setTopicNo(BizKey.next(BizKey.TOPIC));
        t.setStatus(ACTIVE);
        t.setSort(0);
        return t;
    }

    private PrdTopic require(String topicNo) {
        PrdTopic t = topicNo == null || topicNo.isBlank() ? null
                : DataScopeContext.executeWithoutScope(() -> topicMapper.selectOne(
                        Wrappers.<PrdTopic>lambdaQuery()
                                .eq(PrdTopic::getTopicNo, topicNo).last("limit 1")));
        if (t == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return t;
    }

    private List<PrdTopicGoods> rowsOf(String topicNo) {
        if (topicNo == null || topicNo.isBlank()) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> topicGoodsMapper.selectList(
                Wrappers.<PrdTopicGoods>lambdaQuery()
                        .eq(PrdTopicGoods::getTopicNo, topicNo)
                        .orderByAsc(PrdTopicGoods::getSort)));
    }

    /** 各专题的商品数。**运营列表要看得见空专题** —— 空专题在 C 端是一个点进去什么都没有的入口 */
    private Map<String, Integer> countsOf(List<String> topicNos) {
        if (topicNos.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (PrdTopicGoods r : DataScopeContext.executeWithoutScope(() ->
                topicGoodsMapper.selectList(Wrappers.<PrdTopicGoods>lambdaQuery()
                        .in(PrdTopicGoods::getTopicNo, topicNos)))) {
            out.merge(r.getTopicNo(), 1, Integer::sum);
        }
        return out;
    }

    private TopicVO toVO(PrdTopic t, int goodsCount) {
        return new TopicVO(t.getTopicNo(), t.getTitle(), t.getSubtitle(), t.getCover(),
                t.getSort() == null ? 0 : t.getSort(),
                toEpoch(t.getStartAt()), toEpoch(t.getEndAt()), t.getStatus(), goodsCount);
    }

    private static LocalDateTime toLocal(Long epochMilli) {
        return epochMilli == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    private static Long toEpoch(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
