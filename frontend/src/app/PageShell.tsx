import type { ReactNode } from 'react'
import { Boxes } from 'lucide-react'
import { ThemeToggle } from '@/components/ThemeToggle'
import { t } from '@/lib/i18n'

/** Frame for the pages shown before sign-in. The signed-in app layout comes in task 8. */
export function PageShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="flex items-center justify-between border-b px-4 py-3 sm:px-6">
        <span className="flex items-center gap-2 font-semibold">
          <Boxes className="size-5 text-primary" aria-hidden />
          {t('app.name')}
        </span>
        <ThemeToggle />
      </header>
      <main className="flex flex-1 items-center justify-center px-4 py-10">{children}</main>
    </div>
  )
}
