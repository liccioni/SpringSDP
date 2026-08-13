package com.sdp.trading;

import com.sdp.contracts.Side;
import com.sdp.contracts.TradeFilter;
import com.sdp.contracts.TradeHistoryPage;
import com.sdp.contracts.TradeHistoryQuery;
import com.sdp.contracts.TradeSort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import io.r2dbc.spi.Row;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import tools.jackson.databind.ObjectMapper;

/**
 * Answers GET_TRADE_HISTORY with a cursor-paginated, filterable, sortable
 * page of trade history (issue #130) - replaces the prior unbounded "load
 * the whole trades table" behavior (TradeRepository.findAllByOrderByTimestampAsc).
 * Backed directly by DatabaseClient rather than TradeRepository, which can't
 * express a dynamic optional filter/sort/keyset predicate. sortColumn and
 * every filter column are restricted to SORTABLE_COLUMNS, an allow-list, so
 * no client-supplied column name is ever interpolated into SQL - only these
 * fixed identifiers, with every value bound as a parameter.
 */
@Service
public class TradeHistoryQueryService {

    private static final Map<String, Function<String, Object>> SORTABLE_COLUMNS = Map.of(
            "symbol", (Function<String, Object>) value -> value,
            "side", (Function<String, Object>) value -> value,
            "price", (Function<String, Object>) BigDecimal::new,
            "quantity", (Function<String, Object>) BigDecimal::new,
            "timestamp", (Function<String, Object>) Instant::parse);

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public TradeHistoryQueryService(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    public Mono<TradeHistoryPage> query(TradeHistoryQuery query) {
        TradeSort sort = query.sort();
        String sortColumn = sort != null ? requireAllowedColumn(sort.column()) : "timestamp";
        boolean descending = sort != null ? sort.descending() : true;
        Cursor cursor = decodeCursor(query.cursor());

        StringBuilder sql = new StringBuilder("SELECT id, symbol, side, price, quantity, timestamp FROM trades WHERE 1=1");
        Map<String, Object> bindings = new LinkedHashMap<>();

        appendFilters(sql, bindings, query.filters());
        appendKeysetPredicate(sql, bindings, sortColumn, descending, cursor);
        appendOrderBy(sql, sortColumn, descending);

        int fetchSize = query.pageSize() + 1;
        sql.append(" LIMIT :fetchSize");
        bindings.put("fetchSize", fetchSize);

        return execute(sql.toString(), bindings)
                .collectList()
                .map(rows -> toPage(rows, query.pageSize(), sortColumn, descending));
    }

    private void appendFilters(StringBuilder sql, Map<String, Object> bindings, List<TradeFilter> filters) {
        if (filters == null) {
            return;
        }
        int i = 0;
        for (TradeFilter filter : filters) {
            String column = requireAllowedColumn(filter.column());
            String param = "filter" + i++;
            sql.append(" AND ").append(filterPredicate(column, filter.type(), param));
            bindFilterValue(bindings, param, column, filter);
        }
    }

    private String filterPredicate(String column, String type, String param) {
        return switch (type) {
            case "equals" -> column + " = :" + param;
            case "contains" -> "CAST(" + column + " AS TEXT) ILIKE :" + param;
            case "startsWith" -> "CAST(" + column + " AS TEXT) ILIKE :" + param;
            case "lessThan" -> column + " < :" + param;
            case "greaterThan" -> column + " > :" + param;
            case "inRange" -> column + " BETWEEN :" + param + " AND :" + param + "To";
            default -> throw new IllegalArgumentException("unsupported filter type: " + type);
        };
    }

    private void bindFilterValue(Map<String, Object> bindings, String param, String column, TradeFilter filter) {
        Function<String, Object> convert = SORTABLE_COLUMNS.get(column);
        switch (filter.type()) {
            case "contains" -> bindings.put(param, "%" + filter.value() + "%");
            case "startsWith" -> bindings.put(param, filter.value() + "%");
            case "inRange" -> {
                bindings.put(param, convert.apply(filter.value()));
                bindings.put(param + "To", convert.apply(filter.valueTo()));
            }
            default -> bindings.put(param, convert.apply(filter.value()));
        }
    }

    private void appendKeysetPredicate(
            StringBuilder sql, Map<String, Object> bindings, String sortColumn, boolean descending, Cursor cursor) {
        if (cursor == null) {
            return;
        }
        String operator = descending ? "<" : ">";
        if (sortColumn.equals("timestamp")) {
            sql.append(" AND (timestamp, id) ").append(operator).append(" (:cursorTimestamp, :cursorId)");
        } else {
            sql.append(" AND (").append(sortColumn).append(", timestamp, id) ")
                    .append(operator)
                    .append(" (:cursorSortValue, :cursorTimestamp, :cursorId)");
            bindings.put("cursorSortValue", SORTABLE_COLUMNS.get(sortColumn).apply(cursor.sv()));
        }
        bindings.put("cursorTimestamp", Instant.parse(cursor.ts()));
        bindings.put("cursorId", cursor.id());
    }

    private void appendOrderBy(StringBuilder sql, String sortColumn, boolean descending) {
        String direction = descending ? "DESC" : "ASC";
        sql.append(" ORDER BY ");
        if (!sortColumn.equals("timestamp")) {
            sql.append(sortColumn).append(' ').append(direction).append(", ");
        }
        sql.append("timestamp ").append(direction).append(", id ").append(direction);
    }

    private Flux<com.sdp.contracts.Trade> execute(String sql, Map<String, Object> bindings) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql);
        for (Map.Entry<String, Object> binding : bindings.entrySet()) {
            spec = spec.bind(binding.getKey(), binding.getValue());
        }
        return spec.map((row, metadata) -> toTrade(row)).all();
    }

    private com.sdp.contracts.Trade toTrade(Row row) {
        return new com.sdp.contracts.Trade(
                row.get("id", String.class),
                row.get("symbol", String.class),
                Side.valueOf(row.get("side", String.class)),
                row.get("price", BigDecimal.class),
                row.get("quantity", BigDecimal.class),
                row.get("timestamp", Instant.class));
    }

    private TradeHistoryPage toPage(List<com.sdp.contracts.Trade> rows, int pageSize, String sortColumn, boolean descending) {
        boolean hasMore = rows.size() > pageSize;
        List<com.sdp.contracts.Trade> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1), sortColumn) : null;
        return new TradeHistoryPage(page, nextCursor, hasMore);
    }

    private String encodeCursor(com.sdp.contracts.Trade last, String sortColumn) {
        String sv = sortColumn.equals("timestamp") ? null : sortValue(last, sortColumn);
        byte[] json = objectMapper.writeValueAsBytes(new Cursor(last.timestamp().toString(), last.id(), sv));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    private String sortValue(com.sdp.contracts.Trade trade, String column) {
        return switch (column) {
            case "symbol" -> trade.symbol();
            case "side" -> trade.side().name();
            case "price" -> trade.price().toString();
            case "quantity" -> trade.quantity().toString();
            default -> throw new IllegalStateException("unexpected sort column: " + column);
        };
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        return objectMapper.readValue(Base64.getUrlDecoder().decode(cursor), Cursor.class);
    }

    private String requireAllowedColumn(String column) {
        if (!SORTABLE_COLUMNS.containsKey(column)) {
            throw new IllegalArgumentException("unsupported column: " + column);
        }
        return column;
    }

    private record Cursor(String ts, String id, String sv) {
    }
}
