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
  }[]
  /** Parallel arrays: bin i is in racks[rack[i]], level level[i], slot pos[i] from the left end. */
  bins: { ids: string[]; codes: string[]; rack: number[]; level: number[]; pos: number[] }
  /** One digit per bin: 1 = blocked, 2 = pick face (added together). */
  flags: string
  /** How full each bin is, 0 to 100 (1 at least when it holds anything). Missing before stock existed. */
  fill?: number[]
}

export const BLOCKED = 1
export const PICK_FACE = 2

export function useScene(warehouseId: string) {
  return useQuery({
    queryKey: ['scene', warehouseId],
    queryFn: () => api<Scene>(`/warehouses/${warehouseId}/scene`),
    staleTime: 60_000,
    retry: false,
  })
}
