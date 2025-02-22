/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.qpid.server.security.auth.manager;

import static org.apache.commons.codec.CharEncoding.UTF_8;
import static org.apache.qpid.server.security.auth.manager.KerberosAuthenticationManager.GSSAPI_MECHANISM;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URL;
import java.net.URLDecoder;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.LoginContext;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;

import org.ietf.jgss.GSSException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.BrokerTestHelper;
import org.apache.qpid.server.security.auth.AuthenticationResult;
import org.apache.qpid.server.security.auth.sasl.SaslNegotiator;
import org.apache.qpid.server.security.auth.sasl.SaslSettings;
import org.apache.qpid.server.test.EmbeddedKdcResource;
import org.apache.qpid.server.test.KerberosUtilities;
import org.apache.qpid.test.utils.JvmVendor;
import org.apache.qpid.test.utils.SystemPropertySetter;
import org.apache.qpid.test.utils.UnitTestBase;

public class KerberosAuthenticationManagerTest extends UnitTestBase
{
    private static final Logger LOGGER = LoggerFactory.getLogger(KerberosAuthenticationManagerTest.class);
    private static final String LOGIN_CONFIG = "login.config";
    private static final String REALM = "QPID.ORG";
    private static final String SERVER_NAME = "localhost";
    private static final String SERVER_PROTOCOL = "AMQP";
    private static final String SERVICE_PRINCIPAL_NAME = SERVER_PROTOCOL + "/" + SERVER_NAME;
    private static final String SERVER_PRINCIPAL_FULL_NAME = SERVICE_PRINCIPAL_NAME + "@" + REALM;
    private static final String CLIENT_PRINCIPAL_NAME = "client";
    private static final String CLIENT_PRINCIPAL_FULL_NAME = CLIENT_PRINCIPAL_NAME + "@" + REALM;

    private static final KerberosUtilities UTILS = new KerberosUtilities();

    @ClassRule
    public static final EmbeddedKdcResource KDC = new EmbeddedKdcResource(REALM);

    @ClassRule
    public static final SystemPropertySetter SYSTEM_PROPERTY_SETTER = new SystemPropertySetter();

    private static File _clientKeyTabFile;

    private KerberosAuthenticationManager _kerberosAuthenticationProvider;
    private Broker<?> _broker;

    @BeforeClass
    public static void createKeyTabs() throws Exception
    {
        assumeThat(getJvmVendor(), not(JvmVendor.IBM));
        KDC.createPrincipal("broker.keytab", SERVER_PRINCIPAL_FULL_NAME);
        _clientKeyTabFile = KDC.createPrincipal("client.keytab", CLIENT_PRINCIPAL_FULL_NAME);
        final URL resource = KerberosAuthenticationManagerTest.class.getClassLoader().getResource(LOGIN_CONFIG);
        LOGGER.debug("JAAS config:" + resource);
        assertNotNull(resource);
        SYSTEM_PROPERTY_SETTER.setSystemProperty("java.security.auth.login.config", URLDecoder.decode(resource.getPath(), UTF_8));
        SYSTEM_PROPERTY_SETTER.setSystemProperty("javax.security.auth.useSubjectCredsOnly", "false");
    }

    @Before
    public void setUp() throws Exception
    {
        Map<String, String> context = Collections.singletonMap(KerberosAuthenticationManager.GSSAPI_SPNEGO_CONFIG,
                                                               "com.sun.security.jgss.accept");
        final Map<String, Object> attributes = new HashMap<>();
        attributes.put(AuthenticationProvider.NAME, getTestName());
        attributes.put(AuthenticationProvider.CONTEXT, context);
        _broker = BrokerTestHelper.createBrokerMock();
        _kerberosAuthenticationProvider = new KerberosAuthenticationManager(attributes, _broker);
        _kerberosAuthenticationProvider.create();
        when(_broker.getChildren(AuthenticationProvider.class))
                .thenReturn(Collections.singleton(_kerberosAuthenticationProvider));
    }

    @Test
    public void testCreateSaslNegotiator() throws Exception
    {
        final SaslSettings saslSettings = mock(SaslSettings.class);
        when(saslSettings.getLocalFQDN()).thenReturn(SERVER_NAME);
        final SaslNegotiator negotiator = _kerberosAuthenticationProvider.createSaslNegotiator(GSSAPI_MECHANISM,
                                                                                               saslSettings,
                                                                                               null);
        assertNotNull("Could not create SASL negotiator", negotiator);
        try
        {
            final AuthenticationResult result = authenticate(negotiator);
            assertEquals(AuthenticationResult.AuthenticationStatus.SUCCESS, result.getStatus());
            assertEquals(new KerberosPrincipal(CLIENT_PRINCIPAL_FULL_NAME).getName(),
                         result.getMainPrincipal().getName());
        }
        finally
        {
            negotiator.dispose();
        }
    }

