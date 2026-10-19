import { vi } from "vitest";
import { config } from "@vue/test-utils";
export const uniMock = { showToast: vi.fn(), getStorageSync: vi.fn(() => ""), setStorageSync: vi.fn(),
  navigateTo: vi.fn(), setNavigationBarTitle: vi.fn(), stopPullDownRefresh: vi.fn() };
Object.assign(globalThis, { uni: uniMock, getCurrentPages: () => [{}] });
const components = import.meta.glob("../../packages/ui/src/components/sh-*.vue", { eager: true }) as Record<string, { default: unknown }>;
config.global.components = Object.fromEntries(Object.entries(components).map(([path, mod]) => [
  path.replace(/^.*\/(sh-[^/]+)\.vue$/, "$1"), mod.default,
])) as typeof config.global.components;
