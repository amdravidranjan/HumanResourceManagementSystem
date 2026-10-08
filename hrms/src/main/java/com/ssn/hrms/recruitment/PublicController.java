package com.ssn.hrms.recruitment;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.component.shortener.UrlShortenerService;
import com.ssn.hrms.config.NodeOnly;

/** Endpoints reachable without login (short-link lookup, public job pages). */
@NodeOnly
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final UrlShortenerService shortener;

    public PublicController(UrlShortenerService shortener) {
        this.shortener = shortener;
    }

    @GetMapping(value = "/short/{code}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> resolve(@PathVariable String code) {
        String target = shortener.resolve(code);
        return target == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(target);
    }
}
