import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Page } from '@/features/users/api'
import { api } from '@/lib/api'

export type SoStatus = 'DRAFT' | 'RELEASED' | 'PICKING' | 'PICKED' | 'PACKED' | 'SHIPPED' | 'CANCELLED'
export const SO_STATUSES: SoStatus[] = ['DRAFT', 'RELEASED', 'PICKING', 'PICKED', 'PACKED', 'SHIPPED', 'CANCELLED']

export interface SoSummary {
  id: string
  number: string
  status: SoStatus
  customerCode: string
  customerName: string
  customerRef: string | null
  warehouseCode: string
  requestedDate: string | null
  lineCount: number
  createdAt: string
  /** The client (3PL) whose goods the order is for (v2.1); null = the company's own. */
  ownerCode?: string | null
}

export interface SoLine {
  lineNo: number
  sku: string
  productName: string
  uom: string
  factor: number
  qty: number
}

export interface SalesOrder {
  id: string
  number: string
  status: SoStatus
  customerCode: string
  customerName: string
  customerRef: string | null
  /** Null once the warehouse is archived. */
  warehouseId: string | null
  warehouseCode: string
  shipTo: string | null
  requestedDate: string | null
  note: string | null
  createdAt: string
  cancelledAt: string | null
  releasedAt: string | null
  cancelReason: string | null
  lines: SoLine[]
  /** Stock set aside once released: where, which lot, how much (base units). */
  reserved: { lineNo: number; sku: string; binCode: string | null; batchNo: string | null; expiryDate: string | null; qty: number }[]
  /** The pick list once released (null for drafts). */
  pick: PickList | null
  deliveryNoteNo: string | null
  cartons: number | null
  packedAt: string | null
  shipped: { shipmentId: string; number: string; vehicleNo: string | null; driverName: string | null; at: string } | null
  version: number
}

export interface SoInput {
  customerCode: string
  customerRef: string | null
  warehouseId: string
  /** Null = the customer's address. */
  shipTo: string | null
  requestedDate: string | null
  note: string | null
  lines: { sku: string; uom: string; qty: number }[]
  version?: number
}

export const SO_PAGE_SIZE = 25

export function useSalesOrders(status: SoStatus | '', q: string, page: number) {
  const params = new URLSearchParams({ page: String(page), size: String(SO_PAGE_SIZE) })
  if (status) params.set('status', status)
  if (q) params.set('q', q)
  return useQuery({
    queryKey: ['sales-orders', status, q, page],
    queryFn: () => api<Page<SoSummary>>(`/sales-orders?${params}`),
    placeholderData: keepPreviousData,
  })
}

export function useSalesOrder(id: string | undefined) {
  return useQuery({
    queryKey: ['sales-order', id],
    queryFn: () => api<SalesOrder>(`/sales-orders/${id}`),
    enabled: !!id,
  })
}

/** Save (create or edit a draft), release (reserve stock) or cancel; every change refreshes the list and the order. */
export function useSoAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'save'; id?: string; body: SoInput } | { kind: 'release'; id: string; version: number }
      | { kind: 'cancel'; id: string; version: number; reason: string }) =>
      a.kind === 'release' ? api<SalesOrder>(`/sales-orders/${a.id}/release`, { method: 'POST', body: { version: a.version } })
      : a.kind === 'cancel'
        ? api<SalesOrder>(`/sales-orders/${a.id}/cancel`, { method: 'POST', body: { version: a.version, reason: a.reason } })
        : a.id ? api<SalesOrder>(`/sales-orders/${a.id}`, { method: 'PUT', body: a.body })
          : api<SalesOrder>('/sales-orders', { method: 'POST', body: a.body }),
    onSuccess: (so) => {
      qc.setQueryData(['sales-order', so.id], so)
      void qc.invalidateQueries({ queryKey: ['sales-orders'] })
      void qc.invalidateQueries({ queryKey: ['stock'] })
      void qc.invalidateQueries({ queryKey: ['stock-products'] })
    },
  })
}

export interface PickLineView {
  seq: number
  soLineNo: number
  sku: string
  productName: string
  baseUom: string
  binCode: string
  batchNo: string | null
  expiryDate: string | null
  qtyToPick: number
  qtyPicked: number
  status: 'OPEN' | 'PICKED' | 'SHORT'
  shortReason: string | null
}

export interface PickList {
  id: string
  number: string
  status: 'OPEN' | 'IN_PROGRESS' | 'DONE'
  orderId: string
  orderNumber: string
  customerName: string
  warehouseCode: string
  lines: PickLineView[]
  openLines: number
  priority: Priority
  assignee: Person | null
}

