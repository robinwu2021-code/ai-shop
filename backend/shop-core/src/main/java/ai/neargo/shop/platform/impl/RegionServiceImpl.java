package ai.neargo.shop.platform.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.platform.RegionService;
import ai.neargo.shop.platform.entity.SysRegion;
import ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link RegionService} 实现。 */
@Service
public class RegionServiceImpl implements RegionService {

    /** 回溯深度上限。四级树最多走 4 步，给 8 是为了让**坏数据不会变成死循环** */
    private static final int MAX_DEPTH = 8;

    private final RegionMapper mapper;

    public RegionServiceImpl(RegionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<RegionVO> children(String parentCode, boolean enabledOnly) {
        List<SysRegion> rows = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                        .eq(enabledOnly, SysRegion::getEnabled, true)
                        // 顶层是 parent_code IS NULL，不是空串 —— 见实体上的说明
                        .isNull(parentCode == null || parentCode.isBlank(), SysRegion::getParentCode)
                        .eq(parentCode != null && !parentCode.isBlank(),
                                SysRegion::getParentCode, parentCode)
                        .orderByAsc(SysRegion::getRegionCode)));
        return toVOs(rows);
    }

    @Override
    public List<RegionVO> path(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return List.of();
        }
        List<SysRegion> chain = new ArrayList<>();
        String code = regionCode;
        for (int i = 0; i < MAX_DEPTH && code != null && !code.isBlank(); i++) {
            SysRegion row = find(code);
            if (row == null) {
                /*
                 * 链断了就返回**已经走到的部分**，不抛异常也不返回空。
                 *
                 * 存量数据里会有已撤并的区划码（区划每年调整，而这份数据停在 2023）。
                 * 抛异常的话，一个早年归属的社区会让整个运营页打不开；
                 * 返回空的话，界面显示「未归属」而它其实归属过 —— 两个都比
                 * 「显示到能显示的那一级」更糟。
                 */
                break;
            }
            chain.add(row);
            code = row.getParentCode();
        }
        Collections.reverse(chain);   // 从省到自身
        return toVOs(chain);
    }

    private SysRegion find(String code) {
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<SysRegion>lambdaQuery()
                        .eq(SysRegion::getRegionCode, code).last("LIMIT 1")));
    }

    /**
     * 一次性查出「哪些码还有下级」，而不是每行各查一次。
     *
     * <p>省级只有 31 行时逐行查看不出问题，而街道一层单个区就能有几十条 ——
     * 那就是几十次往返。列表页的 N+1 一向如此：小数据集上永远发现不了。
     */
    private List<RegionVO> toVOs(List<SysRegion> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> codes = rows.stream().map(SysRegion::getRegionCode).toList();
        Set<String> withChild = DataScopeContext.executeWithoutScope(() ->
                        mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                                .select(SysRegion::getParentCode)
                                .in(SysRegion::getParentCode, codes)
                                .groupBy(SysRegion::getParentCode))).stream()
                .map(SysRegion::getParentCode).collect(Collectors.toSet());

        return rows.stream().map(r -> new RegionVO(
                r.getRegionCode(), r.getParentCode(), r.getLevel(), r.getName(),
                Boolean.TRUE.equals(r.getEnabled()),
                withChild.contains(r.getRegionCode()))).toList();
    }
}
