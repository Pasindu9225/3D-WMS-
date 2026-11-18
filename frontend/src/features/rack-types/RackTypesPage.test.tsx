import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
    binsPerLevel: 3, maxWeightPerLevelG: 2_000_000, style: 'PALLET', uprightColor: '#1d4ed8', beamColor: '#f97316' })
})

it('chooses how the rack looks in 3D: shelving in its own colours, shown in the preview', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RackTypesPage />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /New rack type/ }))
  await user.type(screen.getByLabelText('Code'), 'SH1')
  await user.type(screen.getByLabelText('Name'), 'Shelving')
  await user.type(screen.getByLabelText('Width (ft)'), '6')
  await user.type(screen.getByLabelText('Depth (ft)'), '2')
  await user.type(screen.getByLabelText('Height (ft)'), '7')
  await user.selectOptions(screen.getByLabelText('Type'), 'SHELF')
  const colour = (label: string, value: string) => fireEvent.input(screen.getByLabelText(label), { target: { value } }) // what the colour picker does
  colour('Uprights', '#22c55e')
  colour('Beams / shelves', '#64748b')
  expect(screen.getByRole('img', { name: 'Preview of the rack' }).querySelectorAll('rect[fill="#22c55e"]')).toHaveLength(2)
  await user.click(screen.getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(requests.some((r) => r.method === 'POST')).toBe(true))
  expect(JSON.parse(requests.find((r) => r.method === 'POST')!.body!)).toMatchObject({ style: 'SHELF', uprightColor: '#22c55e', beamColor: '#64748b' })
})

it('floor storage: the form asks for pallet positions and pallets high; the preview has no uprights', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RackTypesPage />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /New rack type/ }))
  await user.selectOptions(screen.getByLabelText('Type'), 'FLOOR')
  expect(screen.getByLabelText('Pallets high')).toBeInTheDocument()
  expect(screen.getByLabelText('Pallet positions')).toBeInTheDocument()
  expect(screen.getByRole('img', { name: 'Preview of the rack' }).querySelectorAll('rect[fill="#1d4ed8"]')).toHaveLength(0)
})

it('shows a live preview while typing: drawn to the typed proportions, with a summary of places and load', async () => {
  const user = userEvent.setup()
  useEditor.getState().setUnit('m')
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RackTypesPage />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /New rack type/ }))
  const preview = () => screen.getByRole('img', { name: 'Preview of the rack' })
  expect(screen.getByTestId('rack-summary')).toHaveTextContent('4 levels × 3 bins = 12 places · up to 1,000 kg per level')

  await user.type(screen.getByLabelText('Width (m)'), '5.4')
  await user.type(screen.getByLabelText('Depth (m)'), '1.1')
  await user.type(screen.getByLabelText('Height (m)'), '2.7')
  expect(Number(preview().getAttribute('width'))).toBeGreaterThan(Number(preview().getAttribute('height'))) // wide and low
  await user.clear(screen.getByLabelText('Levels'))
  await user.type(screen.getByLabelText('Levels'), '2')
  expect(screen.getByTestId('rack-summary')).toHaveTextContent('5.4 m × 1.1 m × 2.7 m · 2 levels × 3 bins = 6 places')
})
