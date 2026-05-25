package com.bank.core.infrastructure.web;

import com.bank.core.api.IndexApi;
import com.bank.core.dto.IndexResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IndexController implements IndexApi {

    private final IndexModelAssembler assembler;

    public IndexController(IndexModelAssembler assembler) {
        this.assembler = assembler;
    }

    @Override
    public ResponseEntity<IndexResponse> getIndex() {
        return ResponseEntity.ok(assembler.toModel());
    }
}
