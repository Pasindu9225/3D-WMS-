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
