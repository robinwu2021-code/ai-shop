package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.dto.GoodsDiffs;
import ai.neargo.shop.product.dto.GoodsRevisionVO;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdGoodsRevision;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.product.service.GoodsRevisionService;
import ai.neargo.shop.product.service.MerchantGoodsService.SaveCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** {@link GoodsRevisionService} 实现。 */
@Service
public class GoodsRevisionServiceImpl implements GoodsRevisionService {

    private final ProductMappers.GoodsRevisionMapper revisionMapper;
    private final ProductMappers.GoodsMapper goodsMapper;

    public GoodsRevisionServiceImpl(ProductMappers.GoodsRevisionMapper revisionMapper,
                                    ProductMappers.GoodsMapper goodsMapper) {
        this.revisionMapper = revisionMapper;
        this.goodsMapper = goodsMapper;
    }

    @Override
    public void recordSave(String goodsNo, String entityNo, String payload,
                           String changeSummary, String entrySource) {
        PrdGoodsRevision draft = pending(goodsNo);
        if (draft != null) {
            /*
             * 已有未发布行就更新它 —— **同一商品至多一行未发布**，与 prd_goods_draft 同语义。
             * 否则边输边识别加上自动存草稿，一个商品能刷出几百行，而商家只想看「待发布那一版」。
             */
            draft.setPayload(payload);
            draft.setChangeSummary(changeSummary);
            draft.setEntrySource(entrySource == null ? PrdGoodsRevision.SRC_MANUAL : entrySource);
            DataScopeContext.executeWithoutScope(() -> revisionMapper.updateById(draft));
            return;
        }
        PrdGoodsRevision row = new PrdGoodsRevision();
        row.setGoodsNo(goodsNo);
        row.setEntityNo(entityNo);
        row.setRevisionNo(DataScopeContext.executeWithoutScope(
                () -> revisionMapper.nextRevisionNo(goodsNo)));
        PrdGoodsRevision online = current(goodsNo);
        row.setBaseRevision(online == null ? null : online.getRevisionNo());
        row.setPayload(payload);
        row.setChangeSummary(changeSummary);
        row.setEntrySource(entrySource == null ? PrdGoodsRevision.SRC_MANUAL : entrySource);
        row.setStatus(PrdGoodsRevision.DRAFT);
        DataScopeContext.executeWithoutScope(() -> revisionMapper.insert(row));
    }

