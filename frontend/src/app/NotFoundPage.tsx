import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { t } from '@/lib/i18n'
import { PageShell } from './PageShell'

export function NotFoundPage() {
  return (
    <PageShell>
      <section className="space-y-4 text-center">
        <h1 className="text-2xl font-semibold">{t('notFound.title')}</h1>
        <Button asChild variant="outline">
          <Link to="/">{t('notFound.back')}</Link>
        </Button>
      </section>
    </PageShell>
  )
}
