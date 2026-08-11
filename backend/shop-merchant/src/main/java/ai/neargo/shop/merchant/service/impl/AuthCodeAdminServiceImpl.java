package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.SysAuthCode;
import ai.neargo.shop.merchant.mapper.MerchantMappers.SysAuthCodeMapper;
import ai.neargo.shop.merchant.service.AuthCodeAdminService;
import ai.neargo.shop.spi.product.CategoryUsagePort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** {@link AuthCodeAdminService} 实现。 */
@Service
public class AuthCodeAdminServiceImpl implements AuthCodeAdminService {

    private final SysAuthCodeMapper mapper;
    private final MerchantQueryPort merchantQuery;
    private final CategoryUsagePort categoryUsage;

    public AuthCodeAdminServiceImpl(SysAuthCodeMapper mapper, MerchantQueryPort merchantQuery,
                                    CategoryUsagePort categoryUsage) {
        this.mapper = mapper;
        this.merchantQuery = merchantQuery;
        this.categoryUsage = categoryUsage;
    }

    @Override
    public List<AuthCodeAdminVO> list() {
        return mapper.selectList(Wrappers.<SysAuthCode>lambdaQuery()
                .orderByAsc(SysAuthCode::getSort)).stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public AuthCodeAdminVO save(SaveCommand cmd) {
        if (cmd.code() == null || cmd.code().isBlank() || cmd.name() == null || cmd.name().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        SysAuthCode row = find(cmd.code());
        if (row == null) {
            row = new SysAuthCode();
            row.setCode(cmd.code());
            // 新码默认**启用**：运营刚建完就要能发出去，
            // 建完再点一次启用是纯粹的多余步骤，而漏点的后果是「建了但发不了」
            row.setEnabled(true);
        }
        row.setName(cmd.name());
        // 空字符串归一成 null：资质栏空着的意思是「无证件要求」，
        // 而 "" 与 null 在端上会渲染成两种样子（一个空白格 vs 一个「—」）
        row.setRequiredQualification(blankToNull(cmd.requiredQualification()));
        if (cmd.sort() != null) {
            row.setSort(cmd.sort());
        }
        if (row.getId() == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return toVO(row);
    }

    @Override
    @Transactional
    public AuthCodeAdminVO setEnabled(String code, boolean enabled, String reason) {
        // 改的是「一批商家还能不能上新品」，没有理由的改动事后无法复盘
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        SysAuthCode row = require(code);
        /*
         * **还有在用的类目引用它就不许停。**
         *
         * 停掉之后那些类目会要求一个已经停用的码 —— 也就是永远拒绝所有人，
         * 而商家看到的只是「你还没有资质授权」，去哪申请没人知道。
         * V5 的注释里写过这个形状：一个只会拒绝的校验比没有校验更糟，因为它看起来在工作。
         *
         * 所以顺序是**先把类目改到别的码上（或归档），再停这个码**。
         */
        if (!enabled && categoryUsage.countByRequiredCode(code) > 0) {
            throw new BizException(ErrorCode.CATEGORY_IN_USE,
                    "还有类目要求这个授权码，先把它们改到别的码上或归档，再停用");
        }
        row.setEnabled(enabled);
        mapper.updateById(row);
        return toVO(row);
    }

    // ---------------------------------------------------------------- 内部

    private SysAuthCode find(String code) {
        return mapper.selectOne(Wrappers.<SysAuthCode>lambdaQuery()
                .eq(SysAuthCode::getCode, code).last("LIMIT 1"));
    }

    private SysAuthCode require(String code) {
        SysAuthCode row = find(code);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "授权码不存在：" + code);
        }
        return row;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private AuthCodeAdminVO toVO(SysAuthCode e) {
        return new AuthCodeAdminVO(
                e.getCode(), e.getName(), e.getRequiredQualification(),
                e.getSort() == null ? 0 : e.getSort(),
                Boolean.TRUE.equals(e.getEnabled()),
                merchantQuery.countByAuthCode(e.getCode()),
                categoryUsage.countByRequiredCode(e.getCode()));
    }
}
