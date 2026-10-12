import { useEffect, useState } from 'react';

/** Re-renders the caller every `intervalMs` and returns the current epoch ms (for "updated N s ago" text). */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}
