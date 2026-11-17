import { X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { t } from '@/lib/i18n'
import { useSetup } from './SetupPage'

const HIDE_KEY = 'wms.setup.hidden'

function hidden(): boolean {
  try { return localStorage.getItem(HIDE_KEY) === '1' } catch { return false }
}

/** Home page: "Finish setting up" until every step is done (or the admin hides it in this browser). */
export function SetupCard() {
  const [hide, setHide] = useState(hidden)
  const setup = useSetup(!hide)
  if (hide || !setup.data || setup.data.complete) return null
  const done = setup.data.steps.filter((s) => s.done).length
  return (
    <div className="mb-4 flex items-center gap-3 rounded-lg border border-primary/40 bg-card p-4">
      <div className="min-w-0 flex-1">
        <p className="font-medium">{t('setup.cardTitle')}</p>
        <p className="text-sm text-muted-foreground">{t('setup.cardBody', { done, total: setup.data.steps.length })}</p>
      </div>
      <Button asChild size="sm"><Link to="/app/setup">{t('setup.cardGo')}</Link></Button>
      <Button size="icon" variant="ghost" aria-label={t('setup.cardHide')} onClick={() => {
        try { localStorage.setItem(HIDE_KEY, '1') } catch { /* private window: hidden for this visit only */ }
        setHide(true)
      }}><X /></Button>
    </div>
  )
}
