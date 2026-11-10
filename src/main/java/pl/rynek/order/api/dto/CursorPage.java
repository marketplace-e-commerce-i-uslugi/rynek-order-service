package pl.rynek.order.api.dto;

import java.util.List;

/** Paginacja kursorowa (§1): {@code nextCursor} jest pusty na ostatniej stronie. */
public record CursorPage<T>(List<T> items, String nextCursor) {
}
