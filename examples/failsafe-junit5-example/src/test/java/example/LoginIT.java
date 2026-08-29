package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoginIT {
    @Test
    void validLogin() {
        assertEquals("true", System.getProperty("scenariomesh.fixture.argline"));
        assertEquals("EN", System.getProperty("scenariomesh.fixture.language"));
        IntegrationRecorder.record("LoginIT#validLogin");
    }
}
