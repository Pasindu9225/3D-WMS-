import { Monitor, Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { t } from '@/lib/i18n'
import { nextTheme, setTheme, useTheme } from '@/lib/theme'

const icons = { light: Sun, dark: Moon, system: Monitor }
const labels = { light: 'theme.light', dark: 'theme.dark', system: 'theme.system' } as const

/** Cycles light -> dark -> system. */
export function ThemeToggle() {
  const theme = useTheme()
  const Icon = icons[theme]
  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={() => setTheme(nextTheme[theme])}
      aria-label={`${t('theme.toggle')} (${t(labels[theme])})`}
      title={t(labels[theme])}
    >
      <Icon />
    </Button>
  )
}