export interface PickSummary {
  id: string
  number: string
  status: PickList['status']
  orderNumber: string
  customerName: string
  warehouseCode: string
  lines: number
  openLines: number
  priority: Priority
  assignee: Person | null
  startedBy: Person | null
  createdAt: string
  /** The wave it is walked in (v1.2 batch picking) and its tote number, if any. */
  wave: string | null
  tote: number | null
}

/** Pick lists not finished yet, oldest first: the picker's to-do list. */
export function usePickLists(enabled = true) {
  return useQuery({
    queryKey: ['pick-lists'],
    queryFn: () => api<Page<PickSummary>>('/pick-lists?size=50'),
    enabled,
  })
}

export function usePickList(id: string | undefined) {
  return useQuery({ queryKey: ['pick-list', id], queryFn: () => api<PickList>(`/pick-lists/${id}`), enabled: !!id })
}

export type ShortReason = 'NOT_THERE' | 'DAMAGED'

/** Confirm (with an Idempotency-Key, so a phone retry moves nothing twice) or close a line short. */
export function usePickAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'confirm'; id: string; seq: number; key: string; binCode: string; code: string; qty: number }
      | { kind: 'short'; id: string; seq: number; reason: ShortReason }) =>
      a.kind === 'confirm'
        ? api<PickList>(`/pick-lists/${a.id}/lines/${a.seq}/confirm`, {
          method: 'POST', headers: { 'Idempotency-Key': a.key }, body: { binCode: a.binCode, code: a.code, qty: a.qty },
        })
        : api<PickList>(`/pick-lists/${a.id}/lines/${a.seq}/short`, { method: 'POST', body: { reason: a.reason } }),
    onSuccess: (p) => {
      qc.setQueryData(['pick-list', p.id], p)
      void qc.invalidateQueries({ queryKey: ['pick-lists'] })
      void qc.invalidateQueries({ queryKey: ['wave'] }) // a wave walk shows this list's lines too
      void qc.invalidateQueries({ queryKey: ['waves'] })
      void qc.invalidateQueries({ queryKey: ['sales-order', p.orderId] })
    },
  })
}

export interface PackItem {
  sku: string
  name: string
  baseUom: string
  /** Base units. */
  ordered: number
  picked: number
  /** Every code that may be scanned for this product and how many base units one scan counts. */
  codes: { code: string; uom: string; factor: number }[]
}

export interface PackView {
  id: string
  number: string
  status: SoStatus
  customerName: string
  customerRef: string | null
  warehouseCode: string
  items: PackItem[]
  version: number
}

export function useToPack(enabled = true) {
  return useQuery({ queryKey: ['to-pack'], queryFn: () => api<Page<SoSummary>>('/sales-orders/to-pack?size=50'), enabled })
}

export function usePackView(id: string | undefined) {
  return useQuery({ queryKey: ['pack', id], queryFn: () => api<PackView>(`/sales-orders/${id}/pack`), enabled: !!id })
}

export function useFinishPack() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; version: number; cartons: number; weightG: number | null; counts: { sku: string; qty: number }[] }) =>
      api<PackView>(`/sales-orders/${id}/pack`, { method: 'POST', body }),
    onSuccess: (p) => {
      qc.setQueryData(['pack', p.id], p)
      void qc.invalidateQueries({ queryKey: ['to-pack'] })
      void qc.invalidateQueries({ queryKey: ['sales-order', p.id] })
      void qc.invalidateQueries({ queryKey: ['sales-orders'] })
    },
  })
}

/** The two papers that travel with a packed order. */
export const packingSlipUrl = (id: string) => `/api/v1/sales-orders/${id}/packing-slip.pdf`
export const deliveryNoteUrl = (id: string) => `/api/v1/sales-orders/${id}/delivery-note.pdf`

export interface Shipment {
  id: string
  number: string
  warehouseCode: string | null
  vehicleNo: string | null
  driverName: string | null
  note: string | null
  createdAt: string
  orders: { id: string; number: string; customerName: string; deliveryNoteNo: string | null; cartons: number | null }[]
}

export function useToDispatch(enabled = true) {
  return useQuery({ queryKey: ['to-dispatch'], queryFn: () => api<Page<SoSummary>>('/sales-orders/to-dispatch?size=100'), enabled })
}

export function useShipments() {
  return useQuery({ queryKey: ['shipments'], queryFn: () => api<Page<Shipment>>('/shipments?size=10') })
}

