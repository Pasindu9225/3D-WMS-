import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Page } from '@/features/users/api'
import { api } from '@/lib/api'

export interface ProductUnit {
  uom: string
  /** Base units in one of this unit (CASE = 12 EA). */
  factor: number
  barcode: string | null
}

export interface Product {
  id: string
  sku: string
  barcode: string | null
  name: string
  baseUom: string
  weightG: number | null
  volumeCm3: number | null
  trackBatch: boolean
  trackExpiry: boolean
  /** Cost per base unit in cents. */
  costMinor: number | null
  hasImage: boolean
  units: ProductUnit[]
  version: number
}

export type ProductInput = Omit<Product, 'id' | 'sku' | 'hasImage' | 'version'> & { sku?: string; version?: number }

export const PRODUCT_PAGE_SIZE = 25

export function useProducts(q: string, page: number) {
  const params = new URLSearchParams({ page: String(page), size: String(PRODUCT_PAGE_SIZE) })
  if (q) params.set('q', q)
  return useQuery({
    queryKey: ['products', q, page],
    queryFn: () => api<Page<Product>>(`/products?${params}`),
    placeholderData: keepPreviousData,
  })
}

export function useSaveProduct() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: ProductInput & { id?: string }) =>
      id ? api<Product>(`/products/${id}`, { method: 'PUT', body }) : api<Product>('/products', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  })
}

export function useArchiveProduct() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/products/${id}/archive`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  })
}
