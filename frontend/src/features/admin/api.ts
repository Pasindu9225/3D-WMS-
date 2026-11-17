import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Page } from '@/features/users/api'
import { api } from '@/lib/api'

export interface CompanyRow {
  id: string
  name: string
  planCode: string
  maxWarehouses: number
  status: 'ACTIVE' | 'SUSPENDED'
  createdAt: string
  warehouses: number
  users: number
  version: number
}

export interface Usage { warehouses: number; activeUsers: number; products: number; movements30Days: number; storedBytes: number; lastActive: string | null }

export interface Company {
  id: string
  name: string
  planCode: string
  maxWarehouses: number
  status: 'ACTIVE' | 'SUSPENDED'
  createdAt: string
  suspendedAt: string | null
  suspendedReason: string | null
  usage: Usage
  version: number
}

export interface Plan { code: string; name: string; pricePerWarehouseMinor: number; defaultMaxWarehouses: number; active: boolean; companies: number; version: number }

export function useCompanies(q: string, status: string, page: number) {
  const params = new URLSearchParams({ page: String(page), size: '25' })
  if (q.trim()) params.set('q', q.trim())
  if (status) params.set('status', status)
  return useQuery({ queryKey: ['admin-companies', q.trim(), status, page], queryFn: () => api<Page<CompanyRow>>(`/admin/tenants?${params}`), placeholderData: keepPreviousData })
}

export function useCompany(id: string | undefined) {
  return useQuery({ queryKey: ['admin-company', id], queryFn: () => api<Company>(`/admin/tenants/${id}`), enabled: !!id })
}

export function usePlans() {
  return useQuery({ queryKey: ['admin-plans'], queryFn: () => api<Plan[]>('/admin/plans') })
}

export function useCompanyAction(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'plan'; version: number; planCode: string; maxWarehouses: number } | { kind: 'suspend'; version: number; reason: string }
      | { kind: 'reactivate'; version: number }) =>
      a.kind === 'plan'
        ? api<Company>(`/admin/tenants/${id}/plan`, { method: 'PUT', body: { version: a.version, planCode: a.planCode, maxWarehouses: a.maxWarehouses } })
        : a.kind === 'suspend'
          ? api<Company>(`/admin/tenants/${id}/suspend`, { method: 'POST', body: { version: a.version, reason: a.reason } })
          : api<Company>(`/admin/tenants/${id}/reactivate`, { method: 'POST', body: { version: a.version } }),
    onSuccess: (c) => {
      qc.setQueryData(['admin-company', id], c)
      void qc.invalidateQueries({ queryKey: ['admin-companies'] })
    },
  })
}

export function useCreateCompany() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (b: { name: string; planCode: string; adminEmail: string; adminName: string }) =>
      api<{ company: Company; inviteUrl: string; inviteExpiresAt: string }>('/admin/tenants', { method: 'POST', body: b }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin-companies'] }),
  })
}

export function usePlanAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (a: { kind: 'create'; code: string; name: string; pricePerWarehouseMinor: number; defaultMaxWarehouses: number }
      | { kind: 'update'; plan: Plan; name: string; pricePerWarehouseMinor: number; defaultMaxWarehouses: number; active: boolean }) =>
      a.kind === 'create'
        ? api<Plan>('/admin/plans', { method: 'POST', body: { code: a.code, name: a.name, pricePerWarehouseMinor: a.pricePerWarehouseMinor, defaultMaxWarehouses: a.defaultMaxWarehouses } })
        : api<Plan>(`/admin/plans/${a.plan.code}`, { method: 'PUT', body: { version: a.plan.version, name: a.name, pricePerWarehouseMinor: a.pricePerWarehouseMinor,
          defaultMaxWarehouses: a.defaultMaxWarehouses, active: a.active } }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin-plans'] }),
  })
}

/** LKR from cents, e.g. 500000 -> "5,000.00". */
export const lkr = (minor: number) => (minor / 100).toLocaleString('en', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** "12.3 MB" style. */
export function bytes(n: number): string {
  if (n < 1024) return `${n} B`
  const units = ['KB', 'MB', 'GB']
  let v = n / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(1)} ${units[i]}`
}
