import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ProductsPage } from './ProductsPage'

let requests: { url: string; method?: string; body?: string }[]

beforeEach(() => {
  requests = []
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    requests.push({ url, method: init.method, body: init.body as string | undefined })
    if (url === '/api/v1/auth/me') {
      return Response.json({ id: 'u', email: 'a@b.lk', displayName: 'A', permissions: ['catalog:read', 'catalog:manage'] })
    }
    if (url === '/api/v1/products' && init.method === 'POST') return Response.json({}, { status: 201 })
    if (url.startsWith('/api/v1/products')) return Response.json({ items: [], page: 0, size: 25, total: 0 })
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('creates a product from kg, a box size in cm, a price and a case unit', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ProductsPage />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /New product/ }))
  await user.type(screen.getByLabelText('SKU'), 'oil-1l')
  await user.type(screen.getByLabelText('Name'), 'Coconut oil 1L')
  await user.type(screen.getByLabelText('Barcode'), '4791234000011')
  await user.type(screen.getByLabelText('Weight (kg)'), '0.95')
  await user.type(screen.getByLabelText('Volume (L)'), '10x8x25')
  await user.type(screen.getByLabelText('Cost (LKR)'), '1,250.50')
  await user.click(screen.getByLabelText('Track expiry dates'))
  await user.click(screen.getByRole('button', { name: 'Add a unit' }))
  await user.type(screen.getByLabelText('Unit 1'), 'case')
  await user.type(screen.getByLabelText('Base units'), '12')
  await user.click(screen.getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(requests.some((r) => r.method === 'POST')).toBe(true))
  expect(JSON.parse(requests.find((r) => r.method === 'POST')!.body!)).toEqual({
    sku: 'OIL-1L', name: 'Coconut oil 1L', barcode: '4791234000011', baseUom: 'EA', weightG: 950, volumeCm3: 2000,
    costMinor: 125050, trackBatch: false, trackExpiry: true, units: [{ uom: 'CASE', factor: 12, barcode: null }],
  })
})
