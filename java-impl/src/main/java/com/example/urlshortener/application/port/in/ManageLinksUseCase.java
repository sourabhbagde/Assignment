package com.example.urlshortener.application.port.in;

import com.example.urlshortener.domain.model.LinkStatistics;
import com.example.urlshortener.domain.model.ShortLink;

import java.time.Instant;
import java.util.List;

/** Primary port: read/list/deactivate links and read their analytics. */
public interface ManageLinksUseCase {

    ShortLink get(String code);

    Page list(int limit, int offset);

    void deactivate(String code);

    LinkStatistics statistics(String code, Instant from, Instant to, int topN);

    record Page(List<ShortLink> items, long total, int limit, int offset) {
    }
}
