import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PutawayRulesPage } from './PutawayRulesPage'

const calls: { url: string; method: string; body: unknown }[] = []

beforeEach(() => {
  calls.length = 0
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/auth/me') return Response.json({ id: 'u', email: 's@b.lk', displayName: 'S', permissions: ['tasks:assign'] })
    if (url.startsWith('/api/v1/warehouses')) return Response.json({ items: [{ id: 'w1', code: 'MAIN', name: 'Main' }], page: 0, size: 100, total: 1 })
    if (init.method && init.method !== 'GET') {
      calls.push({ url, method: init.method, body: init.body ? JSON.parse(init.body as string) : null })
      return init.method === 'DELETE' ? new Response(null, { status: 204 }) : Response.json({}, { status: 201 })
    }
    if (url.startsWith('/api/v1/putaway-rules')) {
      return Response.json([
        { id: 'r1', priority: 5, appliesTo: 'PRODUCT', sku: 'RICE-5KG', productName: 'Samba rice', category: null, binCode: 'A-01-01-03', zoneCode: null, onlyZone: false, active: true, version: 0 },
        { id: 'r2', priority: 10, appliesTo: 'CATEGORY', sku: null, productName: null, category: 'Frozen', binCode: null, zoneCode: 'C', onlyZone: true, active: true, version: 2 },
      ])
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

const page = () => render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><PutawayRulesPage /></QueryClientProvider>)

it('lists the rules in words and adds a category rule for one zone only', async () => {
  const user = userEvent.setup()
  page()
  expect(await screen.findByText('Bin A-01-01-03')).toBeInTheDocument()
  expect(screen.getByText('Category: Frozen')).toBeInTheDocument()
  expect(screen.getByText('Only zone C')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'New rule' }))
  await user.selectOptions(screen.getByLabelText('Applies to'), 'CATEGORY')
  await user.type(screen.getByLabelText('Category (as on the product)'), 'Chemicals')
  await user.type(screen.getByLabelText('Zone code (as in the layout)'), 'H')
  await user.click(screen.getByLabelText('Only this zone (refuse other bins)'))
  await user.click(screen.getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(calls[0]).toEqual({ url: '/api/v1/putaway-rules', method: 'POST',
    body: { warehouseId: 'w1', priority: 100, onlyZone: true, category: 'Chemicals', zoneCode: 'H' } }))
})

it('switches a rule off and deletes one', async () => {
  const user = userEvent.setup()
  page()
  await screen.findByText('Category: Frozen')
  await user.click(screen.getAllByRole('button', { name: 'Edit' })[1]!)
  await user.click(screen.getByLabelText('Rule is on'))
  await user.click(screen.getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(calls[0]).toEqual({ url: '/api/v1/putaway-rules/r2', method: 'PUT', body: { version: 2, priority: 10, onlyZone: true, active: false } }))
  await user.click(screen.getAllByRole('button', { name: 'Edit' })[0]!)
  await user.click(screen.getByRole('button', { name: 'Delete rule' }))
  await waitFor(() => expect(calls[1]).toEqual({ url: '/api/v1/putaway-rules/r1', method: 'DELETE', body: null }))
})
