package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.entity.ElcHeaderAlias;
import ai.neargo.shop.elec.mapper.ElecMappers.HeaderAliasMapper;
import ai.neargo.shop.elec.support.Columns.Field;
import ai.neargo.shop.elec.support.HeaderNames;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表头别名：查（本家学到的 &gt; 全局）与学（确认上架后把 AI / 手工认出的写法记成本家的）。
 *
 * <p>全局别名整张读进内存、5 分钟一换 —— 一百来行，每次上传都查库不值得；运营改了别名调
 * {@link #invalidate()} 立即生效。本家学到的每次现查：量小，而且刚学到的下一次上传就要用上。
 */
@ConditionalOnElec
@Component
public class HeaderAliases {

    private static final Duration GLOBAL_TTL = Duration.ofMinutes(5);

    private final HeaderAliasMapper mapper;

    private volatile Map<String, Field> global;
    private volatile Instant globalAt = Instant.EPOCH;

    public HeaderAliases(HeaderAliasMapper mapper) {
        this.mapper = mapper;
    }

    /** 规范化写法 → 字段。本家学到的覆盖全局（他就是这么用这个词的） */
    public Map<String, Field> lookup(String supplierNo) {
        Map<String, Field> m = new HashMap<>(global());
        if (supplierNo != null && !supplierNo.isEmpty()) {
            m.putAll(toMap(mapper.selectList(Wrappers.<ElcHeaderAlias>lambdaQuery()
                    .eq(ElcHeaderAlias::getSupplierNo, supplierNo)
                    .eq(ElcHeaderAlias::getStatus, ElcHeaderAlias.STATUS_ACTIVE))));
        }
        return m;
    }

    /**
     * 把确认过的表头写法记成这家的别名。
     *
     * <p><b>只写本家、不写全局</b>：一家把「规格」当料号，全平台跟着认错。全局要运营提升。
     * 全局里已经是同一个字段的不重复写；写法太短（不足 2 字符）的不学 —— 「A」「No」这类会误伤别的表。
     *
     * @param headers 字段 → 那一列的表头原文
     */
    public void learn(String supplierNo, Map<Field, String> headers, String actor) {
        Map<String, Field> g = global();
        for (Map.Entry<Field, String> e : headers.entrySet()) {
            String norm = HeaderNames.norm(e.getValue());
            if (norm.length() < 2 || e.getKey() == g.get(norm)) {
                continue;
            }
            ElcHeaderAlias cur = mapper.selectOne(Wrappers.<ElcHeaderAlias>lambdaQuery()
                    .eq(ElcHeaderAlias::getSupplierNo, supplierNo)
                    .eq(ElcHeaderAlias::getAliasNorm, norm));
            if (cur == null) {
                ElcHeaderAlias a = new ElcHeaderAlias();
                a.setSupplierNo(supplierNo);
                a.setAliasNorm(norm);
                a.setAliasRaw(truncate(e.getValue().trim(), 64));
                a.setField(e.getKey().name());
                a.setSource(ElcHeaderAlias.SOURCE_LEARNED);
                a.setStatus(ElcHeaderAlias.STATUS_ACTIVE);
                a.setCreatedBy(actor);
                a.setUpdatedBy(actor);
                mapper.insert(a);
            } else if (!e.getKey().name().equals(cur.getField())
                    || !ElcHeaderAlias.STATUS_ACTIVE.equals(cur.getStatus())) {
                // 他这次把同一写法认成了别的字段：以最近一次确认为准
                cur.setField(e.getKey().name());
                cur.setStatus(ElcHeaderAlias.STATUS_ACTIVE);
                cur.setUpdatedBy(actor);
                mapper.updateById(cur);
            }
        }
    }

    /** 运营改了全局别名之后调，下一次上传就按新的认 */
    public void invalidate() {
        globalAt = Instant.EPOCH;
    }

    private Map<String, Field> global() {
        Map<String, Field> g = global;
        if (g == null || Instant.now().isAfter(globalAt.plus(GLOBAL_TTL))) {
            g = Map.copyOf(toMap(mapper.selectList(Wrappers.<ElcHeaderAlias>lambdaQuery()
                    .eq(ElcHeaderAlias::getSupplierNo, ElcHeaderAlias.GLOBAL)
                    .eq(ElcHeaderAlias::getStatus, ElcHeaderAlias.STATUS_ACTIVE))));
            global = g;
            globalAt = Instant.now();
        }
        return g;
    }

    private static Map<String, Field> toMap(List<ElcHeaderAlias> rows) {
        Map<String, Field> m = new HashMap<>();
        for (ElcHeaderAlias a : rows) {
            for (Field f : Field.values()) {
                if (f.name().equals(a.getField())) {
                    m.put(a.getAliasNorm(), f);
                }
            }
        }
        return m;
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
