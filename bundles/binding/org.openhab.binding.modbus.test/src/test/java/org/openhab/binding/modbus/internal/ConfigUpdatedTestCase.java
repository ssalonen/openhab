/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal;

import static org.mockito.Mockito.*;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.openhab.binding.modbus.ModbusBindingProvider;
import org.openhab.core.library.types.OnOffType;
import org.openhab.model.item.binding.BindingConfigParseException;
import org.osgi.service.cm.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.procimg.SimpleDigitalIn;

/**
 * Testing how configuration update is handled
 *
 */
@RunWith(Parameterized.class)
public class ConfigUpdatedTestCase extends TestCaseSupport {

    @Parameters
    public static List<Object[]> data() {
        List<Object[]> parameters = new ArrayList<>();
        for (ServerType server : TEST_SERVERS) {
            parameters.add(new Object[] { server });
        }
        return parameters;
    }

    @SuppressWarnings("serial")
    public static class ExpectedFailure extends AssertionError {
        public ExpectedFailure(Throwable cause) {
            initCause(cause);
        }
    }

    public ConfigUpdatedTestCase(ServerType serverType) {
        super();
        this.serverType = serverType;
        // Server is a bit slower to respond than normally
        // this for the testConfigUpdatedWhilePolling
        this.artificialServerWait = 1000; // Also remember default timeout for tcp Modbus.DEFAULT_TIMEOUT
    }

    @Test
    public void testConfigUpdated() throws UnknownHostException, ConfigurationException, BindingConfigParseException,
            org.osgi.service.cm.ConfigurationException, InterruptedException {
        // Modbus server ("modbus slave") has two digital inputs
        spi.addDigitalIn(new SimpleDigitalIn(true));
        spi.addDigitalIn(new SimpleDigitalIn(false));

        binding = new ModbusBinding();
        configureSwitchItemBinding(2, SLAVE_NAME, 0);

        // simulate configuration changes
        // each updated() will interrupt and close connections. We sleep after updated() to give it time to actually
        // make the poll
        for (int i = 0; i < 2; i++) {
            if (i > 0) {
                Thread.sleep(300);
            }
            binding.updated(
                    addSlave(newLongPollBindingConfig(), SLAVE_NAME, ModbusBindingProvider.TYPE_DISCRETE, null, 0, 2));
        }

        // Give the system some time to make the expected connections & requests
        waitForRequests(2);
        if (!serverType.equals(ServerType.UDP)) {
            waitForConnectionsReceived(2);
        }

        verifyEvents();
    }

    private void verifyEvents() throws ExpectedFailure {

        verify(eventPublisher, never()).postCommand(null, null);
        verify(eventPublisher, never()).sendCommand(null, null);
        try {
            verify(eventPublisher).postUpdate("Item1", OnOffType.ON);
            verify(eventPublisher).postUpdate("Item2", OnOffType.OFF);
        } catch (AssertionError e) {
            throw new ExpectedFailure(e);
        }
        verifyNoMoreInteractions(eventPublisher);
    }

    /**
     * To verify fix for https://github.com/openhab/openhab1-addons/issues/5078
     *
     * @throws UnknownHostException
     * @throws org.osgi.service.cm.ConfigurationException
     * @throws BindingConfigParseException
     * @throws InterruptedException
     */
    @Test
    public void testConfigUpdatedWhilePolling() throws UnknownHostException, org.osgi.service.cm.ConfigurationException,
            BindingConfigParseException, InterruptedException {
        final Logger logger = LoggerFactory.getLogger(ConfigUpdatedTestCase.class);
        // run this test only for tcp server due to the customized connection string
        Assume.assumeTrue(serverType.equals(ServerType.TCP));
        MAX_WAIT_REQUESTS_MILLIS = 10000;

        spi.addDigitalIn(new SimpleDigitalIn(true));
        spi.addDigitalIn(new SimpleDigitalIn(false));

        binding = new ModbusBinding();
        configureSwitchItemBinding(2, SLAVE_NAME, 0);

        // Customized connection settings, keep the connection open for 2000s, no connection retries, 300ms connection
        // timeout
        String connection = String.format("%s:%d:30:2000000:0:1:300", localAddress().getHostAddress(), tcpModbusPort);
        Dictionary<String, Object> cfg = addSlave(newLongPollBindingConfig(), serverType, connection, SLAVE_NAME,
                ModbusBindingProvider.TYPE_DISCRETE, null, 1, 0, 2);
        putSlaveConfigParameter(cfg, serverType, SLAVE_NAME, "updateunchangeditems", "true");
        logger.info("First updated() started");
        binding.updated(cfg); // first poll starts now
        logger.info("First updated() finished");
        Thread.sleep(500); // time for poll to start

        Thread executeOnBackground = new Thread(new Runnable() {
            @Override
            public void run() {
                logger.info("First (manual) execution started");
                binding.pollAllScheduledNow();
                logger.info("First (manual) execution finished");
            }
        });

        executeOnBackground.start();
        Thread.sleep(100);
        // Connection should be now open (since ~100ms passed since connection)
        // But the first query is still on the way (since server is so slow)

        // Simulate config update
        // ongoing poll is interrupted
        logger.info("Second updated() started, should cancel first manual execution before response is received");
        binding.updated(cfg);
        Thread.sleep(500);
        logger.info("Second updated() finished");

        executeOnBackground.join();

        // Polling should work after config update (server accepts only single connection so this verifies that
        // connections were closed)
        logger.info("Second execution started");
        binding.pollAllScheduledNow();
        logger.info("Second execution finished");

        // three requests, two of those due to pollAllScheduledNow() commands in this test,
        // one due to initial poll when updated() is called
        waitForRequests(3);
        // two connections, connection is closed on second updated(), and thus connection needs to re-initated
        waitForConnectionsReceived(2);
        verify(eventPublisher, times(3)).postUpdate("Item1", OnOffType.ON);
        verify(eventPublisher, times(3)).postUpdate("Item2", OnOffType.OFF);
        verifyNoMoreInteractions(eventPublisher);
    }

}
