import { useQuery } from '@tanstack/react-query'
import type { Opening, ZoneType } from '@/features/warehouses/api'
import { api } from '@/lib/api'

/** GET /warehouses/{id}/scene: the published layout as compact arrays (all lengths in mm). */
export interface Scene {
  publishedAt: string
  /** x0, y0, x1, y1 … of the floor outline. */
  floor: number[]
  openings: Opening[]
  zones: { code: string; type: ZoneType; name: string; polygon: number[] }[]
  racks: {
    code: string
    zone: string
    x: number
    y: number
    rotation: number
    widthMm: number
    depthMm: number
    heightMm: number
    binsPerLevel: number
    /** Height from the floor of each level, bottom first. */
    levels: number[]
    /** 3D look from the rack type (v2.1): pallet racking or shelving, and its colours. Missing in older data. */
    style?: 'PALLET' | 'SHELF' | 'FLOOR'
    uprightColor?: string
    beamColor?: string
  }[]
  /** Parallel arrays: bin i is in racks[rack[i]], level level[i], slot pos[i] from the left end. */
  bins: { ids: string[]; codes: string[]; rack: number[]; level: number[]; pos: number[] }
  /** One digit per bin: 1 = blocked, 2 = pick face (added together). */
  flags: string
  /** How full each bin is, 0 to 100 (1 at least when it holds anything). Missing before stock existed. */
  fill?: number[]
  /** Days until the earliest expiry in each bin (negative = expired), null when nothing dated is in it. */
  expiryDays?: (number | null)[]
  /** Lines picked from each bin in the last 30 days (v1.2 heat map). */
  picks?: number[]
  /** Real cartons per bin (v2.1): [length, width, height mm, count] of its main product, null without a carton size. */
  cartons?: (number[] | null)[]
}

export const BLOCKED = 1
export const PICK_FACE = 2

export function useScene(warehouseId: string) {
  return useQuery({
    queryKey: ['scene', warehouseId],
    queryFn: () => api<Scene>(`/warehouses/${warehouseId}/scene`),
    // v2.1: live changes arrive over the socket and a new publish reloads it, so no need to ask often; when it is asked,
    // the browser sends its saved copy's ETag and an unchanged layout costs a 304 with no body.
    staleTime: 10 * 60_000,
    refetchOnWindowFocus: false,
    retry: false,
  })
}
