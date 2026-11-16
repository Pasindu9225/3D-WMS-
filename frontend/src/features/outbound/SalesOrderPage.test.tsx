import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SalesOrderPage } from './SalesOrderPage'

let posted: unknown
const oil = {
  id: 'p1', sku: 'OIL-1L', name: 'Coconut oil', baseUom: 'EA', costMinor: 45000, barcode: null, weightG: null, volumeCm3: null,
  trackBatch: false, trackExpiry: false, hasImage: false, units: [{ uom: 'CASE', factor: 12, barcode: null }], version: 0,
}
const page = <T,>(items: T[]) => ({ items, page: 0, size: 100, total: items.length })

beforeEach(() => {
  posted = undefined
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/auth/me') return Response.json({ id: 'u', email: 'a@b.lk', displayName: 'A', permissions: ['outbound:read', 'outbound:manage'] })
    if (url.startsWith('/api/v1/customers')) {
      return Response.json(page([{ id: 'c1', code: 'CUS-1', name: 'Kandy Super', address: '45 Peradeniya Rd, Kandy' }]))
    }
    if (url.startsWith('/api/v1/warehouses')) return Response.json(page([{ id: 'w1', code: 'MAIN', name: 'Main' }]))
    if (url.startsWith('/api/v1/products')) return Response.json(page(url.includes('q=oil') ? [oil] : []))
    if (url === '/api/v1/sales-orders' && init.method === 'POST') {
      posted = JSON.parse(init.body as string)
      return Response.json({ id: 'so1', number: 'SO-000001', status: 'DRAFT', lines: [], version: 0 }, { status: 201 })
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('picks a customer (their address is the default ship-to), adds cases and saves the draft', async () => {
  const user = userEvent.setup()
  const router = createMemoryRouter([{ path: '/app/sales-orders/:id', Component: SalesOrderPage }],
    { initialEntries: ['/app/sales-orders/new'] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: 'Save draft' }))
  expect(await screen.findByText('Add at least one product')).toBeInTheDocument()

  await user.selectOptions(screen.getByLabelText('Customer'), await screen.findByRole('option', { name: 'Kandy Super (CUS-1)' }))
  expect(screen.getByLabelText('Ship to')).toHaveAttribute('placeholder', '45 Peradeniya Rd, Kandy')
  await user.type(screen.getByLabelText('Customer order no.'), 'KSM-4471')
  await user.selectOptions(screen.getByLabelText('Warehouse'), await screen.findByRole('option', { name: 'MAIN · Main' }))
  await user.type(screen.getByLabelText(/Add a product/), 'oil')
  await user.click(await screen.findByRole('button', { name: /OIL-1L/ }))
  await user.selectOptions(screen.getByLabelText('Unit for OIL-1L'), 'CASE')
  const qty = screen.getByLabelText('Quantity of OIL-1L')
  await user.clear(qty)
  await user.type(qty, '10')

  await user.click(screen.getByRole('button', { name: 'Save draft' }))
  await waitFor(() => expect(posted).toEqual({
    customerCode: 'CUS-1', customerRef: 'KSM-4471', warehouseId: 'w1', requestedDate: null, shipTo: null, note: null,
    lines: [{ sku: 'OIL-1L', uom: 'CASE', qty: 10 }],
  }))
})
