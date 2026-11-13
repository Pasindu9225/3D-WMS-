import { useSyncExternalStore } from 'react'

export type ThemePref = 'light' | 'dark' | 'system'

const KEY = 'wms-theme' // the only thing kept in localStorage; never tokens (SEC-01)
const media = window.matchMedia('(prefers-color-scheme: dark)')
const listeners = new Set<() => void>()

function read(): ThemePref {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

function apply(pref: ThemePref): void {
  const dark = pref === 'dark' || (pref === 'system' && media.matches)
  document.documentElement.classList.toggle('dark', dark)
}

media.addEventListener('change', () => apply(read()))

export function setTheme(pref: ThemePref): void {
  try {
    if (pref === 'system') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, pref)
  } catch {
    // storage blocked: the choice lasts for this page only
  }
  apply(pref)
  listeners.forEach((l) => l())
}

export function useTheme(): ThemePref {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    read,
  )
}

export const nextTheme: Record<ThemePref, ThemePref> = { light: 'dark', dark: 'system', system: 'light' }
