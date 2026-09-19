package com.mindbridge;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProfileController {
    @GetMapping("/csrf") public Object csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/profile") public Object profile(Authentication auth) {
        return Map.of("username",auth.getName(),"role",auth.getAuthorities().stream()
            .anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"))?"ADMIN":"STUDENT");
    }
}
