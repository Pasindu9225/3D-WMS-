import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { api } from '@/lib/api'
import { t } from '@/lib/i18n'
import { PageShell } from './PageShell'

export function HomePage() {
  // Also primes the XSRF-TOKEN cookie, which the sign-in form will need.
  const backend = useQuery({ queryKey: ['backend'], queryFn: () => api<void>('/auth/csrf').then(() => true), retry: false })
  const status = backend.isPending ? '…' : backend.isSuccess ? t('home.backendOk') : t('home.backendDown')

  return (
    <PageShell>
      <section className="w-full max-w-md space-y-6 text-center">
        <div className="space-y-2">
          <h1 className="text-3xl font-semibold tracking-tight text-balance">{t('home.title')}</h1>
          <p className="text-muted-foreground">{t('home.body')}</p>
        </div>
        <Button asChild size="lg">
          <Link to="/login">{t('home.signIn')}</Link>
        </Button>
        <p className="text-xs text-muted-foreground" aria-live="polite">
          <span className={backend.isError ? 'text-destructive' : undefined}>{t('home.backend', { status })}</span>
        </p>
      </section>
    </PageShell>
  )
}
