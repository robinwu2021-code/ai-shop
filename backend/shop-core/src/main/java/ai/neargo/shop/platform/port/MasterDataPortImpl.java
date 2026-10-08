package ai.neargo.shop.platform.port;

import ai.neargo.shop.platform.entity.SysRegion;
import ai.neargo.shop.platform.MasterDataService;
import ai.neargo.shop.spi.platform.MasterDataPort;
import org.springframework.stereotype.Component;

/**
 * {@link MasterDataPort} 实现。
 *
 * <p><b>薄薄一层，但不能省。</b>让 {@code MasterDataServiceImpl} 直接实现 Port 的话，
 * 本域内部方法与跨域契约会混在一个类里 —— 改本域逻辑时不知不觉改掉了别的域看到的行为，
 * 而两拨受众看到的能力范围也不一样（platform 自己要 {@code snapshot()}，
 * 别的域只该看到几个判断）。架构守卫拦的就是这个。
 */
@Component
public class MasterDataPortImpl implements MasterDataPort {

    private final MasterDataService masterDataService;
    private final ai.neargo.shop.platform.RegionService regionService;

    public MasterDataPortImpl(MasterDataService masterDataService,
                              ai.neargo.shop.platform.RegionService regionService) {
        this.masterDataService = masterDataService;
        this.regionService = regionService;
    }

    @Override
    public String canonicalSubject(String anySubject) {
        return masterDataService.canonicalSubject(anySubject);
    }

    @Override
    public boolean industryGated(String subjectType) {
        return masterDataService.industryGated(subjectType);
    }

    @Override
    public String settleAccountType(String subjectType) {
        return masterDataService.settleAccountType(subjectType);
    }

    @Override
    public boolean needLicense(String subjectType) {
        return masterDataService.needLicense(subjectType);
    }


    @Override
    public void assertServiceScopeAllowed(String scope) {
        masterDataService.assertServiceScopeAllowed(scope);
    }

    @Override
    public String regionPathName(String regionCode) {
        var path = regionService.path(regionCode);
        return path.isEmpty() ? regionCode
                : path.stream().map(ai.neargo.shop.platform.RegionService.RegionVO::name)
                        .collect(java.util.stream.Collectors.joining(" / "));
    }

    @Override
    public java.util.Map<String, String> regionPathNames(java.util.Collection<String> regionCodes) {
        return regionService.pathNames(regionCodes);
    }

    @Override
    public java.util.Optional<String> officialVillageStreet(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return java.util.Optional.empty();
        }
        var path = regionService.path(regionCode);
        if (path.size() < 2) {
            return java.util.Optional.empty();
        }
        var self = path.get(path.size() - 1);
        var parent = path.get(path.size() - 2);
        boolean official = SysRegion.LEVEL_VILLAGE.equals(self.level())
                && (self.source() == null || "OFFICIAL".equals(self.source()));
        // 街道码是 9 位：聚落必须挂在街道/镇下，挂粗了按街道覆盖永远匹配不到
        return official && parent.regionCode() != null && parent.regionCode().length() == 9
                ? java.util.Optional.of(parent.regionCode())
                : java.util.Optional.empty();
    }

    @Override
    public java.util.List<RegionSuggestion> resolveRegion(String address, Integer latE6, Integer lngE6) {
        return regionService.resolve(address, latE6, lngE6).stream()
                .map(s -> new RegionSuggestion(s.region().regionCode(), s.region().level(),
                        s.region().name(), s.path(), s.source(), s.detail()))
                .toList();
    }

    @Override
    public java.util.Optional<String> streetByDistrictAndName(String adcode, String townshipName) {
        String d = adcode == null ? "" : adcode.trim();
        String t = townshipName == null ? "" : townshipName.trim();
        if (d.isEmpty() || t.isEmpty()) {
            return java.util.Optional.empty();
        }
        return regionService.children(d, false, null).stream()
                .filter(r -> SysRegion.LEVEL_STREET.equals(r.level()))
                // 名字可能带后缀差异（「福城街道」vs「福城街道办事处」），前缀匹配兜一手
                .filter(r -> r.name().equals(t) || r.name().startsWith(t) || t.startsWith(r.name()))
                .map(ai.neargo.shop.platform.RegionService.RegionVO::regionCode)
                .findFirst();
    }

    @Override
    public java.util.Optional<RegionCoords> regionCoords(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return java.util.Optional.empty();
        }
        var path = regionService.path(regionCode);
        if (path.isEmpty()) {
            return java.util.Optional.empty();
        }
        var self = path.get(path.size() - 1);
        return self.latE6() == null || self.lngE6() == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new RegionCoords(self.latE6(), self.lngE6()));
    }

    @Override
    public java.util.Map<String, String> regionNames(java.util.Collection<String> regionCodes) {
        if (regionCodes == null || regionCodes.isEmpty()) {
            return java.util.Map.of();
        }
        /*
         * **一次查完，不逐个走祖先链。**
         *
         * 原来这里对每个码调一次 path()，而 path() 自己又逐级 selectOne 向上走到省。
         * 2026-10-08 生产实测：GET /biz/communities 要 **77 秒** ——
         * 23657 个社区 × 两个方法（本方法 + regionRural）× 4 层 ≈ 189,000 次往返。
         * 而它走完整条链之后只取 path.get(size-1)，那就是码自己那一行：祖先全查了又全丢了。
         *
         * 这个缺陷任何测试都抓不到：H2 里只有十几条聚落，那个乘数是 80 次、跑 5 毫秒。
         * 代码一行没错、闸门全绿、生产 77 秒 —— 它是被上线当天的慢日志抓到的。
         */
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        regionService.byCodes(regionCodes)
                .forEach((code, brief) -> out.put(code, brief.name()));
        return out;
    }

    @Override
    public java.util.Map<String, Boolean> regionRural(java.util.Collection<String> regionCodes) {
        if (regionCodes == null || regionCodes.isEmpty()) {
            return java.util.Map.of();
        }
        // 与上面 regionNames 同一条：一次 IN，不走祖先链。理由见那段注释
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        regionService.byCodes(regionCodes)
                .forEach((code, brief) -> out.put(code, brief.rural()));
        return out;
    }




}