    @Test
    public void testSeveralKerberosAuthenticationProviders()
    {
        final Map<String, Object> attributes =
                Collections.singletonMap(AuthenticationProvider.NAME, getTestName() + "2");
        final KerberosAuthenticationManager kerberosAuthenticationProvider =
                new KerberosAuthenticationManager(attributes, _broker);
        try
        {
            kerberosAuthenticationProvider.create();
            fail("Exception expected");
        }
        catch (IllegalConfigurationException e)
        {
            // pass
        }
    }

    @Test
    public void testCreateKerberosAuthenticationProvidersWithNonExistingJaasLoginModule()
    {
        when(_broker.getChildren(AuthenticationProvider.class)).thenReturn(Collections.emptySet());
        SYSTEM_PROPERTY_SETTER.setSystemProperty("java.security.auth.login.config",
                                                 "config.module." + System.nanoTime());
        final Map<String, Object> attributes = Collections.singletonMap(AuthenticationProvider.NAME, getTestName());
        final KerberosAuthenticationManager kerberosAuthenticationProvider =
                new KerberosAuthenticationManager(attributes, _broker);
        try
        {
            kerberosAuthenticationProvider.create();
            fail("Exception expected");
        }
        catch (IllegalConfigurationException e)
        {
            // pass
        }
    }

    @Test
    public void testAuthenticateUsingNegotiationToken() throws GSSException
    {
        final String token =
                Base64.getEncoder().encodeToString(UTILS.buildToken(CLIENT_PRINCIPAL_NAME, SERVICE_PRINCIPAL_NAME));
        final String authenticationHeader = SpnegoAuthenticator.NEGOTIATE_PREFIX + token;

        final AuthenticationResult result = _kerberosAuthenticationProvider.authenticate(authenticationHeader);

        assertNotNull(result);
        assertEquals(AuthenticationResult.AuthenticationStatus.SUCCESS, result.getStatus());
    }

    private AuthenticationResult authenticate(final SaslNegotiator negotiator) throws Exception
    {
        final LoginContext lc = UTILS.createKerberosKeyTabLoginContext(getTestName(),
                                                                       CLIENT_PRINCIPAL_FULL_NAME,
                                                                       _clientKeyTabFile);
        try
        {
            lc.login();
            final Subject clientSubject = lc.getSubject();
            final SaslClient saslClient = createSaslClient(clientSubject);
            return performNegotiation(clientSubject, saslClient, negotiator);
        }
        finally
        {
            lc.logout();
        }
    }

    private AuthenticationResult performNegotiation(final Subject clientSubject,
                                                    final SaslClient saslClient,
                                                    final SaslNegotiator negotiator)
            throws PrivilegedActionException
    {
        AuthenticationResult result;
        byte[] response = null;
        boolean initiated = false;
        do
        {
            if (!initiated)
            {
                initiated = true;
                response = Subject.doAs(clientSubject, (PrivilegedExceptionAction<byte[]>) () -> {
                    if (saslClient.hasInitialResponse())
                    {
                        return saslClient.evaluateChallenge(new byte[0]);
                    }
                    return null;
                });
            }

            result = negotiator.handleResponse(response);

            byte[] challenge = result.getChallenge();
            if (challenge != null)
            {
                response = Subject.doAs(clientSubject,
                                        (PrivilegedExceptionAction<byte[]>) () -> saslClient.evaluateChallenge(
                                                challenge));
            }
        }
        while (result.getStatus() == AuthenticationResult.AuthenticationStatus.CONTINUE);
        return result;
    }

    private SaslClient createSaslClient(final Subject clientSubject) throws PrivilegedActionException
    {
        return Subject.doAs(clientSubject, (PrivilegedExceptionAction<SaslClient>) () -> {

            final Map<String, String> props =
                    Collections.singletonMap("javax.security.sasl.server.authentication", "true");

            return Sasl.createSaslClient(new String[]{GSSAPI_MECHANISM},
                                         null,
                                         SERVER_PROTOCOL,
                                         SERVER_NAME,
                                         props,
                                         null);
        });
    }
}
