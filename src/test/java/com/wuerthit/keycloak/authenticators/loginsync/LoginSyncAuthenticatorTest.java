package com.wuerthit.keycloak.authenticators.loginsync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
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
    private final RealmModel realm = mock(RealmModel.class);
    private final AuthenticationFlowContext context =
            mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);

    @Test
    void happyPathSyncsTheLoginAndPermitsIt() {
        givenFlow("authenticate", user("jdoe@example.com", "engineering", "staff"));
        when(syncClient.send(any(), eq("permissionsync:glpi"))).thenReturn(SyncOutcome.SUCCESS);

        authenticator(CONFIGURED).authenticate(context);

        ArgumentCaptor<SyncPayload> payload = ArgumentCaptor.forClass(SyncPayload.class);
        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        verify(syncClient).send(payload.capture(), scope.capture());
        assertEquals("jdoe", payload.getValue().username());
        assertEquals(List.of("/engineering", "/staff"), payload.getValue().groups());
        assertEquals("LOGIN", payload.getValue().eventType());
        assertEquals("permissionsync:glpi", scope.getValue());
        verify(context).success();
        verify(context, never()).attempted();
    }

    @Test
    void postBrokerLoginSyncsTheLoginAndPermitsIt() {
        givenFlow("post-broker-login", user("jdoe@example.com"));
        when(syncClient.send(any(), eq("permissionsync:glpi"))).thenReturn(SyncOutcome.SUCCESS);

        authenticator(CONFIGURED).authenticate(context);

        verify(syncClient, times(1)).send(any(), eq("permissionsync:glpi"));
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

    @Test
    void nullClientIdPermitsTheLoginWithoutSyncing() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(client.getClientId()).thenReturn(null);

        authenticator(CONFIGURED).authenticate(context);

        assertSkippedWithoutSync();
        verifyNoInteractions(syncClient);
    }

    @Test
    void blankClientIdPermitsTheLoginWithoutSyncing() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(client.getClientId()).thenReturn(" ");

        authenticator(CONFIGURED).authenticate(context);

        assertSkippedWithoutSync();
        verifyNoInteractions(syncClient);
    }

    @Test
    void scopeIsDerivedFromTheLoginClientId() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(client.getClientId()).thenReturn("grafana");
        givenClientScopes("permissionsync:grafana");
        when(syncClient.send(any(), eq("permissionsync:grafana"))).thenReturn(SyncOutcome.SUCCESS);

        authenticator(CONFIGURED).authenticate(context);

        verify(syncClient).send(any(), eq("permissionsync:grafana"));
        verify(context).success();
    }

    @Test
    void emptyScopeRequestedWhenNoMatchingClientScopeExistsInRealm() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(realm.getClientScopesStream()).thenReturn(Stream.empty());
        when(syncClient.send(any(), isNull())).thenReturn(SyncOutcome.SUCCESS);

        authenticator(CONFIGURED).authenticate(context);

        verify(syncClient).send(any(), isNull());
        verify(context).success();
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
        when(syncClient.send(any(), eq("permissionsync:glpi"))).thenReturn(outcome);

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
    }

    @ParameterizedTest
    @EnumSource(
            value = SyncOutcome.class,
            names = {"SUCCESS"})
    void failClosedStillPermitsTheLoginForEveryNonBlockingOutcome(SyncOutcome outcome) {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any(), eq("permissionsync:glpi"))).thenReturn(outcome);

        authenticator(CONFIGURED).authenticate(context);

        verify(context).success();
        verify(context, never()).attempted();
        verify(context, never()).failure(any(), any(), any(), any());
    }

    @Test
    void blocksTheLoginWhenSendReportsAFailedSync() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any(), eq("permissionsync:glpi")))
                .thenThrow(new SyncFailedException(SyncOutcome.TOKEN_UNAVAILABLE));

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
    }

    @Test
    void blocksTheLoginWhenSendThrowsAnUnexpectedRuntimeFailure() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any(), eq("permissionsync:glpi")))
                .thenThrow(new IllegalStateException("boom"));

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
    }

    @Test
    void blocksTheLoginWhenSendReportsNoOutcomeAtAll() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any(), eq("permissionsync:glpi"))).thenReturn(null);

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
    }

    @Test
    void syncsExactlyOncePerLoginAndNeverRetries() {
        givenFlow("authenticate", user("jdoe@example.com"));
        when(syncClient.send(any(), eq("permissionsync:glpi")))
                .thenReturn(SyncOutcome.SERVER_ERROR);

        authenticator(CONFIGURED).authenticate(context);

        verify(syncClient, times(1)).send(any(), eq("permissionsync:glpi"));
    }

    @Test
    void aBlockedLoginKeepsUserDataOutOfEveryReportedMessage() {
        givenFlow("authenticate", user("email@" + "sentinel.test", "GROUP_SENTINEL"));
        when(syncClient.send(any(), eq("permissionsync:glpi")))
                .thenReturn(SyncOutcome.SERVER_ERROR);

        // The form mock echoes the message key the authenticator sets back into the error
        // page entity, so a future edit that renders user data into the error page surfaces
        // in the sentinel assertions below instead of passing silently.
        LoginFormsProvider form = mock(LoginFormsProvider.class);
        AtomicReference<String> renderedKey = new AtomicReference<>();
        when(form.setError(anyString()))
                .thenAnswer(
                        invocation -> {
                            renderedKey.set(invocation.getArgument(0));
                            return form;
                        });
        when(form.createErrorPage(any()))
                .thenAnswer(
                        invocation -> {
                            Response response = mock(Response.class);
                            when(response.getEntity()).thenReturn(renderedKey.get());
                            return response;
                        });
        when(context.form()).thenReturn(form);

        authenticator(CONFIGURED).authenticate(context);

        assertLoginBlocked();
        ArgumentCaptor<AuthenticationFlowError> error =
                ArgumentCaptor.forClass(AuthenticationFlowError.class);
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        ArgumentCaptor<String> eventDetail = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageKey = ArgumentCaptor.forClass(String.class);
        verify(context)
                .failure(
                        error.capture(),
                        response.capture(),
                        eventDetail.capture(),
                        messageKey.capture());

        assertEquals(AuthenticationFlowError.INTERNAL_ERROR, error.getValue());
        assertEquals("login_sync_failed", eventDetail.getValue());
        assertEquals("loginSyncFailed", messageKey.getValue());
        assertEquals("loginSyncFailed", renderedKey.get());
        for (String userData : List.of("jdoe", "sentinel.test", "GROUP_SENTINEL")) {
            for (String reported :
                    List.of(
                            String.valueOf(response.getValue().getEntity()),
                            eventDetail.getValue(),
                            messageKey.getValue(),
                            error.getValue().name())) {
                assertFalse(
                        reported.contains(userData),
                        "user data " + userData + " leaked into reported message " + reported);
            }
        }
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
        when(context.getAuthenticationSession().getClient()).thenReturn(client);
        when(context.getRealm()).thenReturn(realm);
        when(client.getClientId()).thenReturn("glpi");
        givenClientScopes("permissionsync:glpi");
    }

    private void givenClientScopes(String... names) {
        when(realm.getClientScopesStream())
                .thenReturn(Stream.of(names).map(LoginSyncAuthenticatorTest::clientScope));
    }

    private void assertSkippedWithoutSync() {
        verify(context).success();
        verify(context, never()).attempted();
        verify(context, never()).failure(any(), any(), any(), any());
        verify(syncClient, never()).send(any(), any());
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

    private static ClientScopeModel clientScope(String name) {
        ClientScopeModel clientScope = mock(ClientScopeModel.class);
        when(clientScope.getName()).thenReturn(name);
        return clientScope;
    }
}
