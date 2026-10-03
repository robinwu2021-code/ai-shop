package ai.neargo.shop.fulfillment.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.fulfillment.dto.FreightTemplateVO;
import ai.neargo.shop.fulfillment.entity.FulFreightTemplate;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.FreightTemplateMapper;
import ai.neargo.shop.spi.fulfillment.FreightPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

@Component
public class FreightPortImpl implements FreightPort {

    private static final Logger log = LoggerFactory.getLogger(FreightPortImpl.class);

    private final FreightTemplateMapper templateMapper;
    private final ObjectMapper json;

    public FreightPortImpl(FreightTemplateMapper templateMapper, ObjectMapper json) {
        this.templateMapper = templateMapper;
        this.json = json;
    }

    @Override
    public Optional<Quote> quote(String templateNo, int weighedGram, int unweighedUnits, long goodsAmountMinor,
                                 String receiverAddress) {
        Optional<Template> t = template(templateNo);
        if (t.isEmpty()) {
            return Optional.empty();
        }
        Template tp = t.get();
        String address = receiverAddress == null ? "" : receiverAddress;
        /*
         * **按开头匹配，不按包含**：收货地址是「省 + 市 + 区 + 详细」拼的（UserQueryPortImpl#receiverOf），
         * 包含匹配会让「广东省广州市越秀区北京路」命中「北京」的规则。地区名写省份简称即可（「新疆」匹配
         * 「新疆维吾尔自治区…」）。
         */
        Rule hit = tp.rules().stream()
                .filter(r -> r.region() != null && !r.region().isBlank() && address.startsWith(r.region().trim()))
                .findFirst().orElse(null);
        if (hit != null && ACTION_REJECT.equals(hit.action())) {
            return Optional.of(new Quote(tp.templateNo(), 0L, true, hit.region(), false));
        }
        // 满额免邮在地区加收之前判：「满 99 包邮」对买家说的就是整单不要运费，偏远也一样 —— 不包就别写包邮
        if (tp.freeThreshold() > 0 && goodsAmountMinor >= tp.freeThreshold()) {
            return Optional.of(new Quote(tp.templateNo(), 0L, false, hit == null ? null : hit.region(), true));
        }
        int weight = Math.max(0, weighedGram) + Math.max(0, unweighedUnits) * tp.firstWeightGram();
        long fee = FreightPort.fee(weight, tp.firstWeightGram(), tp.firstFee(), tp.addWeightGram(), tp.addFee())
                + (hit == null ? 0L : Math.max(0L, hit.surcharge()));
        return Optional.of(new Quote(tp.templateNo(), fee, false, hit == null ? null : hit.region(), false));
    }

    @Override
    public Optional<Template> template(String templateNo) {
        FulFreightTemplate row = null;
        if (templateNo != null && !templateNo.isBlank()) {
            row = DataScopeContext.executeWithoutScope(() -> templateMapper.selectOne(
                    Wrappers.<FulFreightTemplate>lambdaQuery()
                            .eq(FulFreightTemplate::getTemplateNo, templateNo)
                            .isNull(FulFreightTemplate::getArchivedAt)
                            .last("limit 1")));
            if (row == null) {
                // 门店指着一个已归档 / 不存在的模板：回落默认，不让这一单变成 0 元运费
                log.warn("[freight] 模板 {} 不可用，回落平台默认模板", templateNo);
            }
        }
        if (row == null) {
            row = DataScopeContext.executeWithoutScope(() -> templateMapper.selectOne(
                    Wrappers.<FulFreightTemplate>lambdaQuery()
                            .eq(FulFreightTemplate::getIsDefault, 1)
                            .isNull(FulFreightTemplate::getArchivedAt)
                            .orderByDesc(FulFreightTemplate::getId)
                            .last("limit 1")));
        }
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new Template(row.getTemplateNo(), row.getName(), nz(row.getFirstWeightGram()),
                nzL(row.getFirstFee()), nz(row.getAddWeightGram()), nzL(row.getAddFee()), nzL(row.getFreeThreshold()),
                rules(row.getOutOfRange())));
    }

    private List<Rule> rules(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return json.readValue(raw, new TypeReference<List<FreightTemplateVO.OutOfRangeVO>>() { }).stream()
                .map(r -> new Rule(r.region(), r.action(), r.surcharge())).toList();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nzL(Long v) {
        return v == null ? 0L : v;
    }
}
