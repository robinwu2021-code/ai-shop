# 支付只读环境选择

状态：本地实施完成，待独立review与部署验收。档位1；承接沙箱收款消息与SSE的权威详情核验、本店支付列表/汇总及后台静默恢复，不变更资金或通知事实。

## 0 需求与范围

真实联调发现同一SANDBOX未支付订单，默认PRODUCTION caller返回NOT_FOUND，专用SANDBOX身份能够正常读取。不能改默认caller的不可变PRODUCTION归属，也不能按门店映射自动切环境、生产404后试探沙箱、从收据构造金额/支付成功。

| AC | 实施落点 |
| --- | --- |
| 显式只读选择，默认生产不变 | 五个既有GET的X-Pay-Read-Environment请求头 |
| 选择不是授权，严格当前主体/店权限和来源映射 | PaymentReadRouter，resolveStrict及平台来源表联查 |
| 专用身份与凭据只来自私有配置 | PaymentReadProperties，窄SandboxPaymentReadApi显式内部鉴权头 |
| 保留真实PAY完整DTO，不混合统计 | 原PortResult/ServiceCalls传输与原支付DTO |
| 任何写/补账POST保持原身份 | 仅五个GET接线，原app service写方法完全不动 |

## 1 新合同

仅既有GET `/biz/payment/collect-status`、`/biz/payment/orders`、`/biz/payment/orders/summary`、`/biz/payment/orders/lookup`、`/biz/payment/orders/{payOrderNo}`接受`X-Pay-Read-Environment`。缺省或精确`PRODUCTION`沿原服务，`SANDBOX`要求全部授权；空值、其他值、混合值拒绝。不新增公开端点/DTO/权限码，不接受客户端caller/credential来选择PAY身份。

私有配置前缀`shop.payment-collections`新增：`sandbox-read-enabled`默认false；`dedicated-sandbox-caller`、`dedicated-sandbox-credential`仅显式read开关true时必需。read开启还要求原collections.enabled=true/environment=SANDBOX。旧SSE启用但read未开时不用配置新凭据，默认支付读取不受影响。配置的toString不得包含凭据；不向默认文件写真实凭据或业务fixture。

SANDBOX请求复核当前登录MERCHANT的resolveStrict结果与当前BizContext完全相同的entity/store及当前操作权限，且本地enabled SANDBOX/CHECKOUT sourceId=payAppId来源映射匹配该店/实体；当前mch_store与mch_entity必须未删除且TW。无映射/撤销/错配/无配置一律拒绝，不回退生产。

## 2 结构

仅新增PaymentReadProperties、PaymentReadRouter、SandboxPaymentReadApi；BizPaymentController五个GET委托router，其他方法不动。复用InternalHttp共享token、服务定位、按服务拦截器、HTTP/1.1与错误合同。窄API显式发送配置的caller/credential，默认PayCallerHeaders不覆盖已明确身份；不改变全局caller，也不引入动态环境ThreadLocal。

PAY既有列表内部协议为POST `/payment-orders/search`，这是只读查询而非资金操作；其余四意图为GET。窄接口不声明create/refund/query补账等。生产和沙箱各自返回所选环境PAY真实数据，不合并支付记录、金额、cursor或汇总。receipt不参与构造金融数据。正式App默认不带header；测试构建仅这五个GET可选SANDBOX。

## 5 验证

测试覆盖缺省/显式生产路径不查沙箱表、五个真实读取DTO、非法header、来源禁用/错主体/删除/错市场、strict身份/权限丢失、配置缺失拒绝不fallback；真实method security继续拒绝无权限，携SANDBOX header的补账POST仍走原service。进程内HTTP验证共享token+专用caller/credential唯一头、全局production client不被污染、原查询body/DTO保留；H2仅作本地授权表验证，不接166/真实业务库。

## 6 实施记录

2026-11-14 本地Java21离线reactor编译成功；37项定向测试全部通过、0失败/0错误/0跳过：PaymentReadRouterTest 22、SandboxPaymentReadHttpTest 1、PayCallerHeadersTest 8、BizPaymentReadAuditTest 4、RemoteBizPaymentAppServiceTest 2。

命令：`mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=PaymentReadRouterTest,SandboxPaymentReadHttpTest,RemoteBizPaymentAppServiceTest,BizPaymentReadAuditTest,PayCallerHeadersTest -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.heap=2g`（JAVA_HOME为本机Java21）。

新增公共GET读取选择只修改controller五条读取。编译产物为PaymentReadProperties、PaymentReadRouter及Scope、SandboxPaymentReadApi、BizPaymentController，共5个class。未修改配置默认文件、caller binding、SSE/receipt代码或写路径。未部署、未访问166/真实业务库；HTTP仅127.0.0.1临时服务、来源授权仅H2。未声称全仓架构测试或线上链路通过；独立review与增量制品验收由root另行安排。
