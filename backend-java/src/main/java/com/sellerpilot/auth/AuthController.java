package com.sellerpilot.auth;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/auth","/api/auth"})
public class AuthController {
    public record Register(@NotBlank @Size(min=3,max=50) @Pattern(regexp="^[A-Za-z0-9_-]+$")String username,
                           @NotBlank @Size(min=8,max=128)String password){}
    public record Login(@NotBlank @Size(max=50)String username,@NotBlank @Size(max=128)String password){}
    private final AuthService service;
    public AuthController(AuthService service){this.service=service;}
    @PostMapping("/register")@ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(@Valid @RequestBody Register body){return service.register(body.username,body.password);}
    @PostMapping("/login")public Map<String,Object> login(@Valid @RequestBody Login body){return service.login(body.username,body.password);}
    @GetMapping("/me")public Map<String,Object> me(@AuthenticationPrincipal Long user){return service.me(user);}
}
