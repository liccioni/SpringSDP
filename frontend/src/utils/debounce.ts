// Coalesces bursts of calls into one trailing invocation, so a burst of
// TRADE_CREATED events during a busy period doesn't refetch trade history
// once per event (see TradeBlotter's use against refreshInfiniteCache).
export function debounce<Args extends unknown[]>(
  fn: (...args: Args) => void,
  delayMs: number,
): (...args: Args) => void {
  let timeout: ReturnType<typeof setTimeout> | undefined

  return (...args: Args) => {
    clearTimeout(timeout)
    timeout = setTimeout(() => fn(...args), delayMs)
  }
}
