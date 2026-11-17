import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { RefillPage } from './RefillPage'
import { RefillRulesPage } from './RefillRulesPage'

const calls: { url: string; body: unknown }[] = []
let task: Record<string, unknown>

beforeEach(() => {
  calls.length = 0
  task = { id: 'rf1', number: 'RF-000001', status: 'OPEN', warehouseCode: 'MAIN', binCode: 'A-01-01-01', sku: 'OIL-1L', productName: 'Coconut oil',
    baseUom: 'EA', qty: 16, movedQty: null, from: { binCode: 'A-01-02-01', batchNo: null, expiryDate: '2026-12-31', free: 12 }, createdAt: '2026-11-17T08:00:00Z' }
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/auth/me') return Response.json({ id: 'u', email: 's@b.lk', displayName: 'S', permissions: ['tasks:assign', 'inventory:transfer'] })
    if (url.startsWith('/api/v1/warehouses')) return Response.json({ items: [{ id: 'w1', code: 'MAIN', name: 'Main' }], page: 0, size: 100, total: 1 })
    if (init.method === 'POST') {
      calls.push({ url, body: JSON.parse(init.body as string) })
      if (url.endsWith('/confirm')) task = { ...task, status: 'DONE', movedQty: 12, from: null }
      return Response.json(url.endsWith('/confirm') ? task : { made: 1 }, { status: url.endsWith('/refill-rules') ? 201 : 200 })
    }
    if (url.startsWith('/api/v1/refill-rules')) {
      return Response.json([{ id: 'r1', binId: 'b1', binCode: 'A-01-01-01', productId: 'p1', sku: 'OIL-1L', productName: 'Coconut oil', baseUom: 'EA',
        minQty: 5, maxQty: 20, active: true, onHand: 4, openTask: 'RF-000001', version: 0 }])
    }
    if (url === '/api/v1/refill-tasks/rf1') return Response.json(task)
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } })

it('lists the rules with on hand below min marked, and adds one', async () => {
  const user = userEvent.setup()
  render(<QueryClientProvider client={client()}><RefillRulesPage /></QueryClientProvider>)
  expect(await screen.findByText('RF-000001')).toBeInTheDocument()
  expect(screen.getByText('4')).toHaveClass('text-destructive') // below the minimum of 5
  await user.click(screen.getByRole('button', { name: 'New rule' }))
  await user.type(screen.getByLabelText('Pick bin code'), 'A-01-01-02')
  await user.type(screen.getByLabelText('Product (SKU or barcode)'), 'RICE-5KG')
  await user.type(screen.getByLabelText('Minimum'), '2')
  await user.type(screen.getByLabelText('Maximum'), '10')
  await user.click(screen.getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(calls[0]).toEqual({ url: '/api/v1/refill-rules',
    body: { warehouseId: 'w1', binCode: 'A-01-01-02', sku: 'RICE-5KG', minQty: 2, maxQty: 10 } }))
})

it('does a refill on the phone: take from the storage bin shown, scan the pick bin', async () => {
  const user = userEvent.setup()
  const router = createMemoryRouter([{ path: '/worker/refill/:id', Component: RefillPage }], { initialEntries: ['/worker/refill/rf1'] })
  render(<QueryClientProvider client={client()}><RouterProvider router={router} /></QueryClientProvider>)
  expect(await screen.findByText('A-01-02-01', { selector: 'span.text-2xl' })).toBeInTheDocument()
  expect(screen.getByLabelText('Quantity')).toHaveValue('12') // what is free there, less than the 16 asked
  await user.type(screen.getByLabelText('Scan the storage bin you take from'), 'A-01-02-01')
  await user.type(screen.getByLabelText('Scan the product'), 'OIL-1L')
  await user.type(screen.getByLabelText('Scan the pick bin'), 'A-01-01-09')
  expect(screen.getByText('Wrong bin: go to A-01-01-01')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Moved' })).toBeDisabled()
  await user.clear(screen.getByLabelText('Scan the pick bin'))
  await user.type(screen.getByLabelText('Scan the pick bin'), 'a-01-01-01')
  await user.click(screen.getByRole('button', { name: 'Moved' }))
  await waitFor(() => expect(calls[0]).toEqual({ url: '/api/v1/refill-tasks/rf1/confirm',
    body: { fromBinCode: 'A-01-02-01', toBinCode: 'a-01-01-01', code: 'OIL-1L', qty: 12 } }))
  expect(await screen.findByText('Done: 12 EA moved to A-01-01-01.')).toBeInTheDocument()
})
