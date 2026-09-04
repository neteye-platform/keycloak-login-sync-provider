package com.wuerthit.keycloak.authenticators.loginsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.truststore.TruststoreProvider;

public class LoginSyncAuthenticatorFactory implements AuthenticatorFactory {
    private static final Logger LOG = Logger.getLogger(LoginSyncAuthenticatorFactory.class);
    private static final String TRUSTSTORE_FALLBACK_MESSAGE =
            "Keycloak truststore unavailable; using JVM default trust material; private/internal "
                    + "CAs will not be trusted";

    private final Object clientLock = new Object();

    private volatile LoginSyncConfig config;
    private volatile SyncClient syncClient;

    @Override
    public Authenticator create(KeycloakSession session) {
        LoginSyncConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.configured()) {
            return new LoginSyncAuthenticator(currentConfig, null);
        }

        SyncClient currentClient = syncClient;
        if (currentClient == null) {
            synchronized (clientLock) {
                currentClient = syncClient;
                if (currentClient == null) {
                    SSLContext sslContext = truststoreSslContext(session);
                    currentClient = new SyncClient(currentConfig, new ObjectMapper(), sslContext);
                    syncClient = currentClient;
                }
            }
        }
        return new LoginSyncAuthenticator(currentConfig, currentClient);
    }

    @Override
    public void init(Config.Scope scope) {
        LoginSyncConfig loadedConfig = LoginSyncConfig.from(scope);
        config = loadedConfig;
        if (!loadedConfig.configured()) {
            LOG.errorf(
                    "Login sync is unconfigured; missing configuration keys: %s",
                    missingConfigurationKeys(loadedConfig));
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Transport construction is deferred to the first create(session): the truststore-derived
        // SSLContext requires a KeycloakSession, which postInit does not have.
    }

    @Override
    public void close() {
        synchronized (clientLock) {
            if (syncClient != null) {
                syncClient.close();
                syncClient = null;
            }
        }
    }

    @Override
    public String getId() {
        return LoginSyncConstants.PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "LOGIN synchronization";
    }

    @Override
    public String getReferenceCategory() {
        return "LOGIN synchronization";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[] {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED
        };
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Synchronizes an authenticated user during LOGIN only.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return Collections.emptyList();
    }

    /**
     * Derives receiver trust from Keycloak per T7 and R-09.
     *
     * <p>If Keycloak trust material is unavailable, the nullable return value directs {@link
     * SyncClient} to use the JVM default without weakening certificate or hostname verification.
     */
    private SSLContext truststoreSslContext(KeycloakSession session) {
        TruststoreProvider provider = session.getProvider(TruststoreProvider.class);
        KeyStore truststore = provider == null ? null : provider.getTruststore();
        if (truststore == null) {
            LOG.warn(TRUSTSTORE_FALLBACK_MESSAGE);
            return null;
        }

        try {
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(truststore);
            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (trustManagers == null || trustManagers.length == 0) {
                LOG.warn(TRUSTSTORE_FALLBACK_MESSAGE);
                return null;
            }

            // "TLS" negotiates the highest protocol the JVM offers, so a future TLS version is
            // adopted by upgrading the JVM alone. The weak-ssl-context rule accepts only the
            // literals "TLSv1.2" and "TLSv1.3", which both PIN that ceiling; the "TLSv1.2" it
            // recommends measurably enables [TLSv1.2] alone and drops TLS 1.3. Suppressed for
            // this line only, so the rule still guards every other call site.
            // nosemgrep: java.lang.security.audit.weak-ssl-context.weak-ssl-context
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, null);
            return sslContext;
        } catch (GeneralSecurityException exception) {
            LOG.warn(TRUSTSTORE_FALLBACK_MESSAGE, exception);
            return null;
        }
    }

    private static String missingConfigurationKeys(LoginSyncConfig currentConfig) {
        StringJoiner missingKeys = new StringJoiner(", ");
        addIfMissing(
                missingKeys,
                LoginSyncConstants.CONFIG_SERVICE_ENDPOINT,
                currentConfig.serviceEndpoint());
        addIfMissing(
                missingKeys, LoginSyncConstants.CONFIG_SA_CLIENT_ID, currentConfig.saClientId());
        addIfMissing(
                missingKeys,
                LoginSyncConstants.CONFIG_SA_CLIENT_SECRET,
                currentConfig.saClientSecret());
        addIfMissing(
                missingKeys,
                LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT,
                currentConfig.saTokenEndpoint());
        return missingKeys.toString();
    }

    private static void addIfMissing(StringJoiner missingKeys, String key, String value) {
        if (value == null || value.isBlank()) {
            missingKeys.add(key);
        }
    }
}
