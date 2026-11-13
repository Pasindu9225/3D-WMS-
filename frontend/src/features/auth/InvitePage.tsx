import { PageShell } from '@/app/PageShell'
import { t } from '@/lib/i18n'

// ponytail: placeholder; task 8 reads the token from location.hash and posts it with the new password.
export function InvitePage() {
  return (
    <PageShell>
      <section className="space-y-2 text-center">
        <h1 className="text-2xl font-semibold">{t('invite.title')}</h1>
        <p className="text-muted-foreground">{t('invite.comingSoon')}</p>
      </section>
    </PageShell>
  )
}
