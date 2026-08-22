package ai.neargo.shop.platform.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.RegionService;
import ai.neargo.shop.platform.entity.SysRegion;
import ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        return children(parentCode, enabledOnly, null);
    }

    @Override
    public List<RegionVO> children(String parentCode, boolean enabledOnly, String entityNo) {
        String owner = entityNo == null || entityNo.isBlank() ? null : entityNo;
        List<SysRegion> rows = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                        .eq(enabledOnly, SysRegion::getEnabled, true)
                        // 顶层是 parent_code IS NULL，不是空串 —— 见实体上的说明
                        .isNull(parentCode == null || parentCode.isBlank(), SysRegion::getParentCode)
                        .eq(parentCode != null && !parentCode.isBlank(),
                                SysRegion::getParentCode, parentCode)
                        /*
                         * 可见范围：**已共享的（owner 为空）+ 我自己补录的**。
                         *
                         * 少了这个条件，一家店补录的村会立刻出现在所有商家的选择器里 ——
                         * 而他可能只是把「XX 新村」打成了「XX 新材」。
                         * 错别字污染的是全平台共享的那棵树，且没人知道是谁录的。
                         */
                        .and(q -> {
                            q.isNull(SysRegion::getOwnerEntityNo);
                            if (owner != null) {
                                q.or(o -> o.eq(SysRegion::getOwnerEntityNo, owner));
                            }
                        })
                        .orderByAsc(SysRegion::getRegionCode)));
        return toVOs(rows);
    }

    /**
     * 商家补录一个村。
     *
     * <p><b>自建码用字母，永不与官方码冲突</b>：官方村级码是
     * {@code 街道码(9) + 3 位数字}（实测 62 万条全为纯数字，后缀 000–599），
     * 而这里生成 {@code 街道码(9) + M + 2 位序号}。字母保证了以后官方补发数据
     * 也撞不上 —— 用数字续号的话，某天官方把 600 号发出来，
     * 撞的是唯一键，报出来是「保存失败」而根因在两年前的编码方案上。
     */
    @Override
    @Transactional
    public RegionVO createVillage(String parentStreetCode, String name, String entityNo) {
        String street = parentStreetCode == null ? "" : parentStreetCode.trim();
        String vname = name == null ? "" : name.trim();
        if (street.isBlank() || vname.isBlank() || entityNo == null || entityNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        SysRegion parent = find(street);
        // 只能挂街道下：挂到区县下的话，它在任何「按街道覆盖」的场景里都出不来
        if (parent == null || !"STREET".equals(parent.getLevel())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 同一街道下同名就直接返回既有的，不报错也不建第二条。
         *
         * 报错的话商家看到「已存在」却在选择器里找不到它 —— 因为那条可能是
         * **别家店**补录的、还没共享，他看不见。建第二条则是让同一个村
         * 在运营确认后出现两次。返回既有的那条最接近他要的结果：能用上。
         */
        List<SysRegion> siblings = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                        .eq(SysRegion::getParentCode, street)
                        .eq(SysRegion::getName, vname)));
        for (SysRegion x : siblings) {
            if (x.getOwnerEntityNo() == null || entityNo.equals(x.getOwnerEntityNo())) {
                return toVOs(List.of(x)).get(0);
            }
        }

        String code = nextMerchantCode(street);
        SysRegion row = new SysRegion();
        row.setRegionCode(code);
        row.setParentCode(street);
        row.setLevel("VILLAGE");
        row.setSource("MERCHANT");
        row.setOwnerEntityNo(entityNo);
        row.setName(vname);
        row.setEnabled(true);
        row.setSort(0);
        DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
        return toVOs(List.of(row)).get(0);
    }

    /** {@code 街道码 + M + 2 位}，M01 起。同一街道最多 99 个补录，够用且看得出是补录的 */
    private String nextMerchantCode(String street) {
        List<SysRegion> mine = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                        .eq(SysRegion::getParentCode, street)
                        .likeRight(SysRegion::getRegionCode, street + "M")));
        int max = 0;
        for (SysRegion x : mine) {
            String suffix = x.getRegionCode().substring(street.length() + 1);
            try {
                max = Math.max(max, Integer.parseInt(suffix));
            } catch (NumberFormatException ignored) {
                // 手工写进来的怪码不参与算序号，但也不该让整个补录失败
            }
        }
        if (max >= 99) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return "%sM%02d".formatted(street, max + 1);
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
                withChild.contains(r.getRegionCode()),
                r.getSource() == null ? "OFFICIAL" : r.getSource(),
                r.getOwnerEntityNo() != null)).toList();
    }
}
