import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { CountPage } from './CountPage'
import { CountPlanPage } from './CountPlanPage'

const cline = (seq: number, bin: string, status = 'OPEN', counted: number | null = null) => ({
  seq, binCode: bin, sku: 'OIL-1L', productName: 'Coconut oil', baseUom: 'EA', batchNo: null, expiry: null, added: false, status, counted, again: false,
})
let counter: Record<string, unknown>
const calls: { url: string; body: unknown }[] = []
const review = {
  id: 'c1', number: 'CC-000001', warehouseCode: 'MAIN', scope: 'ZONE', scopeValue: 'A', status: 'REVIEW', createdAt: '2026-11-17T05:00:00Z',
  approvedAt: null, cancelReason: null, version: 3, differences: 1, valueMinor: -45000, tolerancePct: 2,
  lines: [
    { ...cline(1, 'A-01-01-01', 'COUNTED', 5), expected: 5, difference: 0, valueMinor: 0, countedBy: 'Kamal', countedAt: '2026-11-17T05:10:00Z', firstCount: null, overLimit: false },
    { ...cline(2, 'A-01-01-02', 'COUNTED', 2), expected: 3, difference: -1, valueMinor: -45000, countedBy: 'Kamal', countedAt: '2026-11-17T05:12:00Z', firstCount: 1, overLimit: true },
  ],
}

beforeEach(() => {
  calls.length = 0
  counter = { id: 'c1', number: 'CC-000001', warehouseCode: 'MAIN', status: 'OPEN', lines: 2, open: 2, items: [cline(1, 'A-01-01-01'), cline(2, 'A-01-01-02')] }
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/count-plans/c1/count') return Response.json(counter)
    if (url === '/api/v1/count-plans/c1') return Response.json(review)
    if (init.method === 'POST') {
      calls.push({ url, body: JSON.parse(init.body as string) })
      if (url.endsWith('/lines/1/count')) counter = { ...counter, open: 1, items: [cline(1, 'A-01-01-01', 'COUNTED', 5), cline(2, 'A-01-01-02')] }
      if (url.endsWith('/approve')) return Response.json({ ...review, status: 'APPROVED' })
      return Response.json(counter)
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

function show(path: string, route: string, Component: () => React.JSX.Element) {
  const router = createMemoryRouter([{ path: route, Component }], { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

it('counts blind on the phone: scan the bin and product, type what is there, no system figure shown', async () => {
  const user = userEvent.setup()
  show('/worker/count/c1', '/worker/count/:id', CountPage)
  expect(await screen.findByText('A-01-01-01', { selector: 'span.text-2xl' })).toBeInTheDocument()
  expect(screen.queryByText(/expected|system/i)).toBeNull()
  const save = screen.getByRole('button', { name: 'Save count' })
  await user.type(screen.getByLabelText('Scan the bin label'), 'a-01-01-02')
  expect(screen.getByText('Wrong bin: go to A-01-01-01')).toBeInTheDocument()
  expect(save).toBeDisabled()
  await user.clear(screen.getByLabelText('Scan the bin label'))
  await user.type(screen.getByLabelText('Scan the bin label'), 'A-01-01-01')
  await user.type(screen.getByLabelText('Scan the product'), '4791')
  await user.type(screen.getByLabelText('How many are there?'), '5')
  await user.click(save)
  await waitFor(() => expect(calls[0]).toMatchObject({ url: '/api/v1/count-plans/c1/lines/1/count', body: { binCode: 'A-01-01-01', code: '4791', qty: 5 } }))
  expect(await screen.findByText('A-01-01-02', { selector: 'span.text-2xl' })).toBeInTheDocument()
  expect(screen.getByText('1 of 2 left')).toBeInTheDocument()
})

it('reports goods that are not on the count', async () => {
  const user = userEvent.setup()
  show('/worker/count/c1', '/worker/count/:id', CountPage)
  await user.click(await screen.findByRole('button', { name: 'Something else here' }))
  const dialog = await screen.findByRole('dialog')
  const [bin, product] = [...dialog.querySelectorAll('input')]
  await user.type(bin!, 'A-01-02-01')
  await user.type(product!, '14791')
  await user.type(screen.getByLabelText('How many are there?', { selector: '[role=dialog] input' }), '1')
  await user.click(screen.getByRole('button', { name: 'Add and save' }))
  await waitFor(() => expect(calls[0]).toMatchObject({ url: '/api/v1/count-plans/c1/found', body: { binCode: 'A-01-02-01', code: '14791', qty: 1 } }))
})

it('shows the manager expected, counted and difference, and approves', async () => {
  const user = userEvent.setup()
  show('/app/counts/c1', '/app/counts/:id', CountPlanPage)
  expect(await screen.findByText('-1')).toBeInTheDocument()
  expect(screen.getByText(/Differences: 1/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Recount selected (0)' })).toBeDisabled()
  await user.click(screen.getByLabelText('Select A-01-01-02 OIL-1L'))
  expect(screen.getByRole('button', { name: 'Recount selected (1)' })).toBeEnabled()

  await user.click(screen.getByRole('button', { name: 'Approve differences' }))
  await user.click(screen.getAllByRole('button', { name: 'Approve differences' }).at(-1)!)
  await waitFor(() => expect(calls[0]).toMatchObject({ url: '/api/v1/count-plans/c1/approve', body: { version: 3 } }))
})

it('asks the counter to count a place again, and shows the manager the first count and the limit', async () => {
  counter = { ...counter, items: [{ ...cline(1, 'A-01-01-01'), again: true }, cline(2, 'A-01-01-02')] }
  show('/worker/count/c1', '/worker/count/:id', CountPage)
  expect(await screen.findByText('Please count this place once more, carefully.')).toBeInTheDocument()
  expect(screen.getByText('(count again)')).toBeInTheDocument()
})

it('shows the manager the first count, the over-limit line and the allowed difference', async () => {
  show('/app/counts/c1', '/app/counts/:id', CountPlanPage)
  expect(await screen.findByText('first count 1')).toBeInTheDocument()
  expect(screen.getByText('over the limit')).toBeInTheDocument()
  expect(screen.getByText(/allowed difference 2%/)).toBeInTheDocument()
})
