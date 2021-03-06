/*
 * Kontalk Java client
 * Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.client;

import javax.net.ssl.SSLContext;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.ExceptionCallback;
import org.jivesoftware.smack.SASLAuthentication;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration.Builder;
import org.kontalk.util.TrustUtils;


/**
 * XMPP Connection to a Kontalk Server.
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
final class KonConnection extends XMPPTCPConnection {
    private static final Logger LOGGER = Logger.getLogger(KonConnection.class.getName());

    private static final String RESSOURCE = "Kontalk_Desktop";

    public KonConnection(EndpointServer server, boolean validateCertificate) {
        this(server, null, null, validateCertificate);
    }

    public KonConnection(EndpointServer server,
            PrivateKey privateKey,
            X509Certificate bridgeCert,
            boolean validateCertificate) {
        super(buildConfiguration(RESSOURCE,
                server,
                privateKey,
                bridgeCert,
                validateCertificate)
        );

        // blacklist PLAIN mechanism
        SASLAuthentication.blacklistSASLMechanism("PLAIN");

        // enable SM without resumption (XEP-0198)
        this.setUseStreamManagement(true);
        this.setUseStreamManagementResumption(false);
    }

    private static XMPPTCPConnectionConfiguration buildConfiguration(
            String resource,
            EndpointServer server,
            PrivateKey privateKey,
            X509Certificate bridgeCert,
            boolean validateCertificate) {
        Builder builder =
            XMPPTCPConnectionConfiguration.builder();

        builder
            .setHost(server.getHost())
            .setPort(server.getPort())
            .setServiceName(server.getNetwork())
            .setResource(resource)
            .allowEmptyOrNullUsernames()
            .setCallbackHandler(new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks)
                        throws IOException, UnsupportedCallbackException {
                    for (Callback cb : callbacks)
                        LOGGER.info("got callback!?: " + cb);
                }
            })
            // enable compression
            .setCompressionEnabled(true)
            // enable encryption
            .setSecurityMode(SecurityMode.required);

        // setup SSL
        SSLContext sslContext = null;
        if (!validateCertificate) {
            LOGGER.warning("disabling SSL certificate validation");
        }
        try {
            sslContext = privateKey == null || bridgeCert == null ?
                    TrustUtils.getCustomSSLContext(validateCertificate) :
                    TrustUtils.getCustomSSLContext(privateKey,
                    bridgeCert,
                    validateCertificate);
            // Note: SASL EXTERNAL is already enabled in Smack
        } catch (NoSuchAlgorithmException |
                KeyStoreException |
                IOException |
                CertificateException |
                UnrecoverableKeyException |
                KeyManagementException ex) {
            LOGGER.log(Level.WARNING, "can't setup SSL connection", ex);
        }

        if (sslContext != null)
            builder.setCustomSSLContext(sslContext);

        return builder.build();
    }

    @Override
    public void disconnect() {
        LOGGER.info("disconnecting");
        super.disconnect();
    }

    String getServer() {
        return this.getConfiguration().getServiceName();
    }

    boolean send(Stanza p) {
        try {
            super.sendStanza(p);
        } catch (SmackException.NotConnectedException ex) {
            LOGGER.info("can't send packet, not connected.");
            return false;
        }
        LOGGER.config("packet: "+p);
        return true;
    }

    void sendWithCallback(IQ packet, StanzaListener callback) {
        LOGGER.config("packet: "+packet);
        try {
            super.sendIqWithResponseCallback(packet, callback, new ExceptionCallback() {
                @Override
                public void processException(Exception ex) {
                    LOGGER.log(Level.WARNING, "exception response", ex);
                }
            });
        } catch (SmackException.NotConnectedException ex) {
            LOGGER.log(Level.WARNING, "not connected", ex);
        }
    }
}
