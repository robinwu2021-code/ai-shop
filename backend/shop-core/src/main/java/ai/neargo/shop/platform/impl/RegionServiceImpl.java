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
                         * 可见范围：**已通过的 + 我自己提报的（含被驳回的）**。
                         *
                         * 判据是 audit_status 而不是 owner_entity_no ——
                         * 后者现在只记「谁报的」，通过之后也保留，用它判可见性
                         * 会让通过后的补录反而只有提报方看得到。
                         *
                         * 被驳回的也给提报方看：连同理由。看不到的话那个村在他那里
                         * 凭空消失，他不知道为什么，多半原样再录一遍。
                         */
                        .and(q -> {
                            q.eq(SysRegion::getAuditStatus, SysRegion.APPROVED);
                            if (owner != null) {
                                q.or(o -> o.eq(SysRegion::getOwnerEntityNo, owner));
                            }
                        })
                        .orderByAsc(SysRegion::getRegionCode)));
        return toVOs(rows);
    }

    /** 下一级的 level 由父级推导 —— 不让人选，选错的代价是整棵树的层级从此对不上 */
    private static String childLevel(String parentLevel) {
        return switch (parentLevel) {
            case "PROVINCE" -> "CITY";
            case "CITY" -> "DISTRICT";
            case "DISTRICT" -> "STREET";
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
    }

    @Override
    @Transactional
    public RegionVO createNode(String parentCode, String name, String operatorNo) {
        String vname = name == null ? "" : name.trim();
        SysRegion parent = find(parentCode == null ? "" : parentCode.trim());
        if (parent == null || vname.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 同父下同名直接返回既有的 —— 报错的话运营看到「已存在」还得自己去找它在哪
        SysRegion dup = DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<SysRegion>lambdaQuery()
                        .eq(SysRegion::getParentCode, parent.getRegionCode())
                        .eq(SysRegion::getName, vname).last("limit 1")));
        if (dup != null) {
            return toVOs(List.of(dup)).get(0);
        }
        /*
         * 生成码 = 父码 + X + 两位序号。官方码纯数字（62 万条实测过），
         * 字母段保证官方将来补发号段也撞不上唯一键 —— 用数字续号的话，
         * 某天官方发出那个号，撞的是 uk_sys_region_code，报出来是「保存失败」
         * 而根因在两年前的编码方案上。
         */
        List<SysRegion> mine = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                        .likeRight(SysRegion::getRegionCode, parent.getRegionCode() + "X")));
        int max = 0;
        for (SysRegion x : mine) {
            try {
                max = Math.max(max, Integer.parseInt(
                        x.getRegionCode().substring(parent.getRegionCode().length() + 1)));
            } catch (NumberFormatException ignored) {
                // 手工写进来的怪码不参与算号，但也不该让新增失败
            }
        }
        if (max >= 99) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        SysRegion row = new SysRegion();
        row.setRegionCode("%sX%02d".formatted(parent.getRegionCode(), max + 1));
        row.setParentCode(parent.getRegionCode());
        row.setLevel(childLevel(parent.getLevel()));
        row.setName(vname);
        row.setSource("OPS");
        row.setAuditStatus(SysRegion.APPROVED);
        row.setEnabled(true);
        row.setSort(0);
        row.setCreatedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
        return toVOs(List.of(row)).get(0);
    }

    @Override
    @Transactional
    public RegionVO toggleNode(String regionCode, boolean enabled, String operatorNo) {
        SysRegion row = find(regionCode);
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 不级联：停用「西湖区」只让它自己从选择器消失，底下街道仍可单独选。
        // 级联会让一次误操作波及几十个街道，而恢复时没人记得原来哪些是停的
        row.setEnabled(enabled);
        row.setUpdatedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(row));
        return toVOs(List.of(row)).get(0);
    }

    @Override
    @Transactional
    public RegionVO renameNode(String regionCode, String name, String operatorNo) {
        String vname = name == null ? "" : name.trim();
        SysRegion row = find(regionCode);
        if (row == null || vname.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        row.setName(vname);
        row.setUpdatedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(row));
        return toVOs(List.of(row)).get(0);
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

    @Override
    public List<RegionVO> search(String keyword, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.length() < 2) {
            // 单字会命中成千上万行（「区」「市」），给人挑的列表不该这么长
            return List.of();
        }
        int cap = Math.max(1, Math.min(limit, 20));
        List<SysRegion> rows = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                        .in(SysRegion::getLevel, List.of("CITY", "DISTRICT", "STREET"))
                        .eq(SysRegion::getEnabled, true)
                        .eq(SysRegion::getAuditStatus, "APPROVED")
                        .like(SysRegion::getName, kw)
                        // 细的排前面：搜「西湖」多半是要西湖区下的街道，而不是整个区
                        .orderByDesc(SysRegion::getLevel)
                        .orderByAsc(SysRegion::getRegionCode)
                        .last("LIMIT " + cap)));
        return toVOs(rows);
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
                !SysRegion.APPROVED.equals(r.getAuditStatus()),
                r.getAuditStatus(), r.getRejectReason())).toList();
    }
}
