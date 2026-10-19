package com.pidea.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    public static <E, T> PageResponse<T> of(Page<E> p, Function<E, T> mapper) {
        return new PageResponse<>(p.getContent().stream().map(mapper).toList(),
            p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    /** Clamps client-supplied paging so no request can ask for an unbounded result set. */
    public static org.springframework.data.domain.PageRequest pageRequest(int page, int size,
            org.springframework.data.domain.Sort sort) {
        return org.springframework.data.domain.PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), sort);
    }
}
