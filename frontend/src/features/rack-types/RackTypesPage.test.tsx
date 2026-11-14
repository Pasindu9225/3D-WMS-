import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useEditor } from '@/features/layout/editorStore'
import { RackTypesPage } from './RackTypesPage'

let requests: { url: string; method?: string; body?: string }[]

beforeEach(() => {
  requests = []
  document.cookie = 'XSRF-TOKEN=tok'
  useEditor.getState().setUnit('ft')
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    requests.push({ url, method: init.method, body: init.body as string | undefined })
    if (url === '/api/v1/auth/me') {
      return Response.json({ id: 'u', email: 'a@b.lk', displayName: 'A', permissions: ['warehouses:read', 'warehouses:manage'] })
    }
    if (url.startsWith('/api/v1/rack-templates') && init.method === 'POST') return Response.json({}, { status: 201 })
    if (url.startsWith('/api/v1/rack-templates')) return Response.json({ items: [], page: 0, size: 100, total: 0 })
    return new Response(null, { status: 404 })
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  useEditor.getState().setUnit('m')
})

it('creates a rack type from sizes in feet and a weight in tonnes, sent to the server in mm and grams', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RackTypesPage />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /New rack type/ }))
  await user.type(screen.getByLabelText('Code'), 'pal9')
  await user.type(screen.getByLabelText('Name'), 'Pallet 9 ft')
  await user.type(screen.getByLabelText('Width (ft)'), '9')
  await user.type(screen.getByLabelText('Depth (ft)'), '110cm')
  await user.type(screen.getByLabelText('Height (ft)'), '15')
  await user.clear(screen.getByLabelText('Max weight per level (kg)'))
  await user.type(screen.getByLabelText('Max weight per level (kg)'), '2t')
  await user.click(screen.getByRole('button', { name: 'Save' }))

  await waitFor(() => expect(requests.some((r) => r.method === 'POST')).toBe(true))
  const sent = JSON.parse(requests.find((r) => r.method === 'POST')!.body!)
  expect(sent).toEqual({ code: 'PAL9', name: 'Pallet 9 ft', widthMm: 2743, depthMm: 1100, heightMm: 4572, levels: 4,
    binsPerLevel: 3, maxWeightPerLevelG: 2_000_000 })
})
