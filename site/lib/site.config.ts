/**
 * 站点常量 —— 零硬编码的收口处。
 *
 * 域名、备案号、下载链接、联系方式只许出现在这里。散在页面里的后果是实测过的：
 * 上一轮有两处写死了占位域名 `shop.example.com`，物料生成得出来但指向不存在的地方
 * （见 TDD-店铺码与分享）。
 *
 * 带 `TODO:` 的是**已知的空占位**，构建期由 lib/site.config.test.ts 拦住，
 * 不许在 `NODE_ENV=production` 下漏到产物里。
 */
export const site = {
  name: "虹选 · 好物",
  nameCompact: "虹选好物", // App 桌面名 / 商标 / 备案：连写，不带间隔号
  nameEn: "HX MALL",
  domain: "hxmall.top",
  /**
   * 规范地址是 **www**（nginx 的 server_name、TLS 证书、部署文档都以它为准）。
   * 裸域当前由默认 server 兜底，出的是同一份内容 —— 两个地址同一份页面就是重复内容，
   * canonical / sitemap / OG 必须指认其中一个，否则权重被拆成两半。
   * 裸域 301 到 www 是 nginx 侧的事，还没做（见部署 README）。
   */
  url: "https://www.hxmall.top",
  /**
   * **兜底**描述：每页的 description 来自各自 md 的 frontmatter，
   * 这一句只在没有页面覆盖时出现（以及 OG 的站点级默认）。
   */
  description:
    "面向社区门店的线上经营系统。零门槛开店、0 元起步，一部手机完成上架、接单、发货、核销与对账；支持多门店、员工分权与自有品牌小程序。",
  slogan: "好物在身边",
  tagline: "楼下的好东西，都在虹选。",

  /**
   * 两个端的入口。写成常量是因为它们同时出现在顶栏、页脚、正文 CTA 与 /download/，
   * 而 nginx 上的挂载点是会变的（见部署 README 的 /b/ 与 /c/）。
   */
  entry: {
    merchant: "/b/",
    consumer: "/c/",
  },

  /** 法律主体 —— 页脚、隐私政策、用户协议、App 关于页必须出现全称，不能用品牌名代替 */
  legal: {
    company: "深圳虹选科技有限公司",
    icp: "", // TODO: 备案下来后填，格式 粤ICP备XXXXXXXX号-X
    parentBrand: "虹选科技",
    parentDomain: "hxtech.top",
  },

  contact: {
    email: "hello@hxmall.top",
    /** 招商入口。空着时「加微信聊聊」渲染成邮件，不出死链 —— 同 download 的处理 */
    salesWechatQr: "", // TODO: 企微二维码图片路径，招商口径定了再填
  },

  /**
   * 下载入口 —— **空值是合法状态**：页面据此渲染成「即将上线」而不是死链。
   *
   * 顾客端与商家端分开：两个 App 的 applicationId 不同（`ai.neargo.shop.c` /
   * `top.hxmall.bapp`），装的是两个东西，同一个按钮下两份包会装串。
   */
  download: {
    /** 顾客端 · 虹选好物 */
    consumerAppStore: "", // TODO: 上架后填
    consumerAndroid: "", // TODO: 上架后填
    consumerMiniProgram: "", // TODO: 小程序码图片路径
    /**
     * 商家端 · 虹选商家（Android APK 直链，托管在 COS 的 download 桶）。
     * 形如 https://hxmall-download-<APPID>.cos.ap-guangzhou.myqcloud.com/b-app/latest.apk
     * 发布流程见 deploy/tencent/README.md §商家端 App 分发。
     */
    merchantAndroid: "",
    /** 商家端安卓包的版本号，跟着链接一起改 —— 页面上要让人看得出下的是哪一版 */
    merchantAndroidVersion: "",
  },
} as const;

export type SiteConfig = typeof site;
