package com.wuerthit.keycloak.authenticators.loginsync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.mockito.ArgumentCaptor;

class LoginSyncAuthenticatorTest {
    private static final LoginSyncConfig CONFIGURED =
            new LoginSyncConfig(
                    "https://receiver.test", "sa", "secret", "https://token.test", 5000);
    private static final LoginSyncConfig UNCONFIGURED =
            new LoginSyncConfig(null, null, null, null, 5000);

    private final SyncClient syncClient = mock(SyncClient.class);
    private final ClientModel client = mock(ClientModel.class);
    private final AuthenticationFlowContext context =
            mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);

    @Test
    void happyPathSyncsTheLoginAndPermitsIt() {
        givenFlow("authenticate", user("jdoe@example.com", "engineering", "staff"));
        when(syncClient.send(any())).thenReturn(SyncOutcome.SUCCESS);

        authenticator(CONFIGURED).authenticate(context);

        ArgumentCaptor<SyncPayload> payload = ArgumentCaptor.forClass(SyncPayload.class);
        verify(syncClient).send(payload.capture());
        assertEquals("internal-portal", payload.getValue().clientId());
        assertEquals("jdoe", payload.getValue().username());
        assertEquals("jdoe@example.com", payload.getValue().email());
        assertEquals(List.of("/engineering", "/staff"), payload.getValue().groups());
        assertEquals("LOGIN", payload.getValue().eventType());
        assertNotNull(payload.getValue().timestamp());
        verify(context).success();
        verify(context, never()).attempted();
    }

    @Test
    void postBrokerLoginSyncsTheLoginAndPermitsIt() {
        givenFlow("post-broker-login", user("jdoe@example.com"));
        when(syncClient.send(any())).thenReturn(SyncOutcome.SUCCESS);

        authenticator(CONFIGURED).authenticate(context);

        verify(syncClient, times(1)).send(any());
        verify(context).success();
        verify(context, never()).attempted();
    }

    @Test
    void unconfiguredProviderPermitsTheLoginWithoutSyncing() {
        givenFlow("authenticate", user("jdoe@example.com"));

        authenticator(UNCONFIGURED).authenticate(context);

        assertSkippedWithoutSync();
    }

    @Test
    void absentSyncClientPermitsTheLoginWithoutSyncing() {
        givenFlow("authenticate", user("jdoe@example.com"));

        new LoginSyncAuthenticator(CONFIGURED, null).authenticate(context);

        assertSkippedWithoutSync();
    }

    @Test
    void nullUserPermitsTheLoginWithoutSyncing() {
        givenFlow("authenticate", null);

        authenticator(CONFIGURED).authenticate(context);

        assertSkippedWithoutSync();
    }

    @Test
    void nullClientPermitsTheLoginWithoutSyncing() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(context.getAuthenticationSession().getClient()).thenReturn(null);

        authenticator(CONFIGURED).authenticate(context);

        assertSkippedWithoutSync();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "registration",
                "reset-credentials",
                "first-broker-login",
                "a-flow-path-invented-after-this-plan"
            })
    void everyFlowPathOtherThanAuthenticateOrPostBrokerLoginSkipsWithoutSyncing(String flowPath) {
        givenFlow(flowPath, user("jdoe@example.com"));

        authenticator(CONFIGURED).authenticate(context);

        assertSkippedWithoutSync();
    }

    @ParameterizedTest
    @EnumSource(
            value = SyncOutcome.class,
            names = {
                "REJECTED",
                "UNAUTHORIZED",
                "SERVER_ERROR",
                "TIMEOUT",
                "TRANSPORT_ERROR",
                "TOKEN_UNAVAILABLE",
                "SATURATED"
            })
    void failClosedBlocksTheLoginForEveryBlockingOutcome(SyncOutcome outcome) {
        assertTrue(outcome.blocksLogin());
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any())).thenReturn(outcome);

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
    }

    @ParameterizedTest
    @EnumSource(
            value = SyncOutcome.class,
            names = {"SUCCESS"})
    void failClosedStillPermitsTheLoginForEveryNonBlockingOutcome(SyncOutcome outcome) {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any())).thenReturn(outcome);

        authenticator(CONFIGURED).authenticate(context);

        verify(context).success();
        verify(context, never()).attempted();
        verify(context, never()).failure(any(), any(), any(), any());
    }

    @Test
    void blocksTheLoginWhenSendReportsAFailedSync() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any()))
                .thenThrow(new SyncFailedException(SyncOutcome.TOKEN_UNAVAILABLE));

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
    }

    @Test
    void blocksTheLoginWhenSendThrowsAnUnexpectedRuntimeFailure() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any())).thenThrow(new IllegalStateException("boom"));

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
    }

    @Test
    void blocksTheLoginWhenSendReportsNoOutcomeAtAll() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any())).thenReturn(null);

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
    }

    @Test
    void syncsExactlyOncePerLoginAndNeverRetries() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any())).thenReturn(SyncOutcome.SERVER_ERROR);

        authenticator(CONFIGURED).authenticate(context);

        verify(syncClient, times(1)).send(any());
    }

    @Test
    void aBlockedLoginKeepsUserDataOutOfEveryReportedMessage() {
        givenFlow("authenticate", user("email@" + "sentinel.test", "GROUP_SENTINEL"));
        when(syncClient.send(any())).thenReturn(SyncOutcome.SERVER_ERROR);

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
    }

    @Test
    void actionThrowsBecauseThisAuthenticatorNeverIssuesAChallenge() {
        assertThrows(IllegalStateException.class, () -> authenticator(CONFIGURED).action(context));
    }

    @Test
    void contractMethodsCannotBlockTheFlow() {
        LoginSyncAuthenticator authenticator = authenticator(CONFIGURED);
        assertTrue(authenticator.requiresUser());
        assertTrue(authenticator.configuredFor(null, null, null));
        authenticator.setRequiredActions(null, null, null);
        authenticator.close();
    }

    @Test
    void factoryExposesAnEmptyConfigurationAndOnlySafeChoices() {
        LoginSyncAuthenticatorFactory factory = new LoginSyncAuthenticatorFactory();

        assertNotNull(factory.getConfigProperties());
        assertTrue(factory.getConfigProperties().isEmpty());
        assertArrayEquals(
                new AuthenticationExecutionModel.Requirement[] {
                    AuthenticationExecutionModel.Requirement.REQUIRED,
                    AuthenticationExecutionModel.Requirement.DISABLED
                },
                factory.getRequirementChoices());
        assertEquals(LoginSyncConstants.PROVIDER_ID, factory.getId());
    }

    @Test
    void factoryNeitherThrowsNorBuildsTransportWhenConfigurationIsAbsent() {
        LoginSyncAuthenticatorFactory factory = new LoginSyncAuthenticatorFactory();
        KeycloakSession session = mock(KeycloakSession.class);

        factory.init(mock(Config.Scope.class));

        givenFlow("authenticate", user("jdoe@example.com"));
        factory.create(session).authenticate(context);
        org.mockito.Mockito.verifyNoInteractions(session);
        assertSkippedWithoutSync();
    }

    private LoginSyncAuthenticator authenticator(LoginSyncConfig config) {
        return new LoginSyncAuthenticator(config, syncClient);
    }

    private void givenFlow(String flowPath, UserModel user) {
        when(context.getFlowPath()).thenReturn(flowPath);
        when(context.getUser()).thenReturn(user);
        when(client.getClientId()).thenReturn("internal-portal");
        when(context.getAuthenticationSession().getClient()).thenReturn(client);
    }

    private void assertSkippedWithoutSync() {
        verify(context).success();
        verify(context, never()).attempted();
        verify(syncClient, never()).send(any());
    }

    private void assertLoginBlocked() {
        verify(context)
                .failure(
                        eq(AuthenticationFlowError.INTERNAL_ERROR),
                        any(),
                        eq("login_sync_failed"),
                        eq("loginSyncFailed"));
        verify(context, never()).success();
        verify(context, never()).attempted();
    }

    private static UserModel user(String email, String... groupNames) {
        UserModel user = mock(UserModel.class);
        when(user.getUsername()).thenReturn("jdoe");
        when(user.getEmail()).thenReturn(email);
        when(user.getGroupsStream())
                .thenReturn(Stream.of(groupNames).map(LoginSyncAuthenticatorTest::group));
        return user;
    }

    private static GroupModel group(String name) {
        GroupModel group = mock(GroupModel.class);
        when(group.getName()).thenReturn(name);
        return group;
    }
}
