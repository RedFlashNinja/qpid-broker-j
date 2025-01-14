/*
 *
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
 *
 */
package org.apache.qpid.tests.http.authentication;

import static javax.servlet.http.HttpServletResponse.SC_CREATED;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.apache.qpid.server.transport.network.security.ssl.SSLUtil.canGenerateCerts;
import static org.apache.qpid.server.transport.network.security.ssl.SSLUtil.generateSelfSignedCertificate;
import static org.apache.qpid.test.utils.TestSSLConstants.JAVA_KEYSTORE_TYPE;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.After;
import org.junit.Test;

import org.apache.qpid.server.management.plugin.HttpManagement;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Protocol;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.security.FileKeyStore;
import org.apache.qpid.server.security.ManagedPeerCertificateTrustStore;
import org.apache.qpid.server.security.auth.manager.AnonymousAuthenticationManager;
import org.apache.qpid.server.security.auth.manager.ExternalAuthenticationManager;
import org.apache.qpid.server.transport.network.security.ssl.SSLUtil.KeyCertPair;
import org.apache.qpid.server.util.BaseAction;
import org.apache.qpid.server.util.DataUrlUtils;
import org.apache.qpid.tests.http.HttpTestBase;
import org.apache.qpid.tests.http.HttpTestHelper;

public class PreemptiveAuthenticationTest extends HttpTestBase
{
    private static final TypeReference<String> STRING_TYPE_REF = new TypeReference<String>() {};
    private static final String STORE_PASSWORD = "password";

    private Deque<BaseAction<Void, Exception>> _tearDownActions;
    private String _keyStore;

    @After
    public void tearDown() throws Exception
    {
        if (_tearDownActions != null)
        {
            Exception exception = null;
            while(!_tearDownActions.isEmpty())
            {
                try
                {
                    _tearDownActions.removeLast().performAction(null);
                }
                catch (Exception e)
                {
                    exception = e;
                }
            }

            if (exception != null)
            {
                throw exception;
            }
        }
    }

    @Test
    public void clientAuthSuccess() throws Exception
    {
        assumeThat(canGenerateCerts(), is(true));
        HttpTestHelper helper = configForClientAuth("CN=foo");

        String userId = helper.getJson("broker/getUser", STRING_TYPE_REF, SC_OK);
        assertThat(userId, startsWith("foo@"));
    }

    @Test
    public void clientAuthUnrecognisedCert() throws Exception
    {
        assumeThat(canGenerateCerts(), is(true));
        HttpTestHelper helper = configForClientAuth("CN=foo");

        String keyStore = createKeyStoreDataUrl(getKeyCertPair("CN=bar"), STORE_PASSWORD);
        helper.setKeyStore(keyStore, STORE_PASSWORD);

        try
        {
            helper.getJson("broker/getUser", STRING_TYPE_REF, SC_OK);
            fail("Exception not thrown");
        }
        catch (SSLHandshakeException e)
        {
            // PASS
        }
        catch (SocketException e)
        {
            // TODO - defect - we are not always seeing the SSL handshake exception
        }
    }

    @Test
    public void basicAuth() throws Exception
    {
        verifyGetBroker(SC_OK);
    }

