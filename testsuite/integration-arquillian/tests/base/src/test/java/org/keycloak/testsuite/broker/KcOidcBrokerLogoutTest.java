package org.keycloak.testsuite.broker;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.TokenVerifier;
import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.VerificationException;
import org.keycloak.protocol.oidc.OIDCConfigAttributes;
import org.keycloak.representations.IDToken;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.util.CookieHelper;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.updaters.ClientAttributeUpdater;
import org.keycloak.testsuite.util.AccountHelper;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.WaitUtils;

import static org.junit.Assert.assertEquals;
import static org.keycloak.testsuite.broker.BrokerTestConstants.REALM_CONS_NAME;
import static org.keycloak.testsuite.broker.BrokerTestConstants.REALM_PROV_NAME;
import static org.keycloak.testsuite.broker.BrokerTestTools.getConsumerRoot;
import static org.keycloak.testsuite.broker.BrokerTestTools.waitForPage;

import java.util.HashMap;
import java.util.Map;

public class KcOidcBrokerLogoutTest extends AbstractKcOidcBrokerLogoutTest {

    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Override
    protected BrokerConfiguration getBrokerConfiguration() {
        return KcOidcBrokerConfiguration.INSTANCE;
    }

    @Test
    public void logoutWithoutInitiatingIdpLogsOutOfIdp() {
        logInAsUserInIDPForFirstTime();
        appPage.assertCurrent();

        AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
        AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

        oauth.clientId("account");
        oauth.redirectUri(getConsumerRoot() + "/auth/realms/" + REALM_PROV_NAME + "/account");
        loginPage.open(REALM_PROV_NAME);

        waitForPage(driver, "sign in to provider", true);
    }

    @Test
    public void logoutWithActualIdpAsInitiatingIdpDoesNotLogOutOfIdp() {
        logInAsUserInIDPForFirstTime();
        appPage.assertCurrent();

        AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
        AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

        oauth.clientId("broker-app");
        loginPage.open(bc.providerRealmName());
        waitForPage(driver, "sign in to provider", true);
    }

    @Test
    public void logoutWithOtherIdpAsInitiatinIdpLogsOutOfIdp() {
        logInAsUserInIDPForFirstTime();
        appPage.assertCurrent();

        AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
        AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

        oauth.clientId("account");
        oauth.redirectUri(getConsumerRoot() + "/auth/realms/" + REALM_PROV_NAME + "/account");
        loginPage.open(REALM_PROV_NAME);

        waitForPage(driver, "sign in to provider", true);
    }

    @Test
    public void logoutAfterBrowserRestart() {
        driver.navigate().to(getLoginUrl(getConsumerRoot(), bc.consumerRealmName(), "broker-app"));
        logInWithBroker(bc);
        updateAccountInformation();

        // Exchange code from "broker-app" client of "consumer" realm for the tokens
        String code = oauth.getCurrentQuery().get(OAuth2Constants.CODE);
        OAuthClient.AccessTokenResponse response = oauth.realm(bc.consumerRealmName())
                .clientId("broker-app")
                .redirectUri(getConsumerRoot() + "/auth/realms/" + REALM_CONS_NAME + "/app")
                .doAccessTokenRequest(code, "broker-app-secret");
        assertEquals(200, response.getStatusCode());

        String idToken = response.getIdToken();

        // simulate browser restart by deleting an identity cookie
        log.debugf("Deleting %s and %s cookies", AuthenticationManager.KEYCLOAK_IDENTITY_COOKIE,
                AuthenticationManager.KEYCLOAK_IDENTITY_COOKIE + CookieHelper.LEGACY_COOKIE);
        driver.manage().deleteCookieNamed(AuthenticationManager.KEYCLOAK_IDENTITY_COOKIE);
        driver.manage().deleteCookieNamed(AuthenticationManager.KEYCLOAK_IDENTITY_COOKIE + CookieHelper.LEGACY_COOKIE);

        AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
        AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

        oauth.clientId("account");
        oauth.redirectUri(getConsumerRoot() + "/auth/realms/" + REALM_PROV_NAME + "/account");
        loginPage.open(REALM_PROV_NAME);

        waitForPage(driver, "sign in to provider", true);
    }

