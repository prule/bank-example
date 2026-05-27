package com.bank.core.infrastructure.web;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("dev")
public class SwaggerUiController {

    @GetMapping("/swagger-ui.html")
    public String redirectToSwaggerUi() {
        return "redirect:/webjars/swagger-ui/index.html?url=/v3/api-docs";
    }
}
