package com.medix.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    @Test void issuesVerifiesAndRejectsTampering(){
        JwtService jwt=new JwtService("a-test-secret-that-is-long-enough-123",3600);
        String token=jwt.issue(new AppPrincipal(UUID.randomUUID(),"alice","Alice",Set.of("USER")));
        assertThat(jwt.verify(token)).isEqualTo("alice");
        assertThat(jwt.verify(token+"x")).isNull();
    }
}
