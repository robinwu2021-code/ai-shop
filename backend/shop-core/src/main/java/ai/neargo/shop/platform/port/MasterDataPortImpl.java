package ai.neargo.shop.platform.port;

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
    public boolean supportsSubsidy(String payChannel) {
        return masterDataService.supportsSubsidy(payChannel);
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
        boolean official = "VILLAGE".equals(self.level())
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
                .filter(r -> "STREET".equals(r.level()))
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
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String code : regionCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            var path = regionService.path(code);
            if (!path.isEmpty()) {
                out.put(code, path.get(path.size() - 1).name());
            }
        }
        return out;
    }

    @Override
    public java.util.Map<String, Boolean> regionRural(java.util.Collection<String> regionCodes) {
        if (regionCodes == null || regionCodes.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (String code : regionCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            var path = regionService.path(code);
            if (!path.isEmpty()) {
                out.put(code, path.get(path.size() - 1).rural());
            }
        }
        return out;
    }

    @Override
    public String channelName(String payChannel) {
        return masterDataService.channelName(payChannel);
    }
    @Override
    public java.util.List<String> enabledChannels(String market) {
        return masterDataService.enabledChannels(market);
    }

}
