package ai.neargo.shop.pay.access.service;

import ai.neargo.shop.pay.access.entity.PayApiRequestLog;

/**
 * 请求留痕（四向记录之①，TDD-支付服务-I5接入层 F05）：每次请求一行，鉴权失败也写。
 * <b>独立事务、永不抛</b> —— 记录失败不能让请求失败，更不能让业务回滚。
 */
public interface ApiRequestRecorder {

    void record(PayApiRequestLog row);
}
