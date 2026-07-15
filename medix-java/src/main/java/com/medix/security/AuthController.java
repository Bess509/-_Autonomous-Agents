package com.medix.security;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    public record LoginRequest(String username,String password){}
    private final IdentityService identities; private final JwtService jwt;
    private final boolean production;
    public AuthController(IdentityService identities,JwtService jwt,@Value("${medix.security.production:false}") boolean production){this.identities=identities;this.jwt=jwt;this.production=production;}
    @PostMapping("/login") public Map<String,Object> login(@RequestBody LoginRequest req,HttpServletResponse response){
        if(req==null)throw new BadCredentialsException();
        AppPrincipal p=identities.authenticate(req.username(),req.password()).orElseThrow(BadCredentialsException::new);
        String token=jwt.issue(p); response.addHeader("Set-Cookie",cookie(token,Duration.ofHours(8)).toString()); return view(p);
    }
    @GetMapping("/me") public Map<String,Object> me(Authentication auth){return view((AppPrincipal)auth.getPrincipal());}
    @PostMapping("/logout") public Map<String,Boolean> logout(HttpServletResponse response){response.addHeader("Set-Cookie",cookie("",Duration.ZERO).toString());return Map.of("loggedOut",true);}
    private ResponseCookie cookie(String value,Duration age){return ResponseCookie.from("MEDIX_SESSION",value).httpOnly(true).secure(production).sameSite("Strict").path("/").maxAge(age).build();}
    private Map<String,Object> view(AppPrincipal p){return Map.of("id",p.id(),"username",p.username(),"displayName",p.displayName(),"roles",p.roles());}
    @ResponseStatus(org.springframework.http.HttpStatus.UNAUTHORIZED) static class BadCredentialsException extends RuntimeException{}
}