export function useDispatch() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { warehouseId: string; orderIds: string[]; vehicleNo: string | null; driverName: string | null; note: string | null }) =>
      api<Shipment>('/shipments', { method: 'POST', body }),
    onSuccess: () => {
      for (const key of ['to-dispatch', 'shipments', 'sales-orders', 'sales-order', 'stock', 'stock-products']) void qc.invalidateQueries({ queryKey: [key] })
    },
  })
}

export type Priority = 'URGENT' | 'HIGH' | 'NORMAL'
export const PRIORITIES: Priority[] = ['URGENT', 'HIGH', 'NORMAL']

export interface Person {
  id: string
  name: string
}

export interface Board {
  toRelease: number
  toPick: number
  picking: number
  pickedToday: number
  toPack: number
  toDispatch: number
  inReceiving: number
  /** Refill tasks waiting (v1.2). */
  toRefill: number
  lists: PickSummary[]
}

/** The supervisor board; refreshed every 10 seconds while open. */
export function useBoard(warehouseId: string | undefined) {
  return useQuery({
    queryKey: ['task-board', warehouseId],
    queryFn: () => api<Board>(`/task-board?warehouse=${warehouseId}`),
    enabled: !!warehouseId,
    refetchInterval: 10_000,
  })
}

export function usePickers(warehouseId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['pickers', warehouseId],
    queryFn: () => api<Person[]>(`/task-board/pickers?warehouse=${warehouseId}`),
    enabled: enabled && !!warehouseId,
  })
}

/** Give a list to someone (null = anyone) or change its priority. */
export function useBoardAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'assign'; id: string; userId: string | null } | { kind: 'priority'; id: string; priority: Priority }) =>
      a.kind === 'assign'
        ? api<PickList>(`/pick-lists/${a.id}/assign`, { method: 'POST', body: { userId: a.userId } })
        : api<PickList>(`/pick-lists/${a.id}/priority`, { method: 'POST', body: { priority: a.priority } }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['task-board'] })
      void qc.invalidateQueries({ queryKey: ['pick-lists'] })
    },
  })
}

export interface WaveWalkLine {
  listId: string
  seq: number
  tote: number
  orderNumber: string
  sku: string
  productName: string
  baseUom: string
  binCode: string
  batchNo: string | null
  expiryDate: string | null
  qtyToPick: number
  qtyPicked: number
  status: 'OPEN' | 'PICKED' | 'SHORT'
}

export interface Wave {
  id: string
  number: string
  status: 'OPEN' | 'IN_PROGRESS' | 'DONE'
  warehouseCode: string
  cutOff: string | null
  version: number
  assignee: Person | null
  orders: { listId: string; listNumber: string; tote: number; orderNumber: string; customerName: string; status: PickList['status']; openLines: number }[]
  walk: WaveWalkLine[]
  openLines: number
  /** Straight-line metres for the combined walk, and for picking the same orders one by one. */
  walkMetres: number
  singleWalksMetres: number
}

export interface WaveSummary {
  id: string
  number: string
  status: Wave['status']
  warehouseCode: string
  orders: number
  openLines: number
  urgent: boolean
  assignee: Person | null
}

/** Open waves (several orders in one walk), for pickers and the board. */
export function useWaves(enabled = true) {
  return useQuery({ queryKey: ['waves'], queryFn: () => api<Page<WaveSummary>>('/pick-waves?size=50'), enabled, refetchInterval: 10_000 })
}

export function useWave(id: string | undefined) {
  return useQuery({ queryKey: ['wave', id], queryFn: () => api<Wave>(`/pick-waves/${id}`), enabled: !!id })
}

/** Supervisor: make a wave from chosen lists or by cut-off date, or take one apart. */
export function useWaveAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'lists'; warehouseId: string; listIds: string[]; pickerId?: string }
      | { kind: 'cutOff'; warehouseId: string; cutOff: string; maxOrders: number; pickerId?: string }
      | { kind: 'dissolve'; id: string }): Promise<Wave | void> =>
      a.kind === 'lists'
        ? api<Wave>('/pick-waves', { method: 'POST', body: { warehouseId: a.warehouseId, listIds: a.listIds, pickerId: a.pickerId || undefined } })
        : a.kind === 'cutOff'
          ? api<Wave>('/pick-waves/by-cut-off', { method: 'POST', body: { warehouseId: a.warehouseId, cutOff: a.cutOff, maxOrders: a.maxOrders, pickerId: a.pickerId || undefined } })
          : api<void>(`/pick-waves/${a.id}`, { method: 'DELETE' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['waves'] })
      void qc.invalidateQueries({ queryKey: ['task-board'] })
      void qc.invalidateQueries({ queryKey: ['pick-lists'] })
    },
  })
}
