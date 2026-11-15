import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Page } from '@/features/users/api'
import { api } from '@/lib/api'

/** Goods in the receiving area waiting to be put away; quantities in the product's base unit. */
export interface Waiting {
  productId: string
  sku: string
  name: string
  baseUom: string
  batchNo: string | null
  expiryDate: string | null
  qty: number
  receivedAt: string
}

export interface Suggestion {
  binId: string
  code: string
  zoneType: string
  level: number
  why: 'SAME_PRODUCT' | 'EMPTY_STORAGE'
  distanceM: number
}

export interface PutawayDone {
  binCode: string
  qty: number
  leftInReceiving: number
  alreadyDone: boolean
}

export function useWaiting(warehouseId: string | undefined) {
  return useQuery({
    queryKey: ['putaway', warehouseId],
    queryFn: () => api<Page<Waiting>>(`/putaway?warehouse=${warehouseId}&size=100`),
    enabled: !!warehouseId,
  })
}

export function useSuggestions(warehouseId: string, item: Waiting, qty: number | null) {
  return useQuery({
    queryKey: ['putaway-suggest', warehouseId, item.productId, item.batchNo, qty],
    queryFn: () => api<Suggestion[]>('/putaway/suggestions', {
      method: 'POST', body: { warehouseId, productId: item.productId, batchNo: item.batchNo, qty },
    }),
    enabled: qty !== null && qty > 0,
    staleTime: 0,
  })
}

/** Puts goods into the scanned bin. key: one per putaway, repeated on a retry so it counts once. */
export function usePutaway() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { warehouseId: string; item: Waiting; qty: number; binCode: string; key: string }) =>
      api<PutawayDone>('/putaway', {
        method: 'POST',
        headers: { 'Idempotency-Key': a.key },
        body: {
          warehouseId: a.warehouseId, productId: a.item.productId, batchNo: a.item.batchNo, expiryDate: a.item.expiryDate,
          qty: a.qty, binCode: a.binCode,
        },
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['putaway'] })
      void qc.invalidateQueries({ queryKey: ['putaway-suggest'] })
    },
  })
}

export interface Moved {
  fromBin: string | null
  toBin: string
  sku: string
  qty: number
  batchNo: string | null
  expiryDate: string | null
  alreadyDone: boolean
}

export interface TransferInput {
  warehouseId: string
  productId: string
  qty: number
  fromBin?: string
  toBin: string
  batchNo?: string
  expiryDate?: string
}

/** Bin-to-bin move. key: one per move, repeated on a retry so it counts once. */
export function useTransfer() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ key, ...body }: TransferInput & { key: string }) =>
      api<Moved>('/transfers', { method: 'POST', body, headers: { 'Idempotency-Key': key } }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['putaway'] }),
  })
}

export type AdjustmentStatus = 'PENDING' | 'APPLIED' | 'REJECTED'
export type AdjustmentReason = 'DAMAGED' | 'LOST' | 'FOUND' | 'EXPIRED' | 'COUNT_CORRECTION' | 'OTHER'
export const REASONS: AdjustmentReason[] = ['DAMAGED', 'LOST', 'FOUND', 'EXPIRED', 'COUNT_CORRECTION', 'OTHER']

export interface Adjustment {
  id: string
  number: string
  status: AdjustmentStatus
  warehouseCode: string | null
  binCode: string | null
  sku: string
  productName: string
  baseUom: string
  batchNo: string | null
  expiryDate: string | null
  direction: 'IN' | 'OUT'
  qty: number
  reason: AdjustmentReason
  note: string | null
  /** Cents; null when the product has no cost. */
  valueMinor: number | null
  requestedBy: string | null
  requestedAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
  version: number
}

export interface AdjustmentInput {
  warehouseId: string
  binCode?: string
  productId: string
  direction: 'IN' | 'OUT'
  qty: number
  reason: AdjustmentReason
  note?: string
  batchNo?: string
  expiryDate?: string
}

export function useAdjustments(status: AdjustmentStatus | '', page: number) {
  const params = new URLSearchParams({ page: String(page), size: '25' })
  if (status) params.set('status', status)
  return useQuery({
    queryKey: ['adjustments', status, page],
    queryFn: () => api<Page<Adjustment>>(`/adjustments?${params}`),
  })
}

/** Ask for an adjustment, approve or reject one. Every answer is the adjustment as it now stands. */
export function useAdjustmentAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'request'; key: string; body: AdjustmentInput }
      | { kind: 'approve'; id: string; version: number } | { kind: 'reject'; id: string; version: number; note: string }) => {
      if (a.kind === 'request') return api<Adjustment>('/adjustments', { method: 'POST', body: a.body, headers: { 'Idempotency-Key': a.key } })
      const body = a.kind === 'reject' ? { version: a.version, note: a.note } : { version: a.version }
      return api<Adjustment>(`/adjustments/${a.id}/${a.kind}`, { method: 'POST', body })
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['adjustments'] })
      void qc.invalidateQueries({ queryKey: ['putaway'] })
    },
  })
}
