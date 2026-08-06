import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// Vitest globals are off, so Testing Library's automatic afterEach(cleanup)
// registration never fires — without this, every render() in a test file
// stays mounted for the rest of that file, so later tests can pick up stale
// elements (or fixture values) from earlier ones.
afterEach(() => {
  cleanup()
})
