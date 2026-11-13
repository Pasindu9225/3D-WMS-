import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { InvitePage } from './InvitePage'
import { LoginPage } from './LoginPage'

type Reply = { status: number; body?: unknown }
let replies: Record<string, Reply>
let requests: { url: string; body?: string }[]

beforeEach(() => {
  requests = []
  document.cookie = 'XSRF-TOKEN=tok'
  replies = {
    '/api/v1/auth/me': { status: 401, body: { code: 'UNAUTHENTICATED' } },
    '/api/v1/auth/refresh': { status: 401, body: { code: 'AUTH_FAILED' } },
    '/api/v1/auth/csrf': { status: 204 },
  }
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    requests.push({ url, body: init.body as string | undefined })
    const r = replies[url] ?? { status: 404 }
    return new Response(r.body === undefined ? null : JSON.stringify(r.body), { status: r.status })
  }))
})

afterEach(() => vi.unstubAllGlobals())

function renderAt(path: string, element: React.ReactNode) {
  const router = createMemoryRouter(
    [
      { path: '/login', element },
      { path: '/invite', element },
      { path: '/app', element: <p>app home</p> },
    ],
    { initialEntries: [path] },
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('LoginPage', () => {
  it('validates before calling the server', async () => {
    renderAt('/login', <LoginPage />)
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }))
    expect(await screen.findAllByText('Required')).toHaveLength(2)
    expect(requests.some((r) => r.url.endsWith('/auth/login'))).toBe(false)
  })

  it('shows the generic message when sign-in fails', async () => {
    replies['/api/v1/auth/login'] = { status: 401, body: { code: 'AUTH_FAILED' } }
    renderAt('/login', <LoginPage />)
    await userEvent.type(await screen.findByLabelText('Email'), 'a@b.lk')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is not correct')
  })

  it('goes to the app after signing in', async () => {
    replies['/api/v1/auth/login'] = { status: 200, body: { id: 'U', email: 'a@b.lk', fullName: 'A', roles: [], permissions: [] } }
    const router = renderAt('/login', <LoginPage />)
    await userEvent.type(await screen.findByLabelText('Email'), 'a@b.lk')
    await userEvent.type(screen.getByLabelText('Password'), 'right-password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/app'))
  })
})

describe('InvitePage', () => {
  it('takes the token from the #fragment, clears it from the address bar and sends it', async () => {
    window.history.replaceState(null, '', '/invite#secret-token-123')
    replies['/api/v1/auth/invite/accept'] = { status: 204 }
    renderAt('/invite', <InvitePage />)
    expect(window.location.hash).toBe('')

    await userEvent.type(await screen.findByLabelText('New password'), 'a long passphrase')
    await userEvent.type(screen.getByLabelText('Repeat password'), 'a different one!')
    await userEvent.click(screen.getByRole('button', { name: 'Set password' }))
    expect(await screen.findByText('The passwords do not match')).toBeInTheDocument()

    await userEvent.clear(screen.getByLabelText('Repeat password'))
    await userEvent.type(screen.getByLabelText('Repeat password'), 'a long passphrase')
    await userEvent.click(screen.getByRole('button', { name: 'Set password' }))
    await waitFor(() => expect(requests.some((r) => r.url.endsWith('/invite/accept'))).toBe(true))
    const sent = requests.find((r) => r.url.endsWith('/invite/accept'))
    expect(JSON.parse(sent?.body ?? '{}')).toEqual({ token: 'secret-token-123', password: 'a long passphrase' })
  })

  it('explains when the link is incomplete', async () => {
    window.history.replaceState(null, '', '/invite')
    renderAt('/invite', <InvitePage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('needs the full invite link')
    expect(screen.getByRole('button', { name: 'Set password' })).toBeDisabled()
  })
})
