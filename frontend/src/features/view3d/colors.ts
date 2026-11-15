import type { ZoneType } from '@/features/warehouses/api'
import { BLOCKED, PICK_FACE, type Scene } from './api'

export type ColorMode = 'zone' | 'status'
export type BinStatus = 'EMPTY' | 'PARTLY' | 'FULL' | 'BLOCKED' | 'PICK_FACE'

export const ZONE_COLOR: Record<ZoneType, string> = {
  RECEIVING: '#3b82f6',
  STORAGE: '#64748b',
  PICKING: '#10b981',
  DISPATCH: '#f59e0b',
  QUARANTINE: '#ef4444',
  COLD: '#0ea5e9',
}

/** Status colours: blocked stands out most; empty is quiet so filled bins catch the eye. */
export const STATUS_COLOR: Record<BinStatus, string> = {
  EMPTY: '#cbd5e1',
  PARTLY: '#facc15',
  FULL: '#16a34a',
  BLOCKED: '#dc2626',
  PICK_FACE: '#8b5cf6',
}

/**
 * One bin's status from its flags and how full it is (fill 0..1, from stock). Blocked wins over
 * everything; an empty pick face shows as a pick face so pickers can see where picking happens.
 */
export function statusOf(flag: number, fill?: number): BinStatus {
  if (flag & BLOCKED) return 'BLOCKED'
  if (fill !== undefined && fill >= 0.999) return 'FULL'
  if (fill !== undefined && fill > 0) return 'PARTLY'
  return flag & PICK_FACE ? 'PICK_FACE' : 'EMPTY'
}

/** Colour of every bin, in scene order. flags is the live status string (scene.flags or /bin-status). */
export function binColors(scene: Scene, mode: ColorMode, flags: string): string[] {
  if (mode === 'status') {
    return scene.bins.rack.map((_, i) => STATUS_COLOR[statusOf(Number(flags[i] ?? 0), (scene.fill?.[i] ?? 0) / 100)])
  }
  const zoneType = new Map(scene.zones.map((z) => [z.code, z.type]))
  return scene.bins.rack.map((r) => {
    const type = zoneType.get(scene.racks[r]!.zone)
    return type ? ZONE_COLOR[type] : '#94a3b8'
  })
}

/** Legend rows for the current mode: only what is on screen (zone types present, every status). */
export function legend(scene: Scene, mode: ColorMode): { key: string; color: string }[] {
  if (mode === 'status') return (Object.keys(STATUS_COLOR) as BinStatus[]).map((s) => ({ key: s, color: STATUS_COLOR[s] }))
  const types = [...new Set(scene.zones.map((z) => z.type))]
  return types.map((t) => ({ key: t, color: ZONE_COLOR[t] }))
}
