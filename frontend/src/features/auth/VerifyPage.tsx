import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { PageShell } from '@/app/PageShell'
import { FormMessage } from '@/components/ui/input'
import { api } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { ME_KEY, type Me } from './session'

/** /verify#<token>: the link from the sign-up email. Confirms, signs in, and opens the setup wizard. */
export function VerifyPage() {
  const [token] = useState(() => {
    const value = window.location.hash.slice(1)
    if (value) window.history.replaceState(null, '', window.location.pathname) // not left in history
    return value
  })
  const qc = useQueryClient()
  const navigate = useNavigate()
  const verify = useMutation({
    mutationFn: () => api<Me>('/signup/verify', { method: 'POST', body: { token } }),
    onSuccess: (me) => {
      qc.setQueryData(ME_KEY, me)
      void navigate('/app/setup', { replace: true })
    },
  })
  const started = useRef(false)
  useEffect(() => {
    if (token && !started.current) {
      started.current = true // once, even when React runs effects twice in development
      verify.mutate()
    }
  }, [token, verify])
  return (
    <PageShell>
      <div className="grid w-full max-w-sm gap-4 rounded-lg border bg-card p-6 shadow-sm">
        <h1 className="text-2xl font-semibold">{t('verify.title')}</h1>
        {!token && <FormMessage tone="error">{t('verify.noToken')}</FormMessage>}
        {verify.isPending && <p className="text-sm text-muted-foreground">{t('verify.working')}</p>}
        {verify.isError && (
          <>
            <FormMessage tone="error">{errorMessage(verify.error)}</FormMessage>
            <Link className="text-sm font-medium underline" to="/signup">{t('verify.again')}</Link>
          </>
        )}
      </div>
    </PageShell>
  )
}
