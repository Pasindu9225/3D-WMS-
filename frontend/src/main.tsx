import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import './lib/install'
import { router } from '@/app/router'
import { ME_KEY } from '@/features/auth/session'
import { ApiError, setSessionExpiredHandler } from '@/lib/api'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      // Never retry auth or permission errors; retry network hiccups (warehouse Wi-Fi) twice.
      retry: (count, error) => error instanceof ApiError && error.status === 0 && count < 2,
    },
  },
})

setSessionExpiredHandler(() => {
  // Only say "your session ended" to someone who was signed in; a first visit just goes to sign-in.
  // Never clear() for a first visit: that would drop the in-flight /auth/me query and leave the page
  // waiting forever. Its 401 simply ends in an error, and AppLayout redirects to sign-in.
  if (queryClient.getQueryData(ME_KEY) === undefined) return
  queryClient.clear()
  void router.navigate('/login?expired=1', { replace: true })
})

// The installable worker app works offline-first for its pages only (public/sw.js); never in dev, where
// files change on every save.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => void navigator.serviceWorker.register('/sw.js').catch(() => undefined))
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
