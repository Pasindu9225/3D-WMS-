import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

/** Where goods go: one product, one category or every product -> a fixed bin or a zone (v1.2). */
export interface PutawayRule {
  id: string
  priority: number
  appliesTo: 'PRODUCT' | 'CATEGORY' | 'CLIENT' | 'ALL'
  /** v2.1 (3PL): the client whose goods the rule is for. */
  clientCode?: string | null
  sku: string | null
  productName: string | null
  category: string | null
  binCode: string | null
  zoneCode: string | null
  onlyZone: boolean
  active: boolean
  version: number
}

export type NewPutawayRule = { warehouseId: string; priority: number; sku?: string; category?: string; clientCode?: string; binCode?: string; zoneCode?: string; onlyZone: boolean }

export function usePutawayRules(warehouseId: string | undefined) {
  return useQuery({ queryKey: ['putaway-rules', warehouseId], queryFn: () => api<PutawayRule[]>(`/putaway-rules?warehouse=${warehouseId}`), enabled: !!warehouseId })
}

export function usePutawayRuleAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'create'; rule: NewPutawayRule } | { kind: 'update'; rule: PutawayRule; priority: number; onlyZone: boolean; active: boolean }
      | { kind: 'delete'; id: string }): Promise<unknown> =>
      a.kind === 'create'
        ? api<PutawayRule>('/putaway-rules', { method: 'POST', body: a.rule })
        : a.kind === 'update'
          ? api<PutawayRule>(`/putaway-rules/${a.rule.id}`, { method: 'PUT', body: { version: a.rule.version, priority: a.priority, onlyZone: a.onlyZone, active: a.active } })
          : api<void>(`/putaway-rules/${a.id}`, { method: 'DELETE' }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['putaway-rules'] }),
  })
}