    @Override
    public void recordPublished(String goodsNo, String publishedBy) {
        PrdGoodsRevision draft = pending(goodsNo);
        if (draft == null) {
            /*
             * 这次发布没经过保存（免审直通的某些路径、建表之前的历史商品）。
             * **不补一行** —— 补出来的是一份看不出来源的快照，它会被当成
             * 「有人在那个时刻保存过」，比没有更误导。
             */
            return;
        }
        PrdGoodsRevision online = current(goodsNo);
        if (online != null) {
            online.setStatus(PrdGoodsRevision.SUPERSEDED);
            DataScopeContext.executeWithoutScope(() -> revisionMapper.updateById(online));
        }
        draft.setStatus(PrdGoodsRevision.ONLINE);
        draft.setPublishedBy(publishedBy);
        draft.setPublishedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> revisionMapper.updateById(draft));
    }

    @Override
    public void recordRejected(String goodsNo, String reason) {
        PrdGoodsRevision draft = pending(goodsNo);
        if (draft == null) {
            return;
        }
        draft.setStatus(PrdGoodsRevision.REJECTED);
        draft.setRejectReason(reason);
        DataScopeContext.executeWithoutScope(() -> revisionMapper.updateById(draft));
    }

    @Override
    public List<GoodsRevisionVO> list(String merchantNo, String goodsNo) {
        requireMine(merchantNo, goodsNo);
        List<PrdGoodsRevision> rows = DataScopeContext.executeWithoutScope(() ->
                revisionMapper.selectList(Wrappers.<PrdGoodsRevision>lambdaQuery()
                        .eq(PrdGoodsRevision::getGoodsNo, goodsNo)
                        .orderByDesc(PrdGoodsRevision::getRevisionNo)));
        return rows.stream().map(r -> toVO(r).withoutDiffs()).toList();
    }

    @Override
    public GoodsRevisionVO detail(String merchantNo, String goodsNo, int revisionNo) {
        requireMine(merchantNo, goodsNo);
        PrdGoodsRevision r = one(goodsNo, revisionNo);
        /*
         * 基版取 base_revision 指的那一行,不是「revision_no - 1」——
         * 中间可能有被驳回的版本,它们没上过线,拿它们当基准算出来的差异是假的。
         */
        PrdGoodsRevision prev = r.getBaseRevision() == null ? null : one(goodsNo, r.getBaseRevision());
        PrdGoodsRevision online = current(goodsNo);
        SaveCommand mine = parse(r.getPayload());
        return new GoodsRevisionVO(
                r.getRevisionNo(), r.getStatus(), r.getEntrySource(), r.getChangeSummary(),
                r.getCreatedBy(), r.getCreatedAt(), r.getPublishedBy(), r.getPublishedAt(),
                r.getRejectReason(),
                GoodsDiffs.between(prev == null ? null : parse(prev.getPayload()), mine),
                online == null || online.getRevisionNo().equals(r.getRevisionNo())
                        ? List.of()
                        : GoodsDiffs.between(parse(online.getPayload()), mine),
                // 线上在售那一版取回毫无意义
                !PrdGoodsRevision.ONLINE.equals(r.getStatus()));
    }

    @Override
    public GoodsRevisionVO fork(String merchantNo, String goodsNo, int revisionNo) {
        requireMine(merchantNo, goodsNo);
        PrdGoodsRevision src = one(goodsNo, revisionNo);
        if (PrdGoodsRevision.ONLINE.equals(src.getStatus())) {
            // 取回线上在售那一版 = 什么都不做。给一个必然无效的动作,不如拒
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 原样复用 recordSave:它已经守着「同一商品至多一行未发布」——
         * 手上有未发布草稿时取回旧版,是把那一行换掉,不是多出一行。
         * changeSummary 写明来源,否则历史里会出现一版看不出从哪来的草稿。
         */
        recordSave(goodsNo, src.getEntityNo(), src.getPayload(),
                "取回 v" + revisionNo, src.getEntrySource());
        PrdGoodsRevision made = pending(goodsNo);
        return toVO(made).withoutDiffs();
    }

    private PrdGoodsRevision one(String goodsNo, int revisionNo) {
        PrdGoodsRevision r = DataScopeContext.executeWithoutScope(() ->
                revisionMapper.selectOne(Wrappers.<PrdGoodsRevision>lambdaQuery()
                        .eq(PrdGoodsRevision::getGoodsNo, goodsNo)
                        .eq(PrdGoodsRevision::getRevisionNo, revisionNo).last("limit 1")));
        if (r == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return r;
    }

    /**
     * 快照 → SaveCommand。**读不动就回 null**（差异那一段随之为空）——
     * 快照不是契约,它的形状随编辑器走;一份三个月前的旧快照解不开，
     * 不该让整个历史页 500。
     */
    private static SaveCommand parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper().readValue(payload, SaveCommand.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 未发布那一行（至多一行） */
    private PrdGoodsRevision pending(String goodsNo) {
        return byStatus(goodsNo, PrdGoodsRevision.DRAFT);
    }

    /** 线上在售那一行。**不一定是 revision_no 最大的那一行** */
    private PrdGoodsRevision current(String goodsNo) {
        return byStatus(goodsNo, PrdGoodsRevision.ONLINE);
    }

    private PrdGoodsRevision byStatus(String goodsNo, String status) {
        return DataScopeContext.executeWithoutScope(() ->
                revisionMapper.selectOne(Wrappers.<PrdGoodsRevision>lambdaQuery()
                        .eq(PrdGoodsRevision::getGoodsNo, goodsNo)
                        .eq(PrdGoodsRevision::getStatus, status)
                        .orderByDesc(PrdGoodsRevision::getRevisionNo)
                        .last("limit 1")));
    }

    /**
     * 这件货是不是他的。**带域表的读也要绕域** —— 直查会被过滤成空，
     * 症状是「别人的商品查不到」和「自己的商品也查不到」长得一样（SELECT 变 404）。
     */
    private void requireMine(String merchantNo, String goodsNo) {
        PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, goodsNo)
                        .eq(PrdGoods::getEntityNo, merchantNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private static GoodsRevisionVO toVO(PrdGoodsRevision r) {
        return new GoodsRevisionVO(
                r.getRevisionNo() == null ? 0 : r.getRevisionNo(),
                r.getStatus(),
                r.getEntrySource(),
                r.getChangeSummary(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getPublishedBy(),
                r.getPublishedAt(),
                r.getRejectReason(),
                null, null, false);
    }
}
