package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.entity.ElcManufacturer;
import ai.neargo.shop.elec.entity.ElcMfrAlias;
import ai.neargo.shop.elec.entity.ElcPart;
import ai.neargo.shop.elec.entity.ElcPartKey;
import ai.neargo.shop.elec.mapper.ElecMappers.MfrAliasMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.PartKeyMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.PartMapper;
import ai.neargo.shop.elec.support.ElecKeys;
import ai.neargo.shop.elec.support.Mpn;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 料号库：把供应商写的「料号 + 厂牌原文」认到一个 part_no 上，认不到就长一个新料号。
 *
 * <p><b>一次上传一个实例</b>（{@link #session()}）：别名表整张读进内存（几百行），
 * 料号按本批出现的 mpn_norm 分块预读，新料号攒着一次插入 —— 两万行的表不能是四万次往返。
 */
@ConditionalOnElec
@Component
public class ElecPartCatalog {

    private static final int CHUNK = 500;

    private final MfrAliasMapper aliasMapper;
    private final PartMapper partMapper;
    private final PartKeyMapper keyMapper;

    public ElecPartCatalog(MfrAliasMapper aliasMapper, PartMapper partMapper, PartKeyMapper keyMapper) {
        this.aliasMapper = aliasMapper;
        this.partMapper = partMapper;
        this.keyMapper = keyMapper;
    }

    /** 厂牌原文 → mfr_code；认不出为 null（有歧义的写法不在别名表里，也会落到这里） */
    public Map<String, String> aliases() {
        Map<String, String> m = new HashMap<>();
        for (ElcMfrAlias a : aliasMapper.selectList(null)) {
            m.put(a.getAliasNorm(), a.getMfrCode());
        }
        return m;
    }

    public Session session() {
        return new Session(aliases());
    }

    /** 一批里的料号解析。先 {@link #preload}，再逐行 {@link #resolve}，最后 {@link #flush} */
    public final class Session {

        private final Map<String, String> aliases;
        /** mpn_norm → 这个料号串下的全部料号（不同厂牌） */
        private final Map<String, List<ElcPart>> byNorm = new HashMap<>();
        private final List<ElcPart> created = new ArrayList<>();

        private Session(Map<String, String> aliases) {
            this.aliases = aliases;
        }

        public void preload(Collection<String> norms) {
            List<String> todo = norms.stream().filter(n -> !byNorm.containsKey(n)).distinct().toList();
            for (int i = 0; i < todo.size(); i += CHUNK) {
                List<String> chunk = todo.subList(i, Math.min(todo.size(), i + CHUNK));
                chunk.forEach(n -> byNorm.put(n, new ArrayList<>()));
                for (ElcPart p : partMapper.selectList(Wrappers.<ElcPart>lambdaQuery()
                        .in(ElcPart::getMpnNorm, chunk).ne(ElcPart::getStatus, ElcPart.STATUS_MERGED))) {
                    byNorm.get(p.getMpnNorm()).add(p);
                }
            }
        }

        /**
         * @return part_no。厂牌认得出 → (厂牌, 料号) 那一个；认不出 → 这个料号串下唯一的那个，
         *         有多个或没有就落到 UNKNOWN 名下（运营事后合并）
         */
        public String resolve(String mpnRaw, String mpnNorm, String mfrRaw, String pkg, String actor) {
            preload(List.of(mpnNorm));
            List<ElcPart> same = byNorm.get(mpnNorm);
            String mfrCode = aliases.get(Mpn.mfrNorm(mfrRaw));
            if (mfrCode != null) {
                for (ElcPart p : same) {
                    if (p.getMfrCode().equals(mfrCode)) {
                        return p.getPartNo();
                    }
                }
                return create(mpnRaw, mpnNorm, mfrCode, null, pkg, actor, same);
            }
            if (same.size() == 1) {
                return same.get(0).getPartNo();
            }
            for (ElcPart p : same) {
                if (ElcManufacturer.UNKNOWN.equals(p.getMfrCode())) {
                    return p.getPartNo();
                }
            }
            return create(mpnRaw, mpnNorm, ElcManufacturer.UNKNOWN,
                    mfrRaw == null || mfrRaw.isBlank() ? null : truncate(mfrRaw.trim(), 64), pkg, actor, same);
        }

        private String create(String mpnRaw, String mpnNorm, String mfrCode, String mfrNameRaw, String pkg,
                              String actor, List<ElcPart> same) {
            ElcPart p = new ElcPart();
            p.setPartNo(ElecKeys.next(ElecKeys.PART));
            p.setMpn(truncate(mpnRaw.trim(), 64));
            p.setMpnNorm(mpnNorm);
            p.setMfrCode(mfrCode);
            p.setMfrNameRaw(mfrNameRaw);
            p.setPkg(pkg);
            p.setSource(ElcPart.SOURCE_UPLOAD);
            p.setStatus(ElcPart.STATUS_PENDING);
            p.setCreatedBy(actor);
            same.add(p);
            created.add(p);
            return p.getPartNo();
        }

        /** 把攒着的新料号插进去。<b>必须在插库存行之前调</b> —— 库存行指着它们 */
        public int flush() {
            int n = created.size();
            for (int i = 0; i < created.size(); i += CHUNK) {
                partMapper.insertAll(created.subList(i, Math.min(created.size(), i + CHUNK)));
            }
            /*
             * 分段键跟着料号一起写：中段搜索靠它。<b>同一个料号串在库里已有别的厂牌时，
             * 键已经在了</b>（键按 key_norm + part_no 唯一，不同 part_no 各一套），所以这里不去重。
             */
            List<ElcPartKey> keys = new ArrayList<>();
            for (ElcPart p : created) {
                keys.addAll(keysOf(p.getPartNo(), p.getMpnNorm(), p.getCreatedBy()));
            }
            for (int i = 0; i < keys.size(); i += CHUNK) {
                keyMapper.insertAll(keys.subList(i, Math.min(keys.size(), i + CHUNK)));
            }
            created.clear();
            return n;
        }
    }

    static List<ElcPartKey> keysOf(String partNo, String mpnNorm, String actor) {
        List<ElcPartKey> out = new ArrayList<>();
        for (var k : Mpn.segmentKeys(mpnNorm)) {
            ElcPartKey row = new ElcPartKey();
            row.setKeyNorm(k.getKey());
            row.setPartNo(partNo);
            row.setPos(k.getValue());
            row.setCreatedBy(actor);
            out.add(row);
        }
        return out;
    }

    static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
