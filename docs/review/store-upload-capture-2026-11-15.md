# 166 进件附件转发修复与定向复测

## 问题与处理

同一份虚构 NearGo 水印登记附件：PAY 直连成功，公网及 Shop 直连返回 400；Shop 诊断显示 declaredBytes=80616 / capturedBytes=0，PAY 收不到 requestNo 或 file。已定位为 Shop 转发前请求流被提前读取；未确认具体框架消费者，不将猜测写成根因。

在身份认证成功后、下游链处理前，使用现有有界解析器捕获附件原始字节，存于请求级属性，转发时复用。未放宽 6 MiB 总量／5 MiB 单文件约束、身份权限或门店归属校验，不写入临时文件，不记录附件、凭据或表单原文。

## 验证

- `PayBizUploadCaptureTest`：复现下游提前读流时字节丢失；先失败后通过。拒绝身份不读取输入流。
- 该测试及 `PayBizRequestBodyTest`、`PayBizOnboardingHttpTest` 共 17 项通过；没有执行全量测试。
- 166 发布源 `7a4c4cfe52e9ccfc423bab8bde5b1cd960f0e8cb`，jar SHA256 `008d216fbd9fc00da46bc446c7b44fbd68da9a8f6cff65f7c347066752efd7d3`；API／OPS 健康，容器、挂载 inode 和其余资源保持原状，无数据库迁移。
- 公网真实 multipart 上传返回 200 / code0，草稿版本升至 6。
- Android 模拟器 `emulator-5560`：旧 NearGo 草稿修改五个虚构字段后 6→7、真实 App 附件上传后 7→8、确认资料成功。最终草稿 CONFIRMED，但申请 DRAFT / reviewStatus NONE，未运营送审、未审批、未供应商发送。

## 尚未通过

运营登录会话失效，现有凭据一次正常尝试未通过；没有重置账号、提升权限或伪造审核。运营提审、补件审核、渠道资格及新 H5 沙箱付款仍待正常身份及业务前置。恢复时商户账号资料 GET 返回 403/70006，其他本人门店及进件操作成功，该独立问题记录待查。

本批不部署真机、不发起真实付款。App 的真实 SSE 主动游标重连、消息／未读读取及原生 TTS 能力检查通过；它们不是新付款通知闭环证明。
