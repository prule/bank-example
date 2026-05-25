package com.bank.core.infrastructure.web;

import com.bank.core.api.IndexApi;
import com.bank.core.dto.IndexResponse;
import com.bank.core.dto.IndexResponseLinks;
import com.bank.core.dto.Link;
import com.bank.core.infrastructure.web.account.AccountController;
import com.bank.core.infrastructure.web.transfer.TransferController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
public class IndexController implements IndexApi {

    private final LinkFactory links;

    public IndexController(LinkFactory links) {
        this.links = links;
    }

    @Override
    public ResponseEntity<IndexResponse> getIndex() {
        Link self = links.to(methodOn(IndexController.class).getIndex());
        Link accounts = links.templated("/api/v1/accounts/{accountNumber}");
        Link transfers = links.to(methodOn(TransferController.class).createTransfer(null));
        Link openapi = links.to(methodOn(OpenApiController.class).getOpenApiDocument());
        IndexResponseLinks body = new IndexResponseLinks(self, accounts, transfers, openapi);
        return ResponseEntity.ok(new IndexResponse(body));
    }
}
