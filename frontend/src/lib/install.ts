/**
 * "Install app": Chrome fires beforeinstallprompt once, early, when the app can be installed. It is caught here at
 * start-up (imported by main.tsx) and kept until a page offers the button.
 */
interface InstallEvent extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

let saved: InstallEvent | null = null
const listeners = new Set<() => void>()

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault()
    saved = e as InstallEvent
    listeners.forEach((l) => l())
  })
  window.addEventListener('appinstalled', () => {
    saved = null
    listeners.forEach((l) => l())
  })
}

export function canInstall(): boolean {
  return saved !== null
}

export function onInstallChange(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export async function install(): Promise<void> {
  const e = saved
  if (!e) return
  await e.prompt()
  await e.userChoice
  saved = null
  listeners.forEach((l) => l())
}
