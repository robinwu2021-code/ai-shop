package ai.neargo.shop.platform.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ServiceScopes;
import ai.neargo.shop.platform.ServiceScopeService;
import ai.neargo.shop.platform.SettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@link ServiceScopeService} 实现。启用白名单存 {@code sys_setting}，**不做任何统计**。 */
@Service
public class ServiceScopeServiceImpl implements ServiceScopeService {

    /** 与 {@code MasterDataServiceImpl} 读的是同一个键 —— 一处读、一处写，不能各存一份 */
    static final String KEY = "merchant.service-scope-enabled";

    /**
     * 没配过时的兜底：<b>全开</b>。
     *
     * <p>与本仓库既有口径一致（{@code SettingServiceImpl}「少一行参数不该让整个配置页打不开」、
     * 门店公告的敏感词表坏了就放行）：参数缺失时**放行**而不是拦下。
     * 拦下的最坏情况是全平台商家都保存不了门店，症状是「保存没反应」；
     * 放行的最坏情况是多开了一档，运营在后台一眼能看到。
     *
     * <p>值域校验不受这个兜底影响 —— 那一层永远生效。
     */
    static final String DEFAULT_JSON = "[\"COMMUNITY\",\"CITY\",\"PLATFORM\"]";

    /** 档位的展示与存储顺序：按履约半径从小到大，与 ADR-009 的叙述一致 */
    static final List<String> ORDER = List.of(
            ServiceScopes.COMMUNITY, ServiceScopes.CITY, ServiceScopes.PLATFORM);

    private final SettingService settingService;
    private final ObjectMapper json;

    public ServiceScopeServiceImpl(SettingService settingService, ObjectMapper json) {
        this.settingService = settingService;
        this.json = json;
    }

    @Override
    public Set<String> enabledScopes() {
        try {
            return new LinkedHashSet<>(json.readValue(settingService.get(KEY, DEFAULT_JSON),
                    new TypeReference<List<String>>() {
                    }));
        } catch (RuntimeException e) {
            // 白名单坏了按全开处理，理由同 DEFAULT_JSON
            return new LinkedHashSet<>(ORDER);
        }
    }

    @Override
    @Transactional
    public void setEnabled(String scope, boolean enabled, String reason) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 档位是枚举，不是自由文本 —— 写进白名单的必须是真档位，
        // 否则白名单里会躺着一个谁也匹配不上的字符串，而界面上看不出任何异常
        if (!ServiceScopes.ALL.contains(scope)) {
            throw BizException.of(ErrorCode.SERVICE_SCOPE_NOT_ALLOWED);
        }

        Set<String> next = new LinkedHashSet<>(enabledScopes());
        if (enabled) {
            next.add(scope);
        } else {
            next.remove(scope);
        }
        /*
         * **不许清空**，与「商家授权不能撤空」是同一条道理：
         * 白名单空掉之后所有商家保存门店都会被拒，而错误信息说的是
         * 「当前不支持这个经营范围」—— 商家会以为是自己选错了，把档位挨个试一遍，
         * 每次都被拒。要停止入驻请走别的开关，那是明示的动作。
         */
        if (next.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "至少要开放一档经营范围 —— 全关等于所有商家都保存不了门店");
        }

        String operator = ai.neargo.shop.auth.SecurityUtils.requireUser().userNo();
        // 按 ORDER 归一化再写回：存进去的顺序不该取决于运营点开关的先后
        settingService.put(KEY, json.writeValueAsString(
                ORDER.stream().filter(next::contains).toList()), operator);
    }
}