    @Test
    public void basicAuthWrongPassword() throws Exception
    {
        getHelper().setPassword("badpassword");

        verifyGetBroker(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    public void httpBasicAuthDisabled() throws Exception
    {
        doBasicAuthDisabledTest(false);
    }

    @Test
    public void httpsBasicAuthDisabled() throws Exception
    {
        doBasicAuthDisabledTest(true);
    }

    @Test
    public void anonymousTest() throws Exception
    {
        HttpTestHelper helper = configForAnonymous();

        String userId = helper.getJson("broker/getUser", STRING_TYPE_REF, SC_OK);
        assertThat(userId, startsWith("ANONYMOUS@"));
    }

    @Test
    public void noSessionCreated() throws Exception
    {
        final HttpURLConnection conn = getHelper().openManagementConnection("broker", "GET");
        assertThat("Unexpected server response", conn.getResponseCode(), is(equalTo(SC_OK)));
        assertThat("Unexpected cookie", conn.getHeaderFields(), not(hasKey("Set-Cookie")));
    }

    private void verifyGetBroker(int expectedResponseCode) throws Exception
    {
        assertThat(getHelper().submitRequest("broker", "GET"), is(equalTo(expectedResponseCode)));
    }

    private void doBasicAuthDisabledTest(final boolean tls) throws Exception
    {
        HttpTestHelper configHelper = new HttpTestHelper(getBrokerAdmin());
        configHelper.setTls(!tls);
        final String authEnabledAttrName = tls ? HttpManagement.HTTPS_BASIC_AUTHENTICATION_ENABLED : HttpManagement.HTTP_BASIC_AUTHENTICATION_ENABLED;
        try
        {
            HttpTestHelper helper = new HttpTestHelper(getBrokerAdmin());
            helper.setTls(tls);
            assertThat(helper.submitRequest("broker", "GET"), is(equalTo(SC_OK)));

            configHelper.submitRequest("plugin/httpManagement", "POST",
                                       Collections.<String, Object>singletonMap(authEnabledAttrName, Boolean.FALSE), SC_OK);

            assertThat(helper.submitRequest("broker", "GET"), is(equalTo(SC_UNAUTHORIZED)));
        }
        finally
        {
            configHelper.submitRequest("plugin/httpManagement", "POST",
                                       Collections.<String, Object>singletonMap(authEnabledAttrName, Boolean.TRUE), SC_OK);

        }
    }

    private HttpTestHelper configForClientAuth(final String x500Name) throws Exception
    {
        final KeyCertPair keyCertPair = getKeyCertPair(x500Name);
        final byte[] cert = keyCertPair.getCertificate().getEncoded();

        _keyStore = createKeyStoreDataUrl(keyCertPair, STORE_PASSWORD);

        final Deque<BaseAction<Void,Exception>> deleteActions = new ArrayDeque<>();

        final Map<String, Object> authAttr = new HashMap<>();
        authAttr.put(ExternalAuthenticationManager.TYPE, "External");
        authAttr.put(ExternalAuthenticationManager.ATTRIBUTE_USE_FULL_DN, false);

        getHelper().submitRequest("authenticationprovider/myexternal","PUT", authAttr, SC_CREATED);

        deleteActions.add(object -> getHelper().submitRequest("authenticationprovider/myexternal", "DELETE", SC_OK));

        final Map<String, Object> keystoreAttr = new HashMap<>();
        keystoreAttr.put(FileKeyStore.TYPE, "FileKeyStore");
        keystoreAttr.put(FileKeyStore.STORE_URL, "classpath:java_broker_keystore.jks");
        keystoreAttr.put(FileKeyStore.PASSWORD, STORE_PASSWORD);
        keystoreAttr.put(FileKeyStore.KEY_STORE_TYPE, JAVA_KEYSTORE_TYPE);

        getHelper().submitRequest("keystore/mykeystore","PUT", keystoreAttr, SC_CREATED);
        deleteActions.add(object -> getHelper().submitRequest("keystore/mykeystore", "DELETE", SC_OK));

        final Map<String, Object> truststoreAttr = new HashMap<>();
        truststoreAttr.put(ManagedPeerCertificateTrustStore.TYPE, ManagedPeerCertificateTrustStore.TYPE_NAME);
        truststoreAttr.put(ManagedPeerCertificateTrustStore.STORED_CERTIFICATES, Collections.singletonList(Base64.getEncoder().encodeToString(cert)));


        getHelper().submitRequest("truststore/mytruststore","PUT", truststoreAttr, SC_CREATED);
        deleteActions.add(object -> getHelper().submitRequest("truststore/mytruststore", "DELETE", SC_OK));

        final Map<String, Object> portAttr = new HashMap<>();
        portAttr.put(Port.TYPE, "HTTP");
        portAttr.put(Port.PORT, 0);
        portAttr.put(Port.AUTHENTICATION_PROVIDER, "myexternal");
        portAttr.put(Port.PROTOCOLS, Collections.singleton(Protocol.HTTP));
        portAttr.put(Port.TRANSPORTS, Collections.singleton(Transport.SSL));
        portAttr.put(Port.NEED_CLIENT_AUTH, true);
        portAttr.put(Port.KEY_STORE, "mykeystore");
        portAttr.put(Port.TRUST_STORES, Collections.singletonList("mytruststore"));

        getHelper().submitRequest("port/myport","PUT", portAttr, SC_CREATED);
        deleteActions.add(object -> getHelper().submitRequest("port/myport", "DELETE", SC_OK));

        Map<String, Object> clientAuthPort = getHelper().getJsonAsMap("port/myport");
        int boundPort = Integer.parseInt(String.valueOf(clientAuthPort.get("boundPort")));

        assertThat(boundPort, is(greaterThan(0)));

        _tearDownActions = deleteActions;

        HttpTestHelper helper = new HttpTestHelper(getBrokerAdmin(), null, boundPort);
        helper.setTls(true);
        helper.setKeyStore(_keyStore, STORE_PASSWORD);
        return helper;
    }

    private HttpTestHelper configForAnonymous() throws Exception
    {
        final Deque<BaseAction<Void,Exception>> deleteActions = new ArrayDeque<>();

        final Map<String, Object> authAttr = new HashMap<>();
        authAttr.put(AnonymousAuthenticationManager.TYPE, AnonymousAuthenticationManager.PROVIDER_TYPE);

        getHelper().submitRequest("authenticationprovider/myanon","PUT", authAttr, SC_CREATED);

        deleteActions.add(object -> getHelper().submitRequest("authenticationprovider/myanon", "DELETE", SC_OK));

        final Map<String, Object> portAttr = new HashMap<>();
        portAttr.put(Port.TYPE, "HTTP");
        portAttr.put(Port.PORT, 0);
        portAttr.put(Port.AUTHENTICATION_PROVIDER, "myanon");
        portAttr.put(Port.PROTOCOLS, Collections.singleton(Protocol.HTTP));
        portAttr.put(Port.TRANSPORTS, Collections.singleton(Transport.TCP));

        getHelper().submitRequest("port/myport","PUT", portAttr, SC_CREATED);
        deleteActions.add(object -> getHelper().submitRequest("port/myport", "DELETE", SC_OK));

        Map<String, Object> clientAuthPort = getHelper().getJsonAsMap("port/myport");
        int boundPort = Integer.parseInt(String.valueOf(clientAuthPort.get("boundPort")));

        assertThat(boundPort, is(greaterThan(0)));

        _tearDownActions = deleteActions;

        HttpTestHelper helper = new HttpTestHelper(getBrokerAdmin(), null, boundPort);
        helper.setKeyStore(_keyStore, STORE_PASSWORD);
        helper.setPassword(null);
        helper.setUserName(null);
        return helper;

    }

    private String createKeyStoreDataUrl(final KeyCertPair keyCertPair, final String password) throws Exception
    {
        final KeyStore keyStore = KeyStore.getInstance(JAVA_KEYSTORE_TYPE);
        keyStore.load(null, null);
        Certificate[] certChain = new Certificate[] {keyCertPair.getCertificate()};
        keyStore.setKeyEntry("key1", keyCertPair.getPrivateKey(), password.toCharArray(), certChain);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream())
        {
            keyStore.store(bos, password.toCharArray());
            bos.toByteArray();
            return DataUrlUtils.getDataUrlForBytes(bos.toByteArray());
        }
    }

    private KeyCertPair getKeyCertPair(final String x500Name) throws Exception
    {
        return generateSelfSignedCertificate("RSA", "SHA256WithRSA",
                                             2048, Instant.now().toEpochMilli(),
                                             Duration.of(365, ChronoUnit.DAYS).getSeconds(),
                                             x500Name,
                                             Collections.emptySet(),
                                             Collections.singleton(InetAddress.getLoopbackAddress()));
    }

}