    @Test
    public void logoutAfterIdpTokenExpired() throws VerificationException {
        driver.navigate().to(getLoginUrl(getConsumerRoot(), bc.consumerRealmName(), "broker-app"));
        logInWithBroker(bc);
        updateAccountInformation();

        // Exchange code from "broker-app" client of "consumer" realm for the tokens
        String code = oauth.getCurrentQuery().get(OAuth2Constants.CODE);
        OAuthClient.AccessTokenResponse response = oauth.realm(bc.consumerRealmName())
                .clientId("broker-app")
                .redirectUri(getConsumerRoot() + "/auth/realms/" + REALM_CONS_NAME + "/app")
                .doAccessTokenRequest(code, "broker-app-secret");
        assertEquals(200, response.getStatusCode());

        String idTokenString = response.getIdToken();
        IDToken idToken = TokenVerifier.create(idTokenString, IDToken.class).getToken();
        int expiresInMs = (int) (idToken.getExp() - idToken.getIat());

        // simulate token expiration
        setTimeOffset(expiresInMs * 2);

        logoutFromRealm(
                getConsumerRoot(),
                bc.consumerRealmName(),
                "something-else",
                idTokenString,
                "broker-app",
                getConsumerRoot() + "/auth/realms/" + REALM_CONS_NAME + "/app"
        );

        // user should be logged out successfully from the IDP even though the id_token_hint is expired
        oauth.clientId("account");
        oauth.redirectUri(getConsumerRoot() + "/auth/realms/" + REALM_PROV_NAME + "/account");
        loginPage.open(REALM_PROV_NAME);

        waitForPage(driver, "sign in to provider", true);
    }

    @Test
    public void testFrontChannelLogoutRequestsSendingOnlyClientIdWithFrontChannelLogoutApp() throws Exception {
        RealmResource realm = adminClient.realm(bc.consumerRealmName());
        IdentityProviderResource identityProviderResource = realm.identityProviders().get(bc.getIDPAlias());
        IdentityProviderRepresentation representation = identityProviderResource.toRepresentation();
        Map<String, String> config = representation.getConfig();
        Map<String, String> originalConfig = new HashMap<>(config);

        try (ClientAttributeUpdater clientUpdater = ClientAttributeUpdater.forClient(adminClient, bc.consumerRealmName(), "broker-app")
                .setFrontchannelLogout(true)
                .setAttribute(OIDCConfigAttributes.FRONT_CHANNEL_LOGOUT_URI, getConsumerRoot() + "/auth/realms/" + bc.consumerRealmName() + "/app/logout")
                .update()){
            config.put("backchannelSupported", Boolean.FALSE.toString());
            identityProviderResource.update(representation);
            logInAsUserInIDPForFirstTime();
            appPage.assertCurrent();
            executeLogoutFromRealm(
                    getConsumerRoot(),
                    bc.consumerRealmName(),
                    "something-else",
                    null,
                    "broker-app",
                    null
            );
            logoutConfirmPage.isCurrent();
            // confirm logout at consumer
            logoutConfirmPage.confirmLogout();

            WaitUtils.waitForPageToLoad();
            logoutConfirmPage.isCurrent();
            Assert.assertTrue(driver.getPageSource().contains("You are logging out from following apps"));
            Assert.assertTrue(driver.getPageSource().contains("broker-app"));

            oauth.clientId("account");
            oauth.redirectUri(getConsumerRoot() + "/auth/realms/" + REALM_PROV_NAME + "/account");
            loginPage.open(REALM_PROV_NAME);
            waitForPage(driver, "sign in to provider", true);
        } finally {
            representation.setConfig(originalConfig);
            identityProviderResource.update(representation);
        }
    }
}
