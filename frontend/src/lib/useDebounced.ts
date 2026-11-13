import { useEffect, useState } from 'react'

/** The value, once it has stopped changing for `ms`. Keeps search boxes from sending a request per key. */
export function useDebounced<T>(value: T, ms = 300): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const id = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(id)
  }, [value, ms])
  return settled
}
