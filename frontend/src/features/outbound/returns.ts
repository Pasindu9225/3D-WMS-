import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Page } from '@/features/users/api'
import { api } from '@/lib/api'

export type ReturnReason = 'DAMAGED' | 'WRONG_ITEM' | 'NOT_WANTED' | 'EXPIRED' | 'OTHER'
export type ReturnOutcome = 'RESTOCK' | 'QUARANTINE' | 'SCRAP'
export type ReturnStatus = 'OPEN' | 'CLOSED' | 'CANCELLED'
export const REASONS: ReturnReason[] = ['NOT_WANTED', 'DAMAGED', 'WRONG_ITEM', 'EXPIRED', 'OTHER']

/** What can still come back from a shipped order, per product (base units). */
export interface Returnable {
  sku: string
  productName: string
  baseUom: string
  shipped: number
  returned: number
  left: number
  /** Unit -> base units, e.g. { EA: 1, CASE: 12 }. */
  units: Record<string, number>
}

export interface ReturnLineView {
  seq: number
  sku: string
  productName: string
  baseUom: string
  qty: number
  reason: ReturnReason
  outcome: ReturnOutcome | null
  binCode: string | null
  batchNo: string | null
  expiry: string | null
  inspectedBy: string | null
  inspectedAt: string | null
}

export interface ReturnDetail {
  id: string
  number: string
  orderId: string
  orderNumber: string
  customerName: string
  warehouseCode: string
  status: ReturnStatus
  note: string | null
  cancelReason: string | null
  version: number
  createdAt: string
  closedAt: string | null
  open: number
  lines: ReturnLineView[]
}

export interface ReturnSummary {
  id: string
  number: string
  orderNumber: string
  customerName: string
  warehouseCode: string
  status: ReturnStatus
  lines: number
  open: number
  createdAt: string
}

export function useReturns(done = false, enabled = true) {
  return useQuery({
    queryKey: ['returns', done],
    queryFn: () => api<Page<ReturnSummary>>(`/returns?size=50${done ? '&done=true' : ''}`),
    enabled,
  })
}

export function useReturn(id: string | undefined) {
  return useQuery({ queryKey: ['return', id], queryFn: () => api<ReturnDetail>(`/returns/${id}`), enabled: !!id })
}

export function useReturnable(orderId: string | undefined) {
  return useQuery({ queryKey: ['returnable', orderId], queryFn: () => api<Returnable[]>(`/sales-orders/${orderId}/returnable`), enabled: !!orderId })
}

export function useCreateReturn() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (b: { orderId: string; note?: string; lines: { sku: string; uom: string; qty: number; reason: ReturnReason }[] }) =>
      api<ReturnDetail>('/returns', { method: 'POST', body: b }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['returns'] }),
  })
}

export function useReturnAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'inspect'; id: string; seq: number; outcome: ReturnOutcome; binCode?: string; batchNo?: string; expiryDate?: string }
      | { kind: 'cancel'; id: string; version: number; reason: string }) =>
      a.kind === 'inspect'
        ? api<ReturnDetail>(`/returns/${a.id}/lines/${a.seq}/inspect`, {
          method: 'POST', body: { outcome: a.outcome, binCode: a.binCode || undefined, batchNo: a.batchNo || undefined, expiryDate: a.expiryDate || undefined },
        })
        : api<ReturnDetail>(`/returns/${a.id}/cancel`, { method: 'POST', body: { version: a.version, reason: a.reason } }),
    onSuccess: (r) => {
      qc.setQueryData(['return', r.id], r)
      void qc.invalidateQueries({ queryKey: ['returns'] })
      void qc.invalidateQueries({ queryKey: ['stock'] })
    },
  })
}
