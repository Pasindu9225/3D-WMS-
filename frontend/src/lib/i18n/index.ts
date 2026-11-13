import en from './en.json'

/** Every UI string lives in en.json; a missing key is a type error, not a blank on screen. */
export type MessageKey = keyof typeof en

/** t('home.backend', { status: 'connected' }) replaces {status}. */
export function t(key: MessageKey, vars?: Record<string, string | number>): string {
  const text: string = en[key]
  return vars ? text.replace(/\{(\w+)\}/g, (_, name: string) => String(vars[name] ?? `{${name}}`)) : text
}
