export type Side = 'BUY' | 'SELL'

export interface TradeRequest {
  symbol: string
  side: Side
  price: number
  quantity: number
}
