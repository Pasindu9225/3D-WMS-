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
