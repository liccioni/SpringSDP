import type { Trade } from './trade'

// Mirrors contracts.TradeSort (issue #130).
export interface TradeSort {
  column: string
  descending: boolean
}

// Mirrors contracts.TradeFilter (issue #130).
export interface TradeFilter {
  column: string
  type: string
  value: string
  valueTo: string | null
}

// Mirrors contracts.TradeHistoryQuery (issue #130) - the GET_TRADE_HISTORY
// request payload. cursor is null for the first page.
export interface TradeHistoryQuery {
  pageSize: number
  cursor: string | null
  sort: TradeSort | null
  filters: TradeFilter[] | null
}

// Mirrors contracts.TradeHistoryPage (issue #130) - the TRADE_HISTORY reply
// payload. nextCursor is null once hasMore is false.
export interface TradeHistoryPage {
  rows: Trade[]
  nextCursor: string | null
  hasMore: boolean
}
