import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Page } from '@/features/users/api'
import { api } from '@/lib/api'

/** Keep between min and max of a product in a pick-face bin (base units). */
export interface RefillRule {
  id: string
  binId: string | null
  binCode: string
  productId: string | null
  sku: string
  productName: string
  baseUom: string
  minQty: number
  maxQty: number
  active: boolean
  onHand: number
  /** Number of the open refill task, if one is waiting. */
  openTask: string | null
  version: number
}

export interface RefillTask {
  id: string
  number: string
  status: 'OPEN' | 'DONE' | 'CANCELLED'
  warehouseCode: string
  binCode: string
  sku: string
  productName: string
  baseUom: string
  qty: number
  movedQty: number | null
  /** Where to take it from (oldest expiry first, a storage bin), or null when no free stock is left. */
  from: { binCode: string; batchNo: string | null; expiryDate: string | null; free: number } | null
  createdAt: string
}

export function useRefillRules(warehouseId: string | undefined) {
  return useQuery({ queryKey: ['refill-rules', warehouseId], queryFn: () => api<RefillRule[]>(`/refill-rules?warehouse=${warehouseId}`), enabled: !!warehouseId })
}

export function useRefillTasks(enabled = true) {
  return useQuery({ queryKey: ['refill-tasks'], queryFn: () => api<Page<RefillTask>>('/refill-tasks?size=50'), enabled, refetchInterval: 15_000 })
}

export function useRefillTask(id: string | undefined) {
  return useQuery({ queryKey: ['refill-task', id], queryFn: () => api<RefillTask>(`/refill-tasks/${id}`), enabled: !!id })
}

export function useRuleAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'create'; warehouseId: string; binCode: string; sku: string; minQty: number; maxQty: number }
      | { kind: 'update'; id: string; version: number; minQty: number; maxQty: number; active: boolean }
      | { kind: 'check'; warehouseId: string }): Promise<unknown> =>
      a.kind === 'create'
        ? api<RefillRule>('/refill-rules', { method: 'POST', body: { warehouseId: a.warehouseId, binCode: a.binCode, sku: a.sku, minQty: a.minQty, maxQty: a.maxQty } })
        : a.kind === 'update'
          ? api<RefillRule>(`/refill-rules/${a.id}`, { method: 'PUT', body: { version: a.version, minQty: a.minQty, maxQty: a.maxQty, active: a.active } })
          : api<{ made: number }>('/refill-rules/check', { method: 'POST', body: { warehouseId: a.warehouseId } }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['refill-rules'] })
      void qc.invalidateQueries({ queryKey: ['refill-tasks'] })
    },
  })
}

export function useRefillConfirm() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { id: string; fromBinCode: string; toBinCode: string; code: string; qty: number }) =>
      api<RefillTask>(`/refill-tasks/${a.id}/confirm`, { method: 'POST', body: { fromBinCode: a.fromBinCode, toBinCode: a.toBinCode, code: a.code, qty: a.qty } }),
    onSuccess: (t) => {
      qc.setQueryData(['refill-task', t.id], t)
      void qc.invalidateQueries({ queryKey: ['refill-tasks'] })
      void qc.invalidateQueries({ queryKey: ['refill-rules'] })
    },
  })
}
