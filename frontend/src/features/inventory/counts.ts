import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { Page } from '@/features/users/api'

export type CountScope = 'ALL' | 'ZONE' | 'ABC'
export type CountStatus = 'OPEN' | 'REVIEW' | 'APPROVED' | 'CANCELLED'

export interface CountSummary {
  id: string
  number: string
  warehouseCode: string
  scope: CountScope
  scopeValue: string | null
  status: CountStatus
  lines: number
  counted: number
  createdAt: string
}

/** What a counter sees: places to count, never the expected quantity. */
export interface CounterLine {
  seq: number
  binCode: string
  sku: string
  productName: string
  baseUom: string
  batchNo: string | null
  expiry: string | null
  added: boolean
  status: 'OPEN' | 'COUNTED'
  counted: number | null
}

export interface CounterView {
  id: string
  number: string
  warehouseCode: string
  status: CountStatus
  lines: number
  open: number
  items: CounterLine[]
}

export interface ReviewLine extends CounterLine {
  expected: number | null
  difference: number | null
  /** Difference x cost, in cents; null when the product has no cost or the line is not counted. */
  valueMinor: number | null
  countedBy: string | null
  countedAt: string | null
}

export interface ReviewView {
  id: string
  number: string
  warehouseCode: string
  scope: CountScope
  scopeValue: string | null
  status: CountStatus
  createdAt: string
  approvedAt: string | null
  cancelReason: string | null
  version: number
  differences: number
  valueMinor: number
  lines: ReviewLine[]
}

export function useCounts(done = false, enabled = true) {
  return useQuery({
    queryKey: ['counts', done],
    queryFn: () => api<Page<CountSummary>>(`/count-plans?size=50${done ? '&done=true' : ''}`),
    enabled,
  })
}

export function useCounterView(id: string | undefined) {
  return useQuery({ queryKey: ['count', id, 'counter'], queryFn: () => api<CounterView>(`/count-plans/${id}/count`), enabled: !!id })
}

export function useReview(id: string | undefined) {
  return useQuery({ queryKey: ['count', id, 'review'], queryFn: () => api<ReviewView>(`/count-plans/${id}`), enabled: !!id })
}

export function useCreateCount() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (b: { warehouseId: string; scope: CountScope; scopeValue?: string }) =>
      api<ReviewView>('/count-plans', { method: 'POST', body: b }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['counts'] }),
  })
}

/** Count a line, or report goods that are not on the list. */
export function useCountAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'count'; id: string; seq: number; binCode: string; code: string; qty: number }
      | { kind: 'found'; id: string; binCode: string; code: string; qty: number; batchNo?: string; expiryDate?: string }) =>
      a.kind === 'count'
        ? api<CounterView>(`/count-plans/${a.id}/lines/${a.seq}/count`, { method: 'POST', body: { binCode: a.binCode, code: a.code, qty: a.qty } })
        : api<CounterView>(`/count-plans/${a.id}/found`, {
          method: 'POST', body: { binCode: a.binCode, code: a.code, qty: a.qty, batchNo: a.batchNo || undefined, expiryDate: a.expiryDate || undefined },
        }),
    onSuccess: (v) => {
      qc.setQueryData(['count', v.id, 'counter'], v)
      void qc.invalidateQueries({ queryKey: ['counts'] })
    },
  })
}

/** Manager: recount lines, approve the differences, or cancel. */
export function useReviewAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'recount'; id: string; version: number; lines: number[] }
      | { kind: 'approve'; id: string; version: number }
      | { kind: 'cancel'; id: string; version: number; reason: string }) =>
      api<ReviewView>(`/count-plans/${a.id}/${a.kind}`, {
        method: 'POST',
        body: a.kind === 'recount' ? { version: a.version, lines: a.lines } : a.kind === 'cancel' ? { version: a.version, reason: a.reason } : { version: a.version },
      }),
    onSuccess: (v) => {
      qc.setQueryData(['count', v.id, 'review'], v)
      void qc.invalidateQueries({ queryKey: ['counts'] })
      void qc.invalidateQueries({ queryKey: ['stock'] })
    },
  })
}
