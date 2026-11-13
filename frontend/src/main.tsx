import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import { router } from '@/app/router'
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
  queryClient.clear()
  void router.navigate('/login')
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
