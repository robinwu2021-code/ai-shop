import type { MenuPoint } from './types';
export type GrantState = 'granted' | 'denied' | 'unavailable' | 'planned';
/** Server point grants, for display only. Backend checks and employee scope still apply. */
export function roleMatrixCell(point: MenuPoint, grants: readonly string[]): GrantState {
  if (point.backendStatus !== 'IMPLEMENTED') return 'unavailable';
  if (!grants.includes(point.pointCode)) return 'denied';
  if (point.pointType === 'MENU' && !point.uiReady) return 'planned';
  return 'granted';
}
