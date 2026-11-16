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
