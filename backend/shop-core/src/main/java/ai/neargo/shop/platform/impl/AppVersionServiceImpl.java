package ai.neargo.shop.platform.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.AppVersionService;
import ai.neargo.shop.platform.AppVersions;
import ai.neargo.shop.platform.dto.AppVersionVOs.AppVersionVO;
import ai.neargo.shop.platform.dto.AppVersionVOs.OpsAppVersionVO;
import ai.neargo.shop.platform.dto.AppVersionVOs.SaveAppVersionReq;
import ai.neargo.shop.platform.dto.AppVersionVOs.UpgradeRequirement;
import ai.neargo.shop.platform.entity.SysAppVersion;
import ai.neargo.shop.platform.mapper.PlatformMappers.AppVersionMapper;
import ai.neargo.shop.spi.platform.AuditLogPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AppVersionServiceImpl implements AppVersionService {

    private static final List<String> PLATFORMS = List.of("ANDROID", "IOS");
    private static final List<String> LANGS = List.of("zh-Hant", "zh-Hans", "en", "ar");
    private static final String FALLBACK_LANG = "zh-Hant";
    private static final Set<String> STORE_HOSTS = Set.of("apps.apple.com", "play.google.com");
    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final AppVersionMapper mapper;
    private final AuditLogPort auditLogPort;
    private final ObjectMapper json;
    /** 拒写闸门用的本机缓存：平台 → 策略行（没配为 empty）。运营端保存时整张清掉 */
    private final Map<String, Cached> gateCache = new ConcurrentHashMap<>();

    private record Cached(Optional<SysAppVersion> row, long loadedAt) {
    }

    public AppVersionServiceImpl(AppVersionMapper mapper, AuditLogPort auditLogPort, ObjectMapper json) {
        this.mapper = mapper;
        this.auditLogPort = auditLogPort;
        this.json = json;
    }

    @Override
    public AppVersionVO current(String platform, String build, String acceptLanguage) {
        String p = platformOrNull(platform);
        if (p == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        SysAppVersion row = find(MERCHANT, p);
        if (row == null) {
            return new AppVersionVO(p, "", "", List.of(), null, null);
        }
        String min = blankToEmpty(row.getMinSupportedVersion());
        Boolean upgradeRequired = build == null || build.isBlank() || !AppVersions.isVersion(build) ? null
                : !min.isEmpty() && AppVersions.compare(build, min) < 0;
        Map<String, List<String>> notes = readNotes(row.getNotes());
        List<String> picked = notes.getOrDefault(language(acceptLanguage), List.of());
        if (picked.isEmpty()) {
            picked = notes.getOrDefault(FALLBACK_LANG, List.of());
        }
        return new AppVersionVO(p, blankToEmpty(row.getLatestVersion()), min, picked, row.getStoreUrl(),
                upgradeRequired);
    }

    @Override
    public Optional<UpgradeRequirement> blockingUpgrade(String platformHeader, String versionHeader) {
        String p = platformOrNull(platformHeader);
        if (p == null || versionHeader == null || !AppVersions.isVersion(versionHeader)) {
            return Optional.empty();
        }
        long now = System.nanoTime();
        Cached c = gateCache.get(p);
        if (c == null || now - c.loadedAt() > CACHE_TTL_NANOS) {
            c = new Cached(Optional.ofNullable(find(MERCHANT, p)), now);
            gateCache.put(p, c);
        }
        return c.row()
                .filter(r -> AppVersions.isVersion(r.getMinSupportedVersion()))
                .filter(r -> AppVersions.compare(versionHeader, r.getMinSupportedVersion()) < 0)
                .map(r -> new UpgradeRequirement(r.getMinSupportedVersion(), r.getStoreUrl()));
    }

    @Override
    public List<OpsAppVersionVO> list() {
        List<OpsAppVersionVO> out = new ArrayList<>();
        for (String p : PLATFORMS) {
            SysAppVersion row = find(MERCHANT, p);
            out.add(row == null ? new OpsAppVersionVO(MERCHANT, p, "", "", Map.of(), null, null, null, 0L) : toOps(row));
        }
        return out;
    }

    @Override
    @Transactional
    public OpsAppVersionVO save(String appCode, String platform, SaveAppVersionReq req, String operatorNo) {
        if (!MERCHANT.equals(appCode) || !PLATFORMS.contains(platform)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String latest = blankToNull(req.latest());
        String min = blankToNull(req.minSupported());
        String storeUrl = blankToNull(req.storeUrl());
        Map<String, List<String>> notes = cleanNotes(req.notes());
        validate(latest, min, storeUrl, notes);

        SysAppVersion row = find(appCode, platform);
        String before = row == null ? null : snapshot(row);
        long expected = req.expectedRevision();
        if (row == null) {
            if (expected != 0) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            row = new SysAppVersion();
            row.setAppCode(appCode);
            row.setPlatform(platform);
            row.setLatestVersion(latest);
            row.setMinSupportedVersion(min);
            row.setNotes(json.writeValueAsString(notes));
            row.setStoreUrl(storeUrl);
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException race) {
                // 两个人同时给没配过的平台建第一行：后到的那个按「revision 对不上」处理，让他刷新再改
                throw BizException.of(ErrorCode.CONFLICT);
            }
        } else {
            if (row.getVersion() == null || row.getVersion() != expected) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            // 乐观锁插件按实体上的 version 加 where 条件并 +1；清空的字段要显式 set null（updateById 会跳过 null）
            SysAppVersion lock = new SysAppVersion();
            lock.setVersion(expected);
            int n = mapper.update(lock, Wrappers.<SysAppVersion>lambdaUpdate()
                    .set(SysAppVersion::getLatestVersion, latest)
                    .set(SysAppVersion::getMinSupportedVersion, min)
                    .set(SysAppVersion::getNotes, json.writeValueAsString(notes))
                    .set(SysAppVersion::getStoreUrl, storeUrl)
                    .eq(SysAppVersion::getId, row.getId()));
            if (n == 0) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
        gateCache.clear();
        SysAppVersion saved = find(appCode, platform);
        // 抬高最低支持版会让低于它的所有旧包立刻停写：按高危记
        auditLogPort.record("APP_VERSION_UPDATE", appCode + ":" + platform,
                "最新版 " + (latest == null ? "（空）" : latest) + "，最低支持版 " + (min == null ? "（空）" : min),
                true, before, snapshot(saved));
        return toOps(saved);
    }

    private static void validate(String latest, String min, String storeUrl, Map<String, List<String>> notes) {
        if (latest != null && !AppVersions.isVersion(latest) || min != null && !AppVersions.isVersion(min)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 只配最低版、不配最新版：App 会被要求升级到一个它查不到的版本
        if (min != null && (latest == null || AppVersions.compare(min, latest) > 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (storeUrl != null && !isStoreUrl(storeUrl)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 已拍板 P6：zh-Hant 必填（其它语言回落到它），发版前不必等翻译
        if (latest != null && notes.getOrDefault(FALLBACK_LANG, List.of()).isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /** 只收两家商店的 https 地址。按 URI 的 host 整串比，{@code apps.apple.com.evil.example} 过不去 */
    private static boolean isStoreUrl(String url) {
        try {
            URI u = new URI(url);
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && u.getPort() == -1
                    && u.getHost() != null && STORE_HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** 只留认得的四种语言、去掉空行；认不得的语言 → 10400（写错一个键，说明就静默丢了） */
    private static Map<String, List<String>> cleanNotes(Map<String, List<String>> raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (var e : raw.entrySet()) {
            if (!LANGS.contains(e.getKey())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            List<String> lines = e.getValue() == null ? List.of() : e.getValue().stream()
                    .filter(l -> l != null && !l.isBlank()).map(String::strip).toList();
            if (!lines.isEmpty()) {
                out.put(e.getKey(), lines);
            }
        }
        return out;
    }

    /**
     * {@code Accept-Language} 归并成四种之一：取头里第一个能归并的标签，不分大小写。
     * {@code zh-Hant*}、{@code zh-TW}、{@code zh-HK}、{@code zh-MO}、裸 {@code zh} → zh-Hant；
     * {@code zh-Hans*}、{@code zh-CN}、{@code zh-SG} → zh-Hans；{@code en*} → en；{@code ar*} → ar；都不中 → zh-Hant。
     */
    static String language(String acceptLanguage) {
        if (acceptLanguage == null) {
            return FALLBACK_LANG;
        }
        for (String part : acceptLanguage.split(",")) {
            String tag = part.split(";", 2)[0].strip().toLowerCase(Locale.ROOT).replace('_', '-');
            if (tag.startsWith("zh-hans") || tag.startsWith("zh-cn") || tag.startsWith("zh-sg")) {
                return "zh-Hans";
            }
            if (tag.equals("zh") || tag.startsWith("zh-")) {
                return FALLBACK_LANG;
            }
            if (tag.equals("en") || tag.startsWith("en-")) {
                return "en";
            }
            if (tag.equals("ar") || tag.startsWith("ar-")) {
                return "ar";
            }
        }
        return FALLBACK_LANG;
    }

    private static String platformOrNull(String platform) {
        if (platform == null) {
            return null;
        }
        String p = platform.strip().toUpperCase(Locale.ROOT);
        return PLATFORMS.contains(p) ? p : null;
    }

    private SysAppVersion find(String appCode, String platform) {
        return mapper.selectOne(Wrappers.<SysAppVersion>lambdaQuery()
                .eq(SysAppVersion::getAppCode, appCode)
                .eq(SysAppVersion::getPlatform, platform)
                .last("limit 1"));
    }

    private Map<String, List<String>> readNotes(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return json.readValue(raw, new TypeReference<LinkedHashMap<String, List<String>>>() { });
    }

    private OpsAppVersionVO toOps(SysAppVersion r) {
        return new OpsAppVersionVO(r.getAppCode(), r.getPlatform(), blankToEmpty(r.getLatestVersion()),
                blankToEmpty(r.getMinSupportedVersion()), readNotes(r.getNotes()), r.getStoreUrl(), r.getUpdatedBy(),
                r.getUpdatedAt() == null ? null
                        : r.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                r.getVersion() == null ? 0L : r.getVersion());
    }

    private String snapshot(SysAppVersion r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("latest", r.getLatestVersion());
        m.put("minSupported", r.getMinSupportedVersion());
        m.put("storeUrl", r.getStoreUrl());
        m.put("notes", readNotes(r.getNotes()));
        return json.writeValueAsString(m);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }
}
