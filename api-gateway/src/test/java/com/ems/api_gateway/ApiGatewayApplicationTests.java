package com.ems.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "ALLOWED_ORIGINS=http://localhost:3000",
            "IDENTITY_SERVICE_URL=http://localhost:8081",
            "ORG_SERVICE_URL=http://localhost:8084",
            "EMPLOYEE_SERVICE_URL=http://localhost:8083",
            "app.security.jwt.jwks-uri=http://localhost:8081/.well-known/jwks.json"
        })
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {}
}
