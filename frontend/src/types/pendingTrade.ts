import type { Side } from './tradeRequest'

export interface PendingTrade {
  id: string
  symbol: string
  side: Side
  price: number
  quantity: number
  timestamp: string
}
