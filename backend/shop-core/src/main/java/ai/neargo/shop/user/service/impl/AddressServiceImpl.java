package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.AddressService;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.AddressVO;
import ai.neargo.shop.user.entity.UsrAddress;
import ai.neargo.shop.user.mapper.UserMappers.AddressMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressServiceImpl implements AddressService {

    private final AddressMapper addressMapper;

    public AddressServiceImpl(AddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    @Override
    public List<AddressVO> list() {
        return rows().stream().map(AddressVO::forOwner).toList();
    }

    @Override
    @Transactional
    public List<AddressVO> save(SaveCommand cmd) {
        UsrAddress row = cmd.addressId() == null || cmd.addressId().isBlank()
                ? newRow() : requireOwn(cmd.addressId());

        row.setName(cmd.name());
        row.setPhone(cmd.phone());
        row.setProvince(cmd.province());
        row.setCity(cmd.city());
        row.setDistrict(cmd.district());
        row.setDetail(cmd.detail());
        row.setTag(cmd.tag());

        // 首个地址自动成为默认：否则用户加完第一个地址去下单，还要回来手动设一次
        boolean makeDefault = Boolean.TRUE.equals(cmd.isDefault()) || rows().isEmpty();
        row.setIsDefault(makeDefault);

        if (row.getId() == null) {
            addressMapper.insert(row);
        } else {
            addressMapper.updateById(row);
        }
        if (makeDefault) {
            clearOtherDefaults(row.getAddressId());
        }
        return list();
    }

    @Override
    @Transactional
    public List<AddressVO> archive(String addressId) {
        UsrAddress row = requireOwn(addressId);
        addressMapper.deleteById(row.getId());   // 逻辑删除
        return list();
    }

    @Override
    @Transactional
    public List<AddressVO> setDefault(String addressId) {
        UsrAddress row = requireOwn(addressId);
        row.setIsDefault(true);
        addressMapper.updateById(row);
        clearOtherDefaults(addressId);
        return list();
    }

    /**
     * 「至多一条默认」的落点。用 UPDATE 批量清而不是逐条读改：
     * 逐条读改在并发下会留下两条默认（两个请求各自读到对方之前的状态）。
     */
    private void clearOtherDefaults(String keepAddressId) {
        UsrAddress patch = new UsrAddress();
        patch.setIsDefault(false);
        addressMapper.update(patch, Wrappers.<UsrAddress>lambdaUpdate()
                .eq(UsrAddress::getUserNo, SecurityUtils.currentUserNo())
                .ne(UsrAddress::getAddressId, keepAddressId)
                .eq(UsrAddress::getIsDefault, true));
    }

    private UsrAddress newRow() {
        UsrAddress row = new UsrAddress();
        row.setAddressId(BizKey.next(BizKey.ADDRESS));
        row.setUserNo(SecurityUtils.currentUserNo());
        return row;
    }

    /**
     * 属主校验：查询条件带 {@code userNo}，查不到即 404（而不是先查出来再比对）。
     * 先查后比会让「别人的地址」和「不存在的地址」返回不同的错误 —— 那本身就是一种信息泄漏。
     */
    private UsrAddress requireOwn(String addressId) {
        UsrAddress row = addressMapper.selectOne(Wrappers.<UsrAddress>lambdaQuery()
                .eq(UsrAddress::getAddressId, addressId)
                .eq(UsrAddress::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private List<UsrAddress> rows() {
        return addressMapper.selectList(Wrappers.<UsrAddress>lambdaQuery()
                .eq(UsrAddress::getUserNo, SecurityUtils.currentUserNo())
                .orderByDesc(UsrAddress::getIsDefault)
                .orderByDesc(UsrAddress::getId));
    }
}
