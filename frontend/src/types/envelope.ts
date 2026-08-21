// correlationId mirrors the gateway's Envelope (issue #131) - present only on
// GET_TRADE_HISTORY/TRADE_HISTORY today, absent/undefined on every other type.
export interface Envelope {
  type: string
  payload: unknown
  correlationId?: string
}
