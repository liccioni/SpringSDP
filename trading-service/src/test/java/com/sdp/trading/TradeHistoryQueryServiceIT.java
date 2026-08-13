package com.sdp.trading;

import com.sdp.PostgresIntegrationTest;
import com.sdp.contracts.Side;
import com.sdp.contracts.TradeFilter;
import com.sdp.contracts.TradeHistoryPage;
import com.sdp.contracts.TradeHistoryQuery;
import com.sdp.contracts.TradeSort;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises TradeHistoryQueryService directly against a real Postgres
 * (issue #130): keyset pagination correctness across pages, one case per
 * filter type, and both sort directions with the (timestamp, id) tiebreaker
 * actually breaking a tie. Each test tags its own rows with a unique symbol
 * so assertions don't have to account for other tests' rows in this shared
 * table (see docs/testing.md's PostgresIntegrationTest gotcha).
 */
@SpringBootTest
@Tag("integration")
class TradeHistoryQueryServiceIT implements PostgresIntegrationTest {

    @Autowired
    private TradeHistoryQueryService queryService;

    @Autowired
    private TradeRepository tradeRepository;

    // symbol is VARCHAR(16) - truncate the tag so tag + "-" + a 6-char
    // random suffix always fits, while still keeping each test's rows
    // distinguishable from the shared table's other rows (see
    // docs/testing.md's PostgresIntegrationTest gotcha).
    private String uniqueSymbol(String tag) {
        String shortTag = tag.length() > 6 ? tag.substring(0, 6) : tag;
        return shortTag + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private void seed(String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp) {
        tradeRepository.save(new Trade(id, symbol, side, price, quantity, timestamp)).block(Duration.ofSeconds(5));
    }

    private TradeHistoryPage query(int pageSize, String cursor, TradeSort sort, TradeFilter... filters) {
        return queryService.query(new TradeHistoryQuery(pageSize, cursor, sort, List.of(filters))).block(Duration.ofSeconds(5));
    }

    @Test
    void keysetPaginationWalksAllRowsAcrossTwoPagesWithoutDuplicatesOrGaps() {
        String symbol = uniqueSymbol("KEYSET");
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            seed("keyset-" + i, symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), base.plusSeconds(i));
        }
        TradeFilter bySymbol = new TradeFilter("symbol", "equals", symbol, null);
        TradeSort oldestFirst = new TradeSort("timestamp", false);

        TradeHistoryPage firstPage = query(3, null, oldestFirst, bySymbol);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();
        assertThat(firstPage.rows()).extracting(com.sdp.contracts.Trade::id).containsExactly("keyset-0", "keyset-1", "keyset-2");

        TradeHistoryPage secondPage = query(3, firstPage.nextCursor(), oldestFirst, bySymbol);
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(secondPage.rows()).extracting(com.sdp.contracts.Trade::id).containsExactly("keyset-3", "keyset-4");
    }

    @Test
    void equalsFilterMatchesOnlyTheExactValue() {
        String symbol = uniqueSymbol("EQ");
        seed("eq-buy", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), Instant.parse("2026-01-01T00:00:00Z"));
        seed("eq-sell", symbol, Side.SELL, new BigDecimal("1.0"), new BigDecimal("1"), Instant.parse("2026-01-01T00:00:01Z"));

        TradeHistoryPage page = query(100, null, null,
                new TradeFilter("symbol", "equals", symbol, null), new TradeFilter("side", "equals", "SELL", null));

        assertThat(page.rows()).extracting(com.sdp.contracts.Trade::id).containsExactly("eq-sell");
    }

    @Test
    void containsFilterMatchesASubstringOfTheSymbol() {
        String symbol = uniqueSymbol("CONTAINS");
        seed("contains-match", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), Instant.parse("2026-01-01T00:00:00Z"));
        String substring = symbol.substring(2, 6);

        TradeHistoryPage page = query(100, null, null, new TradeFilter("symbol", "contains", substring, null));

        assertThat(page.rows()).extracting(com.sdp.contracts.Trade::id).contains("contains-match");
    }

    @Test
    void startsWithFilterMatchesAPrefixOfTheSymbol() {
        String symbol = uniqueSymbol("STARTSWITH");
        seed("startswith-match", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), Instant.parse("2026-01-01T00:00:00Z"));

        TradeHistoryPage page = query(100, null, null, new TradeFilter("symbol", "startsWith", symbol.substring(0, 6), null));

        assertThat(page.rows()).extracting(com.sdp.contracts.Trade::id).contains("startswith-match");
    }

    @Test
    void lessThanFilterExcludesValuesAtOrAboveTheThreshold() {
        String symbol = uniqueSymbol("LT");
        seed("lt-low", symbol, Side.BUY, new BigDecimal("1.0000"), new BigDecimal("1"), Instant.parse("2026-01-01T00:00:00Z"));
        seed("lt-high", symbol, Side.BUY, new BigDecimal("2.0000"), new BigDecimal("1"), Instant.parse("2026-01-01T00:00:01Z"));

        TradeHistoryPage page = query(100, null, null,
                new TradeFilter("symbol", "equals", symbol, null), new TradeFilter("price", "lessThan", "1.5000", null));

        assertThat(page.rows()).extracting(com.sdp.contracts.Trade::id).containsExactly("lt-low");
    }

    @Test
    void greaterThanFilterExcludesValuesAtOrBelowTheThreshold() {
        String symbol = uniqueSymbol("GT");
        seed("gt-low", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("100"), Instant.parse("2026-01-01T00:00:00Z"));
        seed("gt-high", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("900"), Instant.parse("2026-01-01T00:00:01Z"));

        TradeHistoryPage page = query(100, null, null,
                new TradeFilter("symbol", "equals", symbol, null), new TradeFilter("quantity", "greaterThan", "500", null));

        assertThat(page.rows()).extracting(com.sdp.contracts.Trade::id).containsExactly("gt-high");
    }

    @Test
    void inRangeFilterIncludesOnlyValuesWithinTheBounds() {
        String symbol = uniqueSymbol("RANGE");
        seed("range-below", symbol, Side.BUY, new BigDecimal("1.0000"), new BigDecimal("1"), Instant.parse("2026-01-01T00:00:00Z"));
        seed("range-within", symbol, Side.BUY, new BigDecimal("1.5000"), new BigDecimal("1"), Instant.parse("2026-01-01T00:00:01Z"));
        seed("range-above", symbol, Side.BUY, new BigDecimal("2.0000"), new BigDecimal("1"), Instant.parse("2026-01-01T00:00:02Z"));

        TradeHistoryPage page = query(100, null, null,
                new TradeFilter("symbol", "equals", symbol, null), new TradeFilter("price", "inRange", "1.2000", "1.8000"));

        assertThat(page.rows()).extracting(com.sdp.contracts.Trade::id).containsExactly("range-within");
    }

    @Test
    void ascendingSortBreaksATimestampTieByAscendingId() {
        String symbol = uniqueSymbol("TIE-ASC");
        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        seed(symbol + "-b", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), same);
        seed(symbol + "-a", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), same);
        seed(symbol + "-c", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), same);

        TradeHistoryPage page = query(100, null, new TradeSort("timestamp", false), new TradeFilter("symbol", "equals", symbol, null));

        assertThat(page.rows()).extracting(com.sdp.contracts.Trade::id)
                .containsExactly(symbol + "-a", symbol + "-b", symbol + "-c");
    }

    @Test
    void descendingSortBreaksATimestampTieByDescendingId() {
        String symbol = uniqueSymbol("TIE-DESC");
        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        seed(symbol + "-b", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), same);
        seed(symbol + "-a", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), same);
        seed(symbol + "-c", symbol, Side.BUY, new BigDecimal("1.0"), new BigDecimal("1"), same);

        TradeHistoryPage page = query(100, null, new TradeSort("timestamp", true), new TradeFilter("symbol", "equals", symbol, null));

        assertThat(page.rows()).extracting(com.sdp.contracts.Trade::id)
                .containsExactly(symbol + "-c", symbol + "-b", symbol + "-a");
    }
}
