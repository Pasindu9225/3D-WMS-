import { useLocation } from 'react-router'

/** The same pages serve the office app (/app/...) and the phone app (/worker/...); links stay in the one in use. */
export function useBase(): '/app' | '/worker' {
  return useLocation().pathname.startsWith('/worker') ? '/worker' : '/app'
}
