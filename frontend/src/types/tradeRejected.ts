import type { Side } from './tradeRequest'

export interface TradeRejected {
  symbol: string
  side: Side
  price: number
  quantity: number
  reason: string
}
