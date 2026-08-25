package com.wuerthit.keycloak.authenticators.loginsync;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

public class LoginSyncAuthenticator implements Authenticator {
    private final LoginSyncConfig config;
    private final SyncClient syncClient;

    public LoginSyncAuthenticator(LoginSyncConfig config, SyncClient syncClient) {
        this.config = config;
        this.syncClient = syncClient;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        // Full outcome mapping arrives with plan 0005 todo 2.
        context.success();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        throw new IllegalStateException("login-sync does not issue an authentication challenge");
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

    @Override
    public void close() {}
}
