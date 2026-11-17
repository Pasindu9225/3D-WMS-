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
  /** Clockwise degrees, 0-359. */
  rotation: number
  /** This rack resized; absent = its rack type size. */
  widthMm?: number | null
  depthMm?: number | null
  heightMm?: number | null
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
  /** 3D look (v2.1): pallet racking or shelving, and the colours of uprights and beams (#rrggbb). */
  style?: 'PALLET' | 'SHELF'
  uprightColor?: string
  beamColor?: string
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

export type RackTypeInput = Omit<RackType, 'id' | 'code'> & { code?: string }

export function useSaveRackType() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: RackTypeInput & { id?: string }) =>
      id ? api<RackType>(`/rack-templates/${id}`, { method: 'PUT', body }) : api<RackType>('/rack-templates', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['rack-types'] }),
  })
}

export function useArchiveRackType() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/rack-templates/${id}/archive`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['rack-types'] }),
  })
}

export interface PublishResult {
  zones: number
  racks: number
  levels: number
  bins: number
  binsAdded: number
  binsRemoved: number
  binsRenamed: number
}

export function usePublishLayout(warehouseId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (baseRevision: number) =>
      api<PublishResult>(`/warehouses/${warehouseId}/layout/publish`, { method: 'POST', body: { baseRevision } }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['layout', warehouseId] })
      void qc.invalidateQueries({ queryKey: ['warehouses'] })
      void qc.invalidateQueries({ queryKey: ['warehouse', warehouseId] })
      void qc.invalidateQueries({ queryKey: ['bins', warehouseId] })
    },
  })
}

export interface Bin {
  id: string
  code: string
  maxWeightG: number
  maxVolumeCm3: number
  customCapacity: boolean
  pickFace: boolean
  blocked: boolean
}

export const BIN_PAGE_SIZE = 50

export function useBins(warehouseId: string, code: string, page: number) {
  const params = new URLSearchParams({ page: String(page), size: String(BIN_PAGE_SIZE) })
  if (code) params.set('code', code)
  return useQuery({
    queryKey: ['bins', warehouseId, code, page],
    queryFn: () => api<Page<Bin>>(`/warehouses/${warehouseId}/bins?${params}`),
    placeholderData: (prev) => prev,
  })
}

export function useEditBin(warehouseId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; maxWeightG?: number; maxVolumeCm3?: number; pickFace?: boolean; blocked?: boolean }) =>
      api<Bin>(`/bins/${id}`, { method: 'PATCH', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bins', warehouseId] }),
  })
}

/** Label PDF link: a plain same-origin GET, so the session cookie goes with it and the browser downloads it. */
export function labelsUrl(warehouseId: string, code: string, format: 'sheet' | 'single') {
  const params = new URLSearchParams({ format })
  if (code) params.set('code', code)
  return `/api/v1/warehouses/${warehouseId}/labels.pdf?${params}`
}
