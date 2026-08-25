package com.wuerthit.keycloak.authenticators.loginsync;

import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.services.resources.LoginActionsService;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Per-request login synchronization, executed as a REQUIRED execution after the forms subflow.
 *
 * <p><strong>Every deliberate skip calls {@link AuthenticationFlowContext#success()}</strong>,
 * never the ATTEMPTED execution status. Verified from Keycloak 26.7.0 source: {@code
 * AuthenticationProcessor.isSuccessful()} (lines 780-784) returns true only for {@code
 * ExecutionStatus.SUCCESS}; {@code DefaultAuthenticationFlow} line 295 gates REQUIRED executions on
 * it and breaks the loop otherwise; {@code authenticateOnly()} line 1132 then throws {@code
 * AuthenticationFlowException}. Marking this execution ATTEMPTED would therefore fail the execution
 * and break every login in the realm. This is decision A0 and {@code R-10}.
 *
 * <p>Blocking HTTP inside {@link #authenticate(AuthenticationFlowContext)} is safe (decision A8):
 * Keycloak's JAX-RS application is annotated {@code @Blocking}, so this method runs on a Quarkus
 * worker thread rather than on an event-loop thread.
 *
 * <p>The synchronization is fail-closed (decision A9, portfolio invariant {@code P5}): the login
 * verdict is read from {@link SyncOutcome#blocksLogin()} and is never re-derived here, so a
 * receiver outage cannot let a user in unsynced.
 *
 * <p>This class is per-request, holds no lock and no mutable state; the transport singletons are
 * owned by {@link LoginSyncAuthenticatorFactory}.
 */
public class LoginSyncAuthenticator implements Authenticator {
    private static final Logger LOG = Logger.getLogger(LoginSyncAuthenticator.class);

    /** Event detail recorded on the failed execution. */
    private static final String ERROR_EVENT = "login_sync_failed";

    /** Message key defined in {@code theme-resources/messages/messages_en.properties}. */
    private static final String ERROR_MESSAGE_KEY = "loginSyncFailed";

    private final LoginSyncConfig config;
    private final SyncClient syncClient;

    /**
     * Creates a per-request authenticator.
     *
     * @param config the provider configuration, {@code null} when the provider is unconfigured
     * @param syncClient the shared client, {@code null} when the provider is unconfigured
     */
    public LoginSyncAuthenticator(LoginSyncConfig config, SyncClient syncClient) {
        this.config = config;
        this.syncClient = syncClient;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if (config == null || !config.configured() || syncClient == null) {
            // A5: the login must not be blocked because an operator has not configured this
            // provider yet, so the skip is a success rather than a failed execution.
            LOG.error("Login sync is unconfigured; skipping the login synchronization");
            context.success();
            return;
        }

        if (!LoginActionsService.AUTHENTICATE_PATH.equals(context.getFlowPath())) {
            // A6: exhaustive by construction. Only the authenticate path synchronizes; the
            // credential-reset path, the first-broker-login path, the post-broker-login path, the
            // account-creation path, a null path and any unknown future value all land here and
            // skip, so no wrong event_type can ever be emitted.
            LOG.debugf("Skipping login sync for flow path %s", context.getFlowPath());
            context.success();
            return;
        }

        UserModel user = context.getUser();
        AuthenticationSessionModel authenticationSession = context.getAuthenticationSession();
        ClientModel client =
                authenticationSession == null ? null : authenticationSession.getClient();
        if (user == null || client == null) {
            // Defence-in-depth ONLY, documented honestly: this is NOT the guard against a
            // misplaced execution. Because requiresUser() is true, Keycloak throws BEFORE this
            // method is invoked when no user is set, so an execution placed before the forms
            // subflow is REJECTED by Keycloak rather than silently skipped here.
            LOG.debug("Skipping login sync because no user or no client is bound to the flow");
            context.success();
            return;
        }

        // Collected inside the session and before the HTTP call, so no model is touched once the
        // request is in flight.
        List<String> groups =
                user.getGroupsStream().map(KeycloakModelUtils::buildGroupPath).toList();
        SyncPayload payload =
                SyncPayload.login(
                        client.getClientId(),
                        user.getUsername(),
                        user.getEmail(),
                        groups,
                        Instant.now());

        SyncOutcome outcome = sendOnce(payload);
        if (!outcome.blocksLogin()) {
            LOG.debugf("Login sync permitted the login with outcome %s", outcome);
            context.success();
            return;
        }

        LOG.warnf("Login sync blocked the login with outcome %s", outcome);
        context.failure(
                AuthenticationFlowError.INTERNAL_ERROR,
                context.form()
                        .setError(ERROR_MESSAGE_KEY)
                        .createErrorPage(Response.Status.INTERNAL_SERVER_ERROR),
                ERROR_EVENT,
                ERROR_MESSAGE_KEY);
    }

    /**
     * Performs the single synchronization attempt.
     *
     * <p>Exactly one call to {@link SyncClient#send(SyncPayload)} is made per login: retry was
     * removed by the LLD (section 3.7, recorded as {@code R-01}), so no failure is ever repeated
     * within one login. An absent outcome is treated as a transport failure, which blocks the
     * login.
     *
     * @param payload the body to deliver
     * @return the outcome that decides the login verdict, never {@code null}
     */
    private SyncOutcome sendOnce(SyncPayload payload) {
        try {
            SyncOutcome outcome = syncClient.send(payload);
            return outcome == null ? SyncOutcome.TRANSPORT_ERROR : outcome;
        } catch (SyncFailedException exception) {
            return exception.outcome() == null ? SyncOutcome.TRANSPORT_ERROR : exception.outcome();
        } catch (RuntimeException exception) {
            // The message is redacted by construction: it names no payload field.
            LOG.warn("Login sync failed with an unexpected error", exception);
            return SyncOutcome.TRANSPORT_ERROR;
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        // A3: this authenticator never issues a challenge, so an inbound action is a logic error.
        throw new IllegalStateException("login-sync does not issue an authentication challenge");
    }

    @Override
    public boolean requiresUser() {
        // A1: the execution must not run before a user exists.
        return true;
    }

    /**
     * Always {@code true} (decision A2).
     *
     * <p>Returning {@code false} while {@code isUserSetupAllowed()} is {@code false} makes Keycloak
     * raise CREDENTIAL_SETUP_REQUIRED, which would kill every login in the realm. There is no
     * per-user credential to set up for a synchronization, so there is nothing to report as
     * unconfigured here.
     */
    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

    @Override
    public void close() {}
}
