import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'

/** One stop of a pick walk: a rack position (mm, plan coordinates) and the bins taken there. */
export interface RouteStop {
  step: number
  x: number
  y: number
  binCodes: string[]
}

/** A pick walk (v1.2): from the start point through the stops in walking order and back. */
export interface PickRoute {
  warehouseId: string
  startX: number
  startY: number
  stops: RouteStop[]
  metres: number
}

/** "list:<id>" or "wave:<id>" from the 3D page's ?route=, or null when it is not one of those. */
export function parseRoute(param: string | null): { kind: 'list' | 'wave'; id: string } | null {
  const m = param?.match(/^(list|wave):([0-9A-Za-z]{1,26})$/)
  return m ? { kind: m[1] as 'list' | 'wave', id: m[2]! } : null
}

export function useRoute(param: string | null) {
  const r = parseRoute(param)
  return useQuery({
    queryKey: ['route', param],
    queryFn: () => api<PickRoute>(r!.kind === 'list' ? `/pick-lists/${r!.id}/route` : `/pick-waves/${r!.id}/route`),
    enabled: !!r,
  })
}

/** The points of the walk in order, start and end included: what the map and the 3D floor draw. */
export function routePoints(route: PickRoute): { x: number; y: number }[] {
  const start = { x: route.startX, y: route.startY }
  return [start, ...route.stops.map((s) => ({ x: s.x, y: s.y })), start]
}
