// 外壳配置：组件库与具体 app 之间**唯一**的缝。
//
// 两端的外壳有真实差异 —— C 端底部菜单有购物车角标与飞入动效，切语言后要重拉社区文案与购物车；
// B 端都没有。表达这种差异有三种写法，这里选第三种：
//   1) 组件里写 `#ifdef` —— 铁律禁止，页面与组件都不许出现条件编译
//   2) 组件里写 `if (isCApp)` —— 库反过来认识使用者，抽了等于没抽
//   3) app 启动时把差异**注入**进来，库只认这份配置 ← 本文件
//
// 判断依据很简单：新写一个 D 端 app 时，只要它能提供这份配置就能直接用这套外壳，
// 不需要改组件库一个字。做不到这点的抽取都是假抽取。
import type { Ref } from "vue";
import type { IconName } from "@shared/design/icons";

export interface ShellTab {
  key: string;
  route: string;
  icon: IconName | string;
  iconOn: IconName | string;
  labelKey: string;
}

/**
 * 覆盖在所有页面之上的常驻层（C 端的飞入小球）**不在这份配置里**：
 * 它由约定组件 `<app-overlay>` 承担，两端各自在 `src/components/app-overlay.vue` 里提供。
 * 原因是 `<component :is>` 小程序端不支持 —— 动态组件这条路在这个技术栈上根本走不通，
 * 只能靠「同名组件、各端各有一份」这种编译期就能定下来的约定。
 */
export interface ShellConfig {
  /** 底部菜单项。C 端是消费者视角、B 端是商家视角，两套导航没有共用的意义 */
  tabs: readonly ShellTab[];
  /**
   * 该端的默认皮肤。不传则用色板里的 `DEFAULT_SKIN`。
   *
   * <p>B 端传 `brand`（品牌红）而 C 端不传 —— 默认皮肤是**端的选择**，
   * 不是色板的属性。写死在 `tokens.ts` 里就没法分端，而两端换不换是两个决定。
   * 用户自己切过皮肤之后以他存的为准，这里只管「第一次打开看到什么」。
   */
  defaultSkin?: string;
  /**
   * tab 角标数字，返回 0 或 undefined 即不显示。
   * C 端用它挂购物车件数；B 端不传。
   */
  badge?: (tabKey: string) => number | undefined;
  /**
   * 需要「弹一下」的 tab key（购物车飞入落点的反馈）。
   * 传 ref 而不是回调：动效的触发方在 app 那边，库只负责在值变化时加 class。
   */
  pulse?: Ref<string>;
  /** 底部菜单挂载/回到前台后回调，C 端用来登记飞入动效的落点坐标 */
  onTabbarReady?: (root: unknown) => void;
  /** 切语言后的副作用。C 端要重拉服务端下发的文案，否则会中英阿混排 */
  onLangChange?: () => void | Promise<void>;
  /** 切市场后的副作用。换货币 + 换时区，依赖服务端计价的数据要重拉 */
  onMarketChange?: () => void | Promise<void>;
}

let config: ShellConfig = { tabs: [] };

/** 在 app 的 `onLaunch` 里调用一次。晚于组件挂载调用会拿到空 tabs，菜单就是空的 */
export function configureShell(next: ShellConfig): void {
  config = next;
}

export function useShell(): ShellConfig {
  return config;
}
