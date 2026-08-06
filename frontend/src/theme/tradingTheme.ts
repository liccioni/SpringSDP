import { colorSchemeDark, themeQuartz } from 'ag-grid-community'

// Bloomberg-terminal-lineage palette: graphite surface, amber for anything
// that just streamed in, green/red reserved for BUY/SELL semantics only.
// Kept in one place so PriceGrid and TradeBlotter read as the same instrument.
export const tradingTheme = themeQuartz.withPart(colorSchemeDark).withParams({
  backgroundColor: '#12171c',
  foregroundColor: '#e7ecf0',
  chromeBackgroundColor: '#0a0d10',
  borderColor: '#202830',
  accentColor: '#f5a623',

  headerBackgroundColor: '#0a0d10',
  headerTextColor: '#c7ae7c',
  headerFontFamily: ['IBM Plex Sans Condensed', 'system-ui', 'sans-serif'],
  headerFontWeight: 600,
  headerFontSize: 11,
  headerVerticalPaddingScale: 0.9,

  fontFamily: ['IBM Plex Sans', 'system-ui', 'sans-serif'],
  cellFontFamily: ['IBM Plex Mono', 'ui-monospace', 'monospace'],
  dataFontSize: 14,
  fontSize: 13,

  oddRowBackgroundColor: '#141a20',
  rowHoverColor: '#1b2229',
  selectedRowBackgroundColor: '#1b2229',
  rowBorder: { color: '#1a2027', style: 'solid', width: 1 },
  columnBorder: false,
  headerRowBorder: { color: '#202830' },
  wrapperBorder: false,
  wrapperBorderRadius: 0,
  borderRadius: 2,
  spacing: 8,

  valueChangeValueHighlightBackgroundColor: 'rgba(245, 166, 35, 0.32)',
})
