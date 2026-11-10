package pl.rynek.order.api.dto;

import java.time.Instant;

import pl.rynek.order.orders.OrderStatus;

public record TimelineEntry(OrderStatus status, Instant at) {
}
