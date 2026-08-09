package ai.neargo.shop.platform.api.common;

import ai.neargo.shop.platform.MasterDataService;
import ai.neargo.shop.platform.dto.MasterDataVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主数据（行业 / 商家主体类型 / 支付渠道）。
 *
 * <p><b>挂在 {@code /common} 而不是 {@code /mp} 或 {@code /biz}</b>：这三样东西
 * 三端都要用 —— C 端展示商家行业、B 端入驻选行业与主体、平台端做筛选。
 * 挂在某一端下面，另外两端就得跨端调用一个不属于自己的前缀。
 *
 * <p><b>游客可读</b>：入驻表单在用户登录之前就要显示行业与主体选项。
 * 这里没有任何敏感内容 —— 只有取值域与展示名，密钥与平台资金账户不在其中。
 */
@RestController
public class CommonMetaController {

    private final MasterDataService masterDataService;

    public CommonMetaController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    /**
     * 主数据快照。<b>只含启用的</b>。
     *
     * <p>合成一个响应而不是三条接口，是因为它们在同一屏上被同时用到：
     * 「选行业 → 据此过滤可选主体 → 主体决定要不要传营业执照」。
     * 分三次请求就会出现「行业回来了、主体还没回来」的中间态，
     * 而那个中间态里表单不知道该不该禁用某个选项。
     */
    @GetMapping("/common/master-data")
    public MasterDataVO masterData() {
        return masterDataService.snapshot();
    }
}
