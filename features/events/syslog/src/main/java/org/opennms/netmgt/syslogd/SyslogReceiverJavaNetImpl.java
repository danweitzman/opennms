/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.syslogd;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.config.SyslogdConfig;
import org.opennms.netmgt.syslogd.api.SyslogConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;

/**
 * @author <a href="mailto:weave@oculan.com">Brian Weaver</a>
 * @author <a href="http://www.oculan.com">Oculan Corporation</a>
 * @fiddler joed
 */
public class SyslogReceiverJavaNetImpl extends SinkDispatchingSyslogReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(SyslogReceiverJavaNetImpl.class);
    
    private static final MetricRegistry METRICS = new MetricRegistry();

    private static final int SOCKET_TIMEOUT = 500;

    /**
     * The Fiber's status.
     */
    private volatile boolean m_stop;

    /**
     * The UDP socket for receipt and transmission of packets from agents.
     */
    private DatagramSocket m_dgSock;

    /**
     * The context thread
     */
    private Thread m_context;

    private final SyslogdConfig m_config;

    public SyslogReceiverJavaNetImpl(final SyslogdConfig config) {
        super(config);
        m_config = config;
        m_stop = false;
        m_dgSock = null;
    }

    @Override
    public String getName() {
        String listenAddress = m_config.getListenAddress() == null? "0.0.0.0" : m_config.getListenAddress();
        return getClass().getSimpleName() + " [" + listenAddress + ":" + m_config.getSyslogPort() + "]";
    }

    /*
     * stop the current receiver
     * @throws InterruptedException
     * 
     */
    @Override
    public void stop() throws InterruptedException {
        m_stop = true;

        // Close the datagram socket
        if (m_dgSock != null) {
            m_dgSock.close();
        }

        if (m_context != null) {
            LOG.debug("Stopping and joining thread context {}", m_context.getName());
            m_context.interrupt();
            m_context.join();
            LOG.debug("Thread context stopped and joined");
        }

        try {
            if (m_dispatcher != null) {
                m_dispatcher.close();
                m_dispatcher = null;
            }
        } catch (Exception e) {
            LOG.warn("Exception while closing dispatcher.", e);
        }
    }

    /**
     * The execution context.
     */
    @Override
    public void run() {
        // Setup logging and create the dispatcher
        super.run();

        // get the context
        m_context = Thread.currentThread();

        // Create some metrics
        Meter packetMeter = METRICS.meter(MetricRegistry.name(getClass(), "packets"));
        Histogram packetSizeHistogram = METRICS.histogram(MetricRegistry.name(getClass(), "packetSize"));

        if (m_stop) {
            LOG.debug("Stop flag set before thread started, exiting");
            return;
        } else {
            LOG.debug("Thread context started");
        }

        // allocate a buffer
        final int length = 0xffff;
        final byte[] buffer = new byte[length];

        try {
            LOG.debug("Creating syslog socket");
            m_dgSock = new DatagramSocket(null);
        } catch (SocketException e) {
            LOG.warn("Could not create syslog socket: " + e.getMessage(), e);
            return;
        }

        // set an SO timeout to make sure we don't block forever
        // if a socket is closed.
        try {
            LOG.debug("Setting socket timeout to {}ms", SOCKET_TIMEOUT);
            m_dgSock.setSoTimeout(SOCKET_TIMEOUT);
        } catch (SocketException e) {
            LOG.warn("An I/O error occured while trying to set the socket timeout", e);
        }

        // Set SO_REUSEADDR so that we don't run into problems in
        // unit tests trying to rebind to an address where other tests
        // also bound. This shouldn't have any effect at runtime.
        try {
            LOG.debug("Setting socket SO_REUSEADDR to true");
            m_dgSock.setReuseAddress(true);
        } catch (SocketException e) {
            LOG.warn("An I/O error occured while trying to set SO_REUSEADDR", e);
        }

        // Increase the receive buffer for the socket
        try {
            LOG.debug("Attempting to set receive buffer size to {}", Integer.MAX_VALUE);
            m_dgSock.setReceiveBufferSize(Integer.MAX_VALUE);
            LOG.debug("Actual receive buffer size is {}", m_dgSock.getReceiveBufferSize());
        } catch (SocketException e) {
            LOG.info("Failed to set the receive buffer to {}", Integer.MAX_VALUE, e);
        }

        try {
            LOG.debug("Opening datagram socket");
            if (m_config.getListenAddress() != null) {
                m_dgSock.bind(new InetSocketAddress(InetAddressUtils.addr(m_config.getListenAddress()), m_config.getSyslogPort()));
            } else {
                m_dgSock.bind(new InetSocketAddress(m_config.getSyslogPort()));
            }
        } catch (SocketException e) {
            LOG.info("Failed to open datagram socket", e);
        }

        // set to avoid numerous tracing message
        boolean ioInterrupted = false;

        // Construct one mutable {@link DatagramPacket} that will be used for receiving syslog messages 
        DatagramPacket pkt = new DatagramPacket(buffer, length);

        // now start processing incoming requests
        while (!m_stop) {
            if (m_context.isInterrupted()) {
                LOG.debug("Thread context interrupted");
                break;
            }

            try {
                if (!ioInterrupted) {
                    LOG.debug("Waiting on a datagram to arrive");
                }

                m_dgSock.receive(pkt);

                // Increment the packet counter
                packetMeter.mark();

                // Create a metric for the Syslog packet size
                packetSizeHistogram.update(length);

                final SyslogConnection connection = new SyslogConnection(pkt, true);
                m_dispatcher.send(connection);

                ioInterrupted = false; // reset the flag
            } catch (SocketTimeoutException e) {
                ioInterrupted = true;
                continue;
            } catch (InterruptedIOException e) {
                ioInterrupted = true;
                continue;
            } catch (IOException e) {
                if (m_stop) {
                    // A SocketException can be thrown during normal shutdown so log as debug
                    LOG.debug("Shutting down the datagram receipt port: " + e.getMessage());
                } else {
                    LOG.error("An I/O exception occured on the datagram receipt port, exiting", e);
                }
                break;
            }

        } // end while status OK

        LOG.debug("Thread context exiting");
    }

    @Override
    public void reload() throws IOException {
        m_config.reload();
        m_stop = false;
    }
}
