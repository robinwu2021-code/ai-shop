package ai.neargo.svc.context;

import java.util.List;

/**
 * 网关带给微服务的调用上下文（TDD-网关层与统一权限 §2.7）：谁、在哪一端、对哪家店、凭哪些功能点，调了哪个接口。
 *
 * <p>整体由网关签名，微服务验签后才可信。微服务不认终端令牌，也不建自己的角色表。
 *
 * @param operator   操作人编号
 * @param realm      端：CONSUMER / MERCHANT / OPERATOR
 * @param merchantNo 经营主体；不是商家端时为空
 * @param storeNo    当前门店；没有时为空
 * @param points     网关已经判过的功能点码
 * @param requestId  请求号，贯穿网关与下游
 * @param timestamp  签名时刻（毫秒）
 * @param method     HTTP 方法
 * @param path       下游地址（含查询串）
 * @param bodyDigest 请求体的 SHA-256（十六进制）；没有请求体时是空串的摘要
 */
public record SignedContext(String operator, String realm, String merchantNo, String storeNo, List<String> points,
                            String requestId, long timestamp, String method, String path, String bodyDigest) {

    public SignedContext {
        points = points == null ? List.of() : List.copyOf(points);
    }

    /** 这次调用带没带这个功能点。 */
    public boolean hasPoint(String code) {
        return points.contains(code);
    }
}
