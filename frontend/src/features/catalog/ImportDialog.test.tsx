import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ImportDialog } from './ImportDialog'

let reply: unknown
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/products/import' && init.method === 'POST' && init.body instanceof FormData) return Response.json(reply)
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

function open() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ImportDialog what="products" onClose={() => undefined} />
    </QueryClientProvider>,
  )
}

it('lists the rows to fix when nothing was saved, and the counts when everything was', async () => {
  reply = { saved: false, created: 0, updated: 0, errors: [{ row: 3, field: 'sku', message: 'SKU is required' }] }
  open()
  const input = document.querySelector('input[type=file]') as HTMLInputElement
  await userEvent.upload(input, new File(['sku,name\n'], 'p.csv', { type: 'text/csv' }))
  expect(await screen.findByText('Nothing was saved. Fix these 1 problems and import again.')).toBeInTheDocument()
  expect(screen.getByText('SKU is required')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: /Download template/ })).toHaveAttribute('href', '/api/v1/products/import-template.csv')

  reply = { saved: true, created: 2, updated: 1, errors: [] }
  await userEvent.upload(input, new File(['sku,name\n'], 'p.csv', { type: 'text/csv' }))
  expect(await screen.findByText('Imported: 2 added, 1 updated.')).toBeInTheDocument()
})
