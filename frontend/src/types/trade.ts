import type { Side } from './tradeRequest'

export interface Trade {
  id: string
  symbol: string
  side: Side
  price: number
  quantity: number
  timestamp: string
}
