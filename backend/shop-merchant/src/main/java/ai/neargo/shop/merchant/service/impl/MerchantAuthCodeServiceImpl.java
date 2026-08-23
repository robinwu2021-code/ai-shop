package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.SysAuthCode;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.SysAuthCodeMapper;
import ai.neargo.shop.merchant.service.MerchantAuthCodeService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

@Service
public class MerchantAuthCodeServiceImpl implements MerchantAuthCodeService {

    private static final String ACTIVE = "ACTIVE";

    private final SysAuthCodeMapper authCodeMapper;
    private final MchEntityMapper merchantMapper;
    private final ObjectMapper json;
    /** 撤码影响面：product → 这边只要一个数（见 CategoryUsagePort 的类注释） */
    private final ai.neargo.shop.spi.product.CategoryUsagePort categoryUsagePort;

    public MerchantAuthCodeServiceImpl(SysAuthCodeMapper authCodeMapper, MchEntityMapper merchantMapper,
                                       ai.neargo.shop.spi.product.CategoryUsagePort categoryUsagePort,
                                       ObjectMapper json) {
        this.categoryUsagePort = categoryUsagePort;
        this.authCodeMapper = authCodeMapper;
        this.merchantMapper = merchantMapper;
        this.json = json;
    }

    @Override
    public List<AuthCodeVO> listCodes() {
        return authCodeMapper.selectList(Wrappers.<SysAuthCode>lambdaQuery()
                        .eq(SysAuthCode::getEnabled, true)
                        .orderByAsc(SysAuthCode::getSort)).stream()
                .map(c -> new AuthCodeVO(c.getCode(), c.getName(), c.getRequiredQualification(), c.getQualType()))
                .toList();
    }

    @Override
    @Transactional
    public SetResult setCodes(String merchantNo, List<String> codes, String reason) {
        // 改的是「这家店能上架什么」，没有理由的改动事后无法复盘
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **不允许撤空**。撤空之后商家会静默失去上架能力 ——
         * 他的商品还在架上，新品却怎么也上不去，而错误信息说的是「没有资质」。
         * 要停止经营请走封禁或归档，那是明示的动作，商家会收到通知。
         */
        if (codes == null || codes.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * executeWithoutScope：调用方是**运营**，本来就该能改任意商家。
         * 不解除数据域的话，where 会被追加成匹配不到任何行 ——
         * 而 updateById 匹配 0 行**不报错**：接口返回成功、审计日志也记了，
         * 唯独商家的授权一点没变。上一次同形状的故障是「订单被过滤成空」。
         */
        MchEntity m = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 没过审就授权等于提前放行
        if (!ACTIVE.equals(m.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        Set<String> known = listCodes().stream().map(AuthCodeVO::code).collect(java.util.stream.Collectors.toSet());
        for (String c : codes) {
            if (!known.contains(c)) {
                // 写进去一个不存在的码 = 一个永远不会被任何类目命中的授权，静默失效
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
        }

        /*
         * ⚠️ 这里**没有**校验「资质证件是否已上传」——B-11.1.2 资质上传还没落地，
         * mch_entity 上根本没有证件字段。ops-web 的 mock 里有这条规则，
         * 等证件表落地后在这里补上。不假装校验过：现在的口径是「运营看着证件放行」。
         */
        /*
         * 撤码的**影响面**要在这一步算：算完再写。
         *
         * 写完再算的话，撤掉的码已经不在库里了，「哪些商品受影响」就只能靠调用方
         * 自己记一份 —— 而那份记录迟早与真正写进去的不一致。
         */
        List<String> before = readCodes(m.getCategoryCodes());
        List<String> revoked = before.stream().filter(c -> !codes.contains(c)).toList();
        long affected = revoked.isEmpty() ? 0
                : categoryUsagePort.countOnShelfGoodsRequiring(merchantNo, revoked);

        m.setCategoryCodes(json.writeValueAsString(codes));
        int updated = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                merchantMapper.updateById(m));
        if (updated == 0) {
            // 静默失败在这里是最危险的：接口成功、日志有记录、授权没生效
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new SetResult(codes, revoked, affected);
    }

    /** 主体上那份码存 JSON。解析失败按空处理 —— 脏数据不该让「改授权」这条路走不通 */
    private List<String> readCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new tools.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
