import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Page } from '@/features/users/api'
import { api } from '@/lib/api'

export type PoStatus = 'DRAFT' | 'APPROVED' | 'PARTLY_RECEIVED' | 'RECEIVED' | 'CANCELLED'
export const PO_STATUSES: PoStatus[] = ['DRAFT', 'APPROVED', 'PARTLY_RECEIVED', 'RECEIVED', 'CANCELLED']

export interface PoSummary {
  id: string
  number: string
  status: PoStatus
  supplierCode: string
  supplierName: string
  warehouseCode: string
  expectedDate: string | null
  /** Cents. */
  totalMinor: number
  lineCount: number
  createdAt: string
}

export interface PoLine {
  lineNo: number
  sku: string
  productName: string
  uom: string
  factor: number
  qty: number
  unitCostMinor: number
  totalMinor: number
  qtyReceived: number
}

export interface PurchaseOrder {
  id: string
  number: string
  status: PoStatus
  supplierCode: string
  supplierName: string
  /** Null once the warehouse is archived. */
  warehouseId: string | null
  warehouseCode: string
  expectedDate: string | null
  note: string | null
  totalMinor: number
  createdAt: string
  approvedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  lines: PoLine[]
  version: number
}

export interface PoInput {
  supplierCode: string
  warehouseId: string
  expectedDate: string | null
  note: string | null
  /** unitCostMinor null = the product's cost x unit factor. */
  lines: { sku: string; uom: string; qty: number; unitCostMinor: number | null }[]
  version?: number
}

export const PO_PAGE_SIZE = 25

export function usePurchaseOrders(status: PoStatus | '', q: string, page: number) {
  const params = new URLSearchParams({ page: String(page), size: String(PO_PAGE_SIZE) })
  if (status) params.set('status', status)
  if (q) params.set('q', q)
  return useQuery({
    queryKey: ['purchase-orders', status, q, page],
    queryFn: () => api<Page<PoSummary>>(`/purchase-orders?${params}`),
    placeholderData: keepPreviousData,
  })
}

export function usePurchaseOrder(id: string | undefined) {
  return useQuery({
    queryKey: ['purchase-order', id],
    queryFn: () => api<PurchaseOrder>(`/purchase-orders/${id}`),
    enabled: !!id,
  })
}

/** Save (create or edit draft), approve or cancel; every change refreshes the list and the order. */
export function usePoAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'save'; id?: string; body: PoInput } | { kind: 'approve'; id: string; version: number }
      | { kind: 'cancel'; id: string; version: number; reason: string }) => {
      if (a.kind === 'save') {
        return a.id ? api<PurchaseOrder>(`/purchase-orders/${a.id}`, { method: 'PUT', body: a.body })
          : api<PurchaseOrder>('/purchase-orders', { method: 'POST', body: a.body })
      }
      const body = a.kind === 'cancel' ? { version: a.version, reason: a.reason } : { version: a.version }
      return api<PurchaseOrder>(`/purchase-orders/${a.id}/${a.kind}`, { method: 'POST', body })
    },
    onSuccess: (po) => {
      qc.setQueryData(['purchase-order', po.id], po)
      void qc.invalidateQueries({ queryKey: ['purchase-orders'] })
    },
  })
}

/** Orders that can be received now (approved or partly received), for the Receive screen. */
export function useReceivableOrders(q: string) {
  const params = new URLSearchParams({ receivable: 'true', size: String(PO_PAGE_SIZE) })
  if (q) params.set('q', q)
  return useQuery({
    queryKey: ['purchase-orders', 'receivable', q],
    queryFn: () => api<Page<PoSummary>>(`/purchase-orders?${params}`),
    placeholderData: keepPreviousData,
  })
}

/** One order line with what is still expected; all quantities in the product's base unit. */
export interface LineProgress {
  lineNo: number
  sku: string
  productName: string
  baseUom: string
  ordered: number
  receivedBefore: number
  thisReceipt: number
  damagedThisReceipt: number
}

export interface Scan {
  id: string
  sku: string
  uom: string
  factor: number
  qty: number
  qtyBase: number
  damagedBase: number
  damageNote: string | null
  batchNo: string | null
  expiryDate: string | null
  createdAt: string
}

export interface Receipt {
  id: string
  number: string
  status: 'OPEN' | 'CLOSED'
  poId: string
  poNumber: string
  supplierName: string
  warehouseCode: string
  note: string | null
  createdAt: string
  closedAt: string | null
  lines: LineProgress[]
  scans: Scan[]
  version: number
}

export interface ScanInput {
  code: string
  qty: number
  uom?: string
  damagedQty?: number
  damageNote?: string
  batchNo?: string
  expiryDate?: string
  /** Same key when the same scan is sent again after a network error. */
  key: string
}

export function useReceipt(id: string | undefined) {
  return useQuery({ queryKey: ['receipt', id], queryFn: () => api<Receipt>(`/receipts/${id}`), enabled: !!id })
}

/** Open (or reuse) a receipt, scan, take back a scan, close. Each answer is the whole receipt. */
export function useReceiptAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'open'; poId: string } | { kind: 'scan'; id: string; scan: ScanInput }
      | { kind: 'remove'; id: string; lineId: string } | { kind: 'close'; id: string; version: number; note?: string }) => {
      switch (a.kind) {
        case 'open': return api<Receipt>('/receipts', { method: 'POST', body: { poId: a.poId } })
        case 'scan': {
          const { key, ...body } = a.scan
          return api<Receipt>(`/receipts/${a.id}/lines`, { method: 'POST', body, headers: { 'Idempotency-Key': key } })
        }
        case 'remove': return api<Receipt>(`/receipts/${a.id}/lines/${a.lineId}`, { method: 'DELETE' })
        case 'close': return api<Receipt>(`/receipts/${a.id}/close`, { method: 'POST', body: { version: a.version, note: a.note } })
      }
    },
    onSuccess: (r) => {
      qc.setQueryData(['receipt', r.id], r)
      void qc.invalidateQueries({ queryKey: ['purchase-orders'] })
      void qc.invalidateQueries({ queryKey: ['purchase-order', r.poId] })
    },
  })
}
