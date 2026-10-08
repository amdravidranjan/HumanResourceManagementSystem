package com.ssn.hrms.component.shortener;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Durable copy of a short link (Redis holds the hot copy with TTL). Id = short code. */
@Document("short_urls")
public class ShortUrl {

    @Id
    public String id;
    public String target;
    public long createdAt;
    public Long expiresAt;
}
