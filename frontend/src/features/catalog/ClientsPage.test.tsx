import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ClientsPage } from './PartnersPage'

let requests: { url: string; method?: string; body?: string }[]

beforeEach(() => {
  requests = []
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    requests.push({ url, method: init.method, body: init.body as string | undefined })
    if (url === '/api/v1/auth/me') {
      return Response.json({ id: 'u', email: 'a@b.lk', displayName: 'A', permissions: ['catalog:read', 'catalog:manage'] })
    }
    if (url === '/api/v1/clients' && init.method === 'POST') return Response.json({}, { status: 201 })
    if (url.startsWith('/api/v1/clients')) return Response.json({ items: [], page: 0, size: 25, total: 0 })
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('creates a 3PL client limited to the commodity sections ticked', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ClientsPage />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /New client/ }))
  await user.type(screen.getByLabelText('Code'), 'food')
  await user.type(screen.getByLabelText('Name'), 'Ceylon Foods')
  await user.click(screen.getByLabelText(/Prepared foods, drinks and tobacco/))
  await user.click(screen.getByLabelText(/Vegetable products/))
  await user.click(screen.getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(requests.some((r) => r.method === 'POST')).toBe(true))
  expect(JSON.parse(requests.find((r) => r.method === 'POST')!.body!)).toMatchObject({ code: 'FOOD', name: 'Ceylon Foods', allowedHsSections: [2, 4] })
}, 15_000)
