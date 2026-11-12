---
Title: 开发者文档与示例下载
Description: Near Pay 服务端接入指南与公开离线示例下载。
Sort: 30
---

# Near Pay 开发者文档

面向第三方业务系统的服务端接入，覆盖应用申请、密钥、签名、支付、退款和异步通知。

- [外部 API 接入指南](外部API接入指南.md)：可作为开发者网站的主体内容。
- [Python 签名与调用示例](samples/open_api.py)：标准库实现P1/P2，支持离线自检。
- [Node.js 加解密示例](samples/crypto.mjs)：内置crypto实现P3/P4及离线自检。
- [公开测试向量](samples/test-vectors.json)：用于实现校对，不可用于真实账户。

下载示例和公开测试向量后，请保存到同一目录，再执行指南中的离线自检。正式环境接入资料由平台提供。
