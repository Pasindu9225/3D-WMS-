import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { NewReturnPage } from './NewReturnPage'
import { ReturnPage } from './ReturnPage'

const line = (seq: number, reason: string, outcome: string | null = null, binCode: string | null = null) => ({
  seq, sku: 'OIL-1L', productName: 'Coconut oil', baseUom: 'EA', qty: 6, reason, outcome, binCode, batchNo: null, expiry: null,
  inspectedBy: outcome ? 'Kamal' : null, inspectedAt: null,
})
let ret: Record<string, unknown>
const calls: { url: string; body: unknown }[] = []

beforeEach(() => {
  calls.length = 0
  ret = { id: 'r1', number: 'RT-000001', orderId: 'so1', orderNumber: 'SO-000006', customerName: 'Kandy Super', warehouseCode: 'MAIN',
    status: 'OPEN', note: null, cancelReason: null, version: 0, createdAt: '2026-11-17T05:00:00Z', closedAt: null, open: 2,
    lines: [line(1, 'NOT_WANTED'), line(2, 'DAMAGED')] }
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/auth/me') {
      return Response.json({ id: 'u', email: 'r@b.lk', fullName: 'Kamal', roles: ['RECEIVER'], permissions: ['inbound:read', 'inbound:receive'] })
    }
    if (url === '/api/v1/returns/r1') return Response.json(ret)
    if (url === '/api/v1/sales-orders/so1/returnable') {
      return Response.json([{ sku: 'OIL-1L', productName: 'Coconut oil', baseUom: 'EA', shipped: 24, returned: 0, left: 24, units: { EA: 1, CASE: 12 } }])
    }
    if (init.method === 'POST') {
      calls.push({ url, body: JSON.parse(init.body as string) })
      if (url.endsWith('/lines/1/inspect')) ret = { ...ret, open: 1, lines: [line(1, 'NOT_WANTED', 'RESTOCK', 'A-01-01-02'), line(2, 'DAMAGED')] }
      return Response.json(url === '/api/v1/returns' ? { ...ret } : ret, { status: url === '/api/v1/returns' ? 201 : 200 })
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

function show(path: string, routes: { path: string; Component: () => React.JSX.Element }[]) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

it('starts a return from a shipped order: quantity, unit and reason per product', async () => {
  const user = userEvent.setup()
  const router = show('/app/returns/new/so1', [{ path: '/app/returns/new/:orderId', Component: NewReturnPage },
    { path: '/app/returns/:id', Component: () => <p>opened</p> }])
  const qty = await screen.findByLabelText('Quantity of OIL-1L coming back')
  expect(screen.getByRole('button', { name: 'Create return' })).toBeDisabled()
  await user.type(qty, '1')
  await user.selectOptions(screen.getByLabelText('Unit for OIL-1L'), 'CASE')
  await user.selectOptions(screen.getByLabelText('Why OIL-1L came back'), 'DAMAGED')
  await user.click(screen.getByRole('button', { name: 'Create return' }))
  await waitFor(() => expect(calls[0]).toMatchObject({ url: '/api/v1/returns',
    body: { orderId: 'so1', lines: [{ sku: 'OIL-1L', uom: 'CASE', qty: 1, reason: 'DAMAGED' }] } }))
  await waitFor(() => expect(router.state.location.pathname).toBe('/app/returns/r1'))
})

it('inspects a line on the phone: restock into a scanned bin; scrap needs no bin', async () => {
  const user = userEvent.setup()
  show('/worker/returns/r1', [{ path: '/worker/returns/:id', Component: ReturnPage }])
  const saves = await screen.findAllByRole('button', { name: 'Save' })
  expect(saves[0]).toBeDisabled() // restock needs a bin
  await user.type(screen.getAllByLabelText('Scan the bin label')[0]!, 'a-01-01-02')
  await user.click(saves[0]!)
  await waitFor(() => expect(calls[0]).toMatchObject({ url: '/api/v1/returns/r1/lines/1/inspect', body: { outcome: 'RESTOCK', binCode: 'a-01-01-02' } }))
  expect(await screen.findByText('A-01-01-02')).toBeInTheDocument()

  await user.click(screen.getByRole('radio', { name: 'Scrap' }))
  expect(screen.getByText('Cannot be used: nothing goes back into stock.')).toBeInTheDocument()
  expect(screen.queryByLabelText('Scan the bin label')).toBeNull()
  await user.click(screen.getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(calls[1]).toMatchObject({ url: '/api/v1/returns/r1/lines/2/inspect', body: { outcome: 'SCRAP' } }))
})
