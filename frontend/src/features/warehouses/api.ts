import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Issue } from '@/lib/api'
import type { Page } from '@/features/users/api'

export interface Warehouse {
  id: string
  code: string
  name: string
  layoutStatus: 'DRAFT' | 'PUBLISHED'
  widthMm: number
  lengthMm: number
  wallHeightMm: number
  createdAt: string
}

/** Layout document, exactly as the server stores it. All lengths in millimetres. */
export interface Point {
  x: number
  y: number
}

export interface Opening {
  kind: 'DOOR' | 'DOCK'
  label: string
  x: number
  y: number
  widthMm: number
}

export type ZoneType = 'RECEIVING' | 'STORAGE' | 'PICKING' | 'DISPATCH' | 'QUARANTINE' | 'COLD'

export interface Zone {
  code: string
  type: ZoneType
  name: string
  polygon: Point[]
}

export interface Rack {
  code: string
  templateId: string
  zone: string
  x: number
  y: number
  rotation: number
}

export interface LayoutDocument {
  floor: Point[]
  openings: Opening[]
  zones: Zone[]
  racks: Rack[]
  settings: { minAisleMm: number }
}

export interface RackType {
  id: string
  code: string
  name: string
  widthMm: number
  depthMm: number
  heightMm: number
  levels: number
  binsPerLevel: number
  maxWeightPerLevelG: number
}

export interface LayoutView {
  status: 'NEW' | 'DRAFT' | 'PUBLISHED'
  revision: number
  layout: LayoutDocument
  templates: Record<string, RackType>
  warnings: Issue[]
  updatedAt: string
}

export function useWarehouses() {
  return useQuery({ queryKey: ['warehouses'], queryFn: () => api<Page<Warehouse>>('/warehouses?size=100') })
}

export function useWarehouse(id: string) {
  return useQuery({ queryKey: ['warehouse', id], queryFn: () => api<Warehouse>(`/warehouses/${id}`) })
}

export function useCreateWarehouse() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { code: string; name: string; widthM: number; lengthM: number; wallHeightM: number }) =>
      api<Warehouse>('/warehouses', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['warehouses'] }),
  })
}

export function useArchiveWarehouse() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/warehouses/${id}/archive`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['warehouses'] }),
  })
}

export function useLayout(warehouseId: string) {
  return useQuery({
    queryKey: ['layout', warehouseId],
    queryFn: () => api<LayoutView>(`/warehouses/${warehouseId}/layout`),
    staleTime: Infinity, // the editor owns the copy while open; reload is explicit
    refetchOnWindowFocus: false,
  })
}

export function useSaveLayout(warehouseId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { baseRevision: number; layout: LayoutDocument }) =>
      api<LayoutView>(`/warehouses/${warehouseId}/layout`, { method: 'PUT', body }),
    onSuccess: (view) => qc.setQueryData(['layout', warehouseId], view),
  })
}

/** The company's rack type library (max 100 types per page; enough for one picker). */
export function useRackTypes() {
  return useQuery({ queryKey: ['rack-types'], queryFn: () => api<Page<RackType>>('/rack-templates?size=100') })
}
