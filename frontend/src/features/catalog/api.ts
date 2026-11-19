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
  /** Low stock at or below this many base units (dashboard); null = not watched. */
  reorderPoint: number | null
  hasImage: boolean
  units: ProductUnit[]
  version: number
  /** A, B or C by how often it is picked (weekly); null until first worked out. */
  abcClass: 'A' | 'B' | 'C' | null
  /** Free text that putaway rules match, e.g. Frozen; null = none. */
  category: string | null
  /** Real carton size in mm and units per carton (v2.1, 3D); null when not known. */
  cartonLengthMm?: number | null
  cartonWidthMm?: number | null
  cartonHeightMm?: number | null
  unitsPerCarton?: number | null
  /** How it must be stored (v2.1); putaway follows it. */
  handling?: Handling | null
  /** The client (3PL) whose goods these are (v2.1); null = the company's own goods. */
  ownerCode?: string | null
  ownerName?: string | null
}

export type StorageTemp = 'AMBIENT' | 'CHILLED' | 'FROZEN'

export interface Handling {
  /** World customs (HS) code, digits only, e.g. 090230; null = not given. */
  hsCode: string | null
  storageTemp: StorageTemp
  /** UN dangerous goods class or division, e.g. 3 or 5.1; null = not dangerous. */
  hazardClass: string | null
  /** How many high it may stand, counting itself (1 = do not stack); null = no limit. */
  stackMax: number | null
  /** Nothing may stand on it. */
  fragile: boolean
}

export type ProductInput = Omit<Product, 'id' | 'sku' | 'hasImage' | 'version' | 'abcClass'> & { sku?: string; version?: number }

export const PRODUCT_PAGE_SIZE = 25

/** owner: '' = every product, '-' = the company's own goods, else a client code. */
export function useProducts(q: string, page: number, owner = '') {
  const params = new URLSearchParams({ page: String(page), size: String(PRODUCT_PAGE_SIZE) })
  if (q) params.set('q', q)
  if (owner) params.set('owner', owner)
  return useQuery({
    queryKey: ['products', q, page, owner],
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

/** Works out the ABC classes now (counts:approve) instead of waiting for Sunday. */
export function useRecalculateAbc() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api<{ a: number; b: number; c: number }>('/abc/recalculate', { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  })
}

/** Product picture: JPEG or PNG up to 5 MB; the server re-encodes it. */
export function useProductImage() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, file }: { id: string; file: File | null }) => {
      if (!file) return api<Product>(`/products/${id}/image`, { method: 'DELETE' })
      const form = new FormData()
      form.append('file', file)
      return api<Product>(`/products/${id}/image`, { method: 'POST', body: form })
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['products'] }),
  })
}

/** Image URL with a version so a replaced picture is not shown from the browser cache. */
export const imageUrl = (p: Product) => `/api/v1/products/${p.id}/image?v=${p.version}`

/** Suppliers, customers and clients (3PL stock owners) share one shape and one set of endpoints. */
export type PartnerKind = 'suppliers' | 'customers' | 'clients'

export interface Supplier {
  id: string
  type: 'SUPPLIER' | 'CUSTOMER' | 'CLIENT'
  code: string
  name: string
  contactName: string | null
  phone: string | null
  email: string | null
  address: string | null
  version: number
  /** Clients only (v2.1): HS sections 1-21 they may store; empty = any commodity. */
  allowedHsSections?: number[] | null
}

export type SupplierInput = Pick<Supplier, 'name' | 'contactName' | 'phone' | 'email' | 'address' | 'allowedHsSections'> & { code?: string; version?: number }

/** Every live client, for owner pickers (a 3PL has tens, not thousands). */
export function useClients() {
  return useQuery({ queryKey: ['clients', 'all'], queryFn: () => api<Page<Supplier>>('/clients?size=100&sort=code') })
}

export function useSuppliers(q: string, page: number, kind: PartnerKind = 'suppliers') {
  const params = new URLSearchParams({ page: String(page), size: String(PRODUCT_PAGE_SIZE) })
  if (q) params.set('q', q)
  return useQuery({
    queryKey: [kind, q, page],
    queryFn: () => api<Page<Supplier>>(`/${kind}?${params}`),
    placeholderData: keepPreviousData,
  })
}

export function useSaveSupplier(kind: PartnerKind = 'suppliers') {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: SupplierInput & { id?: string }) =>
      id ? api<Supplier>(`/${kind}/${id}`, { method: 'PUT', body }) : api<Supplier>(`/${kind}`, { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [kind] }),
  })
}

export function useArchiveSupplier(kind: PartnerKind = 'suppliers') {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/${kind}/${id}/archive`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [kind] }),
  })
}

/** CSV import result: all saved, or nothing saved and the rows to fix. */
export interface ImportResult {
  saved: boolean
  created: number
  updated: number
  errors: { row: number; field: string; message: string }[]
}

export type ImportKind = 'products' | PartnerKind | 'sales-orders'

export function useImport(what: ImportKind) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return api<ImportResult>(`/${what}/import`, { method: 'POST', body: form })
    },
    onSuccess: (r) => {
      if (r.saved) void qc.invalidateQueries({ queryKey: [what] })
    },
  })
}
