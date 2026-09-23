package com.ems.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "ORG_SERVICE_URL=http://localhost:8084")
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {}
}
