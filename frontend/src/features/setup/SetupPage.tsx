import { useQuery } from '@tanstack/react-query'
import { CheckCircle2, Circle, PartyPopper } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { FormMessage } from '@/components/ui/input'
import { useMe } from '@/features/auth/session'
import { api } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'

export interface SetupStatus { steps: { key: string; done: boolean }[]; complete: boolean; warehouseId: string | null }

export function useSetup(enabled = true) {
  return useQuery({ queryKey: ['setup'], queryFn: () => api<SetupStatus>('/setup'), enabled, refetchOnWindowFocus: true })
}

/** Where each step is done in the app. */
function linkFor(key: string, warehouseId: string | null): string {
  switch (key) {
    case 'WAREHOUSE': return '/app/warehouses'
    case 'RACK_TYPE': return '/app/rack-types'
    case 'LAYOUT': return warehouseId ? `/app/warehouses/${warehouseId}/layout` : '/app/warehouses'
    case 'PRODUCTS': return '/app/products'
    case 'RECEIVE': return '/app/purchase-orders'
    default: return '/app/users'
  }
}

/** /app/setup: the guided first hour for a new company. Each step ticks itself when it is done anywhere in the app. */
export function SetupPage() {
  const me = useMe()
  const setup = useSetup()
  if (setup.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (setup.isError) return <FormMessage tone="error">{errorMessage(setup.error)}</FormMessage>
  const s = setup.data
  const next = s.steps.find((x) => !x.done)?.key
  return (
    <div className="grid max-w-3xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('setup.title', { name: me.data?.fullName ?? '' })}</h1>
        <p className="text-sm text-muted-foreground">{t('setup.subtitle')}</p>
      </div>
      {s.complete && (
        <FormMessage tone="success"><PartyPopper className="mr-1 inline size-4" />{t('setup.ready')}</FormMessage>
      )}
      <ol className="grid gap-3">
        {s.steps.map((step, i) => (
          <li key={step.key} className={`flex items-start gap-3 rounded-lg border bg-card p-4 ${step.key === next ? 'border-primary' : ''}`}>
            {step.done ? <CheckCircle2 aria-label={t('setup.done')} className="mt-0.5 size-5 shrink-0 text-emerald-600" />
              : <Circle aria-label={t('setup.todo')} className="mt-0.5 size-5 shrink-0 text-muted-foreground" />}
            <div className="min-w-0 flex-1">
              <h2 className="font-medium">{i + 1}. {t(`setup.step.${step.key}` as MessageKey)}</h2>
              <p className="text-sm text-muted-foreground">{t(`setup.why.${step.key}` as MessageKey)}</p>
            </div>
            {!step.done && (
              <Button asChild size="sm" variant={step.key === next ? 'default' : 'outline'}>
                <Link to={linkFor(step.key, s.warehouseId)}>{t(`setup.go.${step.key}` as MessageKey)}</Link>
              </Button>
            )}
          </li>
        ))}
      </ol>
      <p className="text-xs text-muted-foreground">{t('setup.hint')}</p>
    </div>
  )
}
