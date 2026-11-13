import { ApiError } from './api'
import { t, type MessageKey } from './i18n'
import en from './i18n/en.json'

/**
 * Turns any thrown error into a sentence for the user. Known backend codes have their own text;
 * anything else shows the correlation ID so support can find it in the server log.
 */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const key = `error.${error.code}`
    if (key in en) return t(key as MessageKey)
    return t('error.generic', { ref: error.correlationId ?? error.status })
  }
  return t('error.generic', { ref: '-' })
}

/** Field errors from a 400 VALIDATION_FAILED, keyed by field name, for React Hook Form. */
export function fieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {}
  return Object.fromEntries(error.errors.map((e) => [e.field, e.message]))
}

/** A known message key, or the code made readable: USER_ROLES_CHANGED -> "User roles changed". */
export function label(prefix: string, code: string): string {
  const key = `${prefix}.${code}`
  if (key in en) return t(key as MessageKey)
  const words = code.toLowerCase().replace(/_/g, ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}
