import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PickPage } from './PickPage'
import { TaskBoardPage } from './TaskBoardPage'

const list = (id: string, n: number) => ({ id, number: `PL-00000${n}`, status: 'OPEN', orderNumber: `SO-00000${n}`, customerName: 'Kandy Super',
  warehouseCode: 'MAIN', lines: 3, openLines: 3, priority: 'NORMAL', assignee: null, startedBy: null, createdAt: new Date().toISOString(), wave: null, tote: null })
const walkLine = (tote: number, bin: string, sku: string, status = 'OPEN') => ({ listId: `pl${tote}`, seq: 1, tote, orderNumber: `SO-00000${tote}`,
  sku, productName: sku, baseUom: 'EA', binCode: bin, batchNo: null, expiryDate: null, qtyToPick: 1, qtyPicked: status === 'OPEN' ? 0 : 1, status })
let wave: Record<string, unknown>
const calls: { url: string; body: unknown }[] = []

beforeEach(() => {
  calls.length = 0
  wave = { id: 'wv1', number: 'WV-000001', status: 'OPEN', warehouseCode: 'MAIN', cutOff: null, version: 0, assignee: null,
    orders: [], openLines: 2, walkMetres: 84, singleWalksMetres: 310,
    walk: [walkLine(1, 'A-01-01-01', 'P1'), walkLine(2, 'A-01-01-01', 'P1')] }
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/auth/me') return Response.json({ id: 'u', email: 's@b.lk', displayName: 'S', permissions: ['tasks:read', 'tasks:assign', 'picking:execute'] })
    if (url.startsWith('/api/v1/warehouses')) return Response.json({ items: [{ id: 'w1', code: 'MAIN', name: 'Main' }], page: 0, size: 100, total: 1 })
    if (url.startsWith('/api/v1/task-board/pickers')) return Response.json([])
    if (url.startsWith('/api/v1/task-board')) {
      return Response.json({ toRelease: 0, toPick: 2, picking: 0, pickedToday: 0, toPack: 0, toDispatch: 0, inReceiving: 0, lists: [list('pl1', 1), list('pl2', 2)] })
    }
    if (init.method === 'POST') {
      calls.push({ url, body: JSON.parse(init.body as string) })
      if (url.includes('/confirm')) wave = { ...wave, openLines: 1, walk: [walkLine(1, 'A-01-01-01', 'P1', 'PICKED'), walkLine(2, 'A-01-01-01', 'P1')] }
      return Response.json(url.includes('/confirm') ? { id: 'pl1', orderId: 'so1' } : wave, { status: url.endsWith('/pick-waves') ? 201 : 200 })
    }
    if (url === '/api/v1/pick-waves/wv1') return Response.json(wave)
    if (url.startsWith('/api/v1/pick-waves')) return Response.json({ items: [], page: 0, size: 50, total: 0 })
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } })

it('makes a wave of the ticked lists on the task board', async () => {
  const user = userEvent.setup()
  render(<QueryClientProvider client={client()}><TaskBoardPage /></QueryClientProvider>)
  const make = await screen.findByRole('button', { name: /Make wave/ })
  expect(make).toBeDisabled()
  await user.click(await screen.findByLabelText('Select PL-000001'))
  await user.click(screen.getByLabelText('Select PL-000002'))
  await user.click(screen.getByRole('button', { name: 'Make wave (2)' }))
  await waitFor(() => expect(calls[0]).toEqual({ url: '/api/v1/pick-waves', body: { warehouseId: 'w1', listIds: ['pl1', 'pl2'] } }))
})

it('walks a wave: one bin, two totes, each confirmed on its own list', async () => {
  const user = userEvent.setup()
  const router = createMemoryRouter([{ path: '/worker/pick/wave/:waveId', Component: PickPage }], { initialEntries: ['/worker/pick/wave/wv1'] })
  render(<QueryClientProvider client={client()}><RouterProvider router={router} /></QueryClientProvider>)
  expect(await screen.findByText('Put it in tote 1')).toBeInTheDocument()
  expect(screen.getByText(/walk about 84 m \(one by one: 310 m\)/)).toBeInTheDocument()
  await user.type(screen.getByLabelText('Scan the bin label'), 'A-01-01-01')
  await user.type(screen.getByLabelText('Scan the product'), 'P1')
  await user.click(screen.getByRole('button', { name: 'Picked' }))
  await waitFor(() => expect(calls[0]).toMatchObject({ url: '/api/v1/pick-lists/pl1/lines/1/confirm', body: { binCode: 'A-01-01-01', code: 'P1', qty: 1 } }))
  expect(await screen.findByText('Put it in tote 2')).toBeInTheDocument()
})
