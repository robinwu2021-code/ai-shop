package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.OpsDtos.HeaderAliasReq;
import ai.neargo.shop.elec.dto.OpsDtos.HeaderAliasRow;
import ai.neargo.shop.elec.dto.OpsDtos.HeaderAliasUpdate;
import ai.neargo.shop.elec.entity.ElcHeaderAlias;
import ai.neargo.shop.elec.mapper.ElecMappers.HeaderAliasMapper;
import ai.neargo.shop.elec.service.ElecOpsHeaderAliasService;
import ai.neargo.shop.elec.support.Columns.Field;
import ai.neargo.shop.elec.support.HeaderNames;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@ConditionalOnElec
@Service
public class ElecOpsHeaderAliasServiceImpl implements ElecOpsHeaderAliasService {

    private static final int MAX_PAGE = 100;

    private final HeaderAliasMapper mapper;
    private final HeaderAliases aliases;

    public ElecOpsHeaderAliasServiceImpl(HeaderAliasMapper mapper, HeaderAliases aliases) {
        this.mapper = mapper;
        this.aliases = aliases;
    }

    @Override
    public List<HeaderAliasRow> list(String scope, String keyword, int page, int size) {
        int sz = Math.max(1, Math.min(MAX_PAGE, size));
        int offset = Math.max(0, page - 1) * sz;
        String kw = keyword == null || keyword.isBlank() ? null : HeaderNames.norm(keyword);
        if ("LEARNED".equals(scope)) {
            return mapper.learned(kw == null ? null : "%" + kw + "%", sz, offset).stream()
                    .map(r -> new HeaderAliasRow(null, r.getAliasNorm(), r.getAliasRaw(), r.getField(),
                            ElcHeaderAlias.SOURCE_LEARNED, ElcHeaderAlias.STATUS_ACTIVE,
                            r.getSupplierCount() == null ? 0 : r.getSupplierCount(), r.getLastAt()))
                    .toList();
        }
        return mapper.selectList(Wrappers.<ElcHeaderAlias>lambdaQuery()
                        .eq(ElcHeaderAlias::getSupplierNo, ElcHeaderAlias.GLOBAL)
                        .like(kw != null, ElcHeaderAlias::getAliasNorm, kw)
                        .orderByAsc(ElcHeaderAlias::getField).orderByAsc(ElcHeaderAlias::getId)
                        .last("LIMIT " + sz + " OFFSET " + offset)).stream()
                .map(ElecOpsHeaderAliasServiceImpl::row).toList();
    }

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public HeaderAliasRow createGlobal(String actor, HeaderAliasReq req) {
        String norm = req == null ? "" : HeaderNames.norm(req.alias());
        String field = fieldOf(req == null ? null : req.field());
        if (norm.length() < 2) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        ElcHeaderAlias a = mapper.selectOne(Wrappers.<ElcHeaderAlias>lambdaQuery()
                .eq(ElcHeaderAlias::getSupplierNo, ElcHeaderAlias.GLOBAL).eq(ElcHeaderAlias::getAliasNorm, norm));
        if (a == null) {
            a = new ElcHeaderAlias();
            a.setSupplierNo(ElcHeaderAlias.GLOBAL);
            a.setAliasNorm(norm);
            a.setAliasRaw(req.alias().strip().length() > 64 ? req.alias().strip().substring(0, 64) : req.alias().strip());
            a.setField(field);
            a.setSource(ElcHeaderAlias.SOURCE_OPS);
            a.setStatus(ElcHeaderAlias.STATUS_ACTIVE);
            a.setCreatedBy(actor);
            a.setUpdatedBy(actor);
            mapper.insert(a);
        } else {
            a.setField(field);
            a.setStatus(ElcHeaderAlias.STATUS_ACTIVE);
            a.setUpdatedBy(actor);
            mapper.updateById(a);
        }
        aliases.invalidate();
        return row(a);
    }

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public HeaderAliasRow update(String actor, long id, HeaderAliasUpdate req) {
        ElcHeaderAlias a = mapper.selectById(id);
        if (a == null || !ElcHeaderAlias.GLOBAL.equals(a.getSupplierNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (req != null && req.field() != null) {
            a.setField(fieldOf(req.field()));
        }
        if (req != null && req.status() != null) {
            if (!ElcHeaderAlias.STATUS_ACTIVE.equals(req.status())
                    && !ElcHeaderAlias.STATUS_DISABLED.equals(req.status())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            a.setStatus(req.status());
        }
        a.setUpdatedBy(actor);
        mapper.updateById(a);
        aliases.invalidate();
        return row(a);
    }

    private static String fieldOf(String f) {
        return Arrays.stream(Field.values()).map(Enum::name).filter(n -> n.equals(f)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
    }

    private static HeaderAliasRow row(ElcHeaderAlias a) {
        return new HeaderAliasRow(a.getId(), a.getAliasNorm(), a.getAliasRaw(), a.getField(), a.getSource(),
                a.getStatus(), 0, a.getUpdatedAt());
    }
}
