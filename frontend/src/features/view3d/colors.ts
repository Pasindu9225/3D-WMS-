import type { ZoneType } from '@/features/warehouses/api'
import { BLOCKED, PICK_FACE, type Scene } from './api'

export type ColorMode = 'real' | 'zone' | 'status' | 'expiry' | 'heat'

/** Real look (v2.1): natural cardboard tints, a little different per bin so stacks do not look copy-pasted. */
export const CARTON_COLORS = ['#d8a86c', '#c99456', '#e2b883', '#cf9d62'] as const
export const EMPTY_COLOR = '#cbd5e1'
export type ExpiryBand = 'EXPIRED' | 'D30' | 'D60' | 'D90' | 'LATER' | 'NONE'
export type BinStatus = 'EMPTY' | 'PARTLY' | 'FULL' | 'BLOCKED' | 'PICK_FACE'

export const ZONE_COLOR: Record<ZoneType, string> = {
  RECEIVING: '#3b82f6',
  STORAGE: '#64748b',
  PICKING: '#10b981',
  DISPATCH: '#f59e0b',
  QUARANTINE: '#ef4444',
  COLD: '#0ea5e9',
  FROZEN: '#6366f1',
}

/** Status colours: blocked stands out most; empty is quiet so filled bins catch the eye. */
export const STATUS_COLOR: Record<BinStatus, string> = {
  EMPTY: '#cbd5e1',
  PARTLY: '#facc15',
  FULL: '#16a34a',
  BLOCKED: '#dc2626',
  PICK_FACE: '#8b5cf6',
}

/** Expiry colours (v1.1): red when expired, warming from yellow to orange as the date nears, green when far off. */
export const EXPIRY_COLOR: Record<ExpiryBand, string> = {
  EXPIRED: '#dc2626',
  D30: '#f97316',
  D60: '#f59e0b',
  D90: '#facc15',
  LATER: '#16a34a',
  NONE: '#cbd5e1',
}

/** Pick heat (v1.2): step 0 = not picked, then 1 (cool blue) to 5 (red), by share of the busiest bin. */
export const HEAT_COLOR = ['#cbd5e1', '#3b82f6', '#06b6d4', '#facc15', '#f97316', '#dc2626'] as const

export function heatStep(picks: number, max: number): number {
  return picks <= 0 || max <= 0 ? 0 : Math.min(5, Math.ceil((5 * picks) / max))
}

/** Pick ranges of each heat step for the legend; steps that no count can reach (few picks) are left out. */
export function heatRanges(max: number): { step: number; from: number; to: number }[] {
  const out: { step: number; from: number; to: number }[] = []
  for (let k = 1; k <= 5 && max > 0; k++) {
    const from = Math.floor(((k - 1) * max) / 5) + 1
    const to = Math.floor((k * max) / 5)
    if (from <= to) out.push({ step: k, from, to })
  }
  return out
}

/** Band of a bin from the days until its earliest expiry (null = nothing dated in it). */
export function expiryBand(days: number | null | undefined): ExpiryBand {
  if (days === null || days === undefined) return 'NONE'
  if (days < 0) return 'EXPIRED'
  if (days <= 30) return 'D30'
  if (days <= 60) return 'D60'
  if (days <= 90) return 'D90'
  return 'LATER'
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
  if (mode === 'real') {
    return scene.bins.rack.map((_, i) => ((scene.fill?.[i] ?? 0) > 0 ? CARTON_COLORS[(i * 7) % CARTON_COLORS.length]! : EMPTY_COLOR))
  }
  if (mode === 'expiry') {
    return scene.bins.rack.map((_, i) => EXPIRY_COLOR[expiryBand(scene.expiryDays?.[i])])
  }
  if (mode === 'heat') {
    const max = Math.max(0, ...(scene.picks ?? []))
    return scene.bins.rack.map((_, i) => HEAT_COLOR[heatStep(scene.picks?.[i] ?? 0, max)]!)
  }
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
export function legend(scene: Scene, mode: ColorMode): { key: string; color: string; from?: number; to?: number }[] {
  if (mode === 'real') return [{ key: 'LOADED', color: CARTON_COLORS[0] }, { key: 'EMPTY', color: EMPTY_COLOR }]
  if (mode === 'heat') {
    const max = Math.max(0, ...(scene.picks ?? []))
    return [{ key: 'NONE', color: HEAT_COLOR[0] }, ...heatRanges(max).map((r) => ({ key: `H${r.step}`, color: HEAT_COLOR[r.step]!, from: r.from, to: r.to }))]
  }
  if (mode === 'expiry') return (Object.keys(EXPIRY_COLOR) as ExpiryBand[]).map((b) => ({ key: b, color: EXPIRY_COLOR[b] }))
  if (mode === 'status') return (Object.keys(STATUS_COLOR) as BinStatus[]).map((s) => ({ key: s, color: STATUS_COLOR[s] }))
  const types = [...new Set(scene.zones.map((z) => z.type))]
  return types.map((t) => ({ key: t, color: ZONE_COLOR[t] }))
}
