import { PageShell } from '@/app/PageShell'
import { t } from '@/lib/i18n'

// ponytail: placeholder; the real form (React Hook Form + Zod) is task 8.
export function LoginPage() {
  return (
    <PageShell>
      <section className="space-y-2 text-center">
        <h1 className="text-2xl font-semibold">{t('login.title')}</h1>
        <p className="text-muted-foreground">{t('login.comingSoon')}</p>
      </section>
    </PageShell>
  )
}
