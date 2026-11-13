import { useMe } from '@/features/auth/session'
import { t } from '@/lib/i18n'

export function AppHome() {
  const me = useMe()
  return (
    <section className="max-w-2xl space-y-2">
      <h1 className="text-2xl font-semibold">{t('home.welcome', { name: me.data?.fullName ?? '' })}</h1>
      <p className="text-muted-foreground">{t('home.body')}</p>
    </section>
  )
}
