package com.wuerthit.keycloak.authenticators.loginsync;

import java.util.Objects;

public record TokenHandle(String token, long generation) {

    public TokenHandle {
        Objects.requireNonNull(token, "token");
    }

    @Override
    public String toString() {
        return "TokenHandle[generation=" + generation + "]";
    }
}
