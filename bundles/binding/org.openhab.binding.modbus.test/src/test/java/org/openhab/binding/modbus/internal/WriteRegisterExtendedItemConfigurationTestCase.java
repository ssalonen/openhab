/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

import java.util.Dictionary;

import org.junit.Before;
import org.junit.Test;
import org.openhab.binding.modbus.ModbusBindingProvider;
import org.openhab.binding.modbus.internal.Transformation.TransformationHelperWrapper;
import org.openhab.core.library.items.DimmerItem;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.RollershutterItem;
import org.openhab.core.library.items.StringItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StopMoveType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.transform.TransformationException;
import org.openhab.core.transform.TransformationService;
import org.openhab.model.item.binding.BindingConfigParseException;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationException;

import net.wimpi.modbus.procimg.SimpleRegister;

/**
 * Tests for items with extended syntax. Run only against TCP server.
 *
 */
public class WriteRegisterExtendedItemConfigurationTestCase extends TestCaseSupport {

    private ModbusGenericBindingProvider provider;
    private Dictionary<String, Object> config;

    @Before
    public void initSlaveAndServer() throws Exception {
        spi.addRegister(new SimpleRegister(9));
        spi.addRegister(new SimpleRegister(10));
        //
        binding = new ModbusBinding();
        config = newNoPollBindingConfig();
        addSlave(config, SLAVE_NAME, ModbusBindingProvider.TYPE_HOLDING, ModbusBindingProvider.VALUE_TYPE_INT16, 0, 2);

        // Configure items

        provider = new ModbusGenericBindingProvider();

        binding.setEventPublisher(eventPublisher);
        binding.addBindingProvider(provider);
    }

    /**
     * Call to make the binding "long-polling", polling instantly on binding.updated() (given that config is valid)
     */
    private void setLongPoll() {
        config.put("poll", String.valueOf(REFRESH_INTERVAL_LONG_POLL));
    }

    @Test
    public void testRegisterWriteRollershutterItemManyConnections()
            throws BindingConfigParseException, ConfigurationException {
        // Inspired by https://github.com/openhab/openhab/pull/4654
        provider.processBindingConfiguration("test.items", new RollershutterItem("Item1"),
                String.format(
                        ">[%1$s:0:trigger=UP,transformation=1],>[%1$s:0:trigger=DOWN,transformation=-1]"
                                + ",>[%1$s:1:trigger=MOVE,transformation=1],>[%1$s:1:trigger=STOP,transformation=0]",
                        SLAVE_NAME));
        binding.updated(config);
        verifyNoMoreInteractions(eventPublisher); // write-only item, no event sent
        binding.receiveCommand("Item1", UpDownType.UP);
        waitForConnectionsReceived(1);
        assertThat(spi.getRegister(0).getValue(), is(equalTo(1)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteIncreaseWithoutRead()
            throws BindingConfigParseException, ConfigurationException, InterruptedException {
        // Inspired by https://github.com/openhab/openhab/pull/4654
        provider.processBindingConfiguration("test.items", new DimmerItem("Item1"),
                String.format(">[%1$s:0],<[%1$s:0]", SLAVE_NAME));
        binding.updated(config);
        verifyNoMoreInteractions(eventPublisher);
        binding.receiveCommand("Item1", IncreaseDecreaseType.INCREASE);
        // Binding cannot execute the command since there is no polled value
        // -> no change in registers
        // Stop was not processed, even after some time
        Thread.sleep(SLEEP_AFTER_REQUEST);
        assertThat(spi.getRegister(0).getValue(), is(equalTo(9)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteIncreaseWithRead()
            throws BindingConfigParseException, ConfigurationException, InterruptedException {
        setLongPoll();
        // Read index 1 (value=10) and (INCREASE command) increments it by one -> 11. Written to index 0
        provider.processBindingConfiguration("test.items", new DimmerItem("Item1"),
                String.format(">[%1$s:0],<[%1$s:1]", SLAVE_NAME));
        binding.updated(config);
        waitForConnectionsReceived(1);
        verify(eventPublisher).postUpdate("Item1", new DecimalType(10));
        verifyNoMoreInteractions(eventPublisher);

        binding.receiveCommand("Item1", IncreaseDecreaseType.INCREASE);
        waitForConnectionsReceived(2);
        assertThat(spi.getRegister(0).getValue(), is(equalTo(11)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteIncreaseWithRead2()
            throws BindingConfigParseException, InterruptedException, ConfigurationException {
        setLongPoll();
        // Read index 1 (value=10) and index 0 (value=9).
        // INCREASE command increments last read value (9) by one -> 10. Written to index 0
        provider.processBindingConfiguration("test.items", new DimmerItem("Item1"),
                String.format(">[%1$s:0],<[%1$s:1],<[%1$s:0]", SLAVE_NAME));
        binding.updated(config);
        waitForConnectionsReceived(1);
        verify(eventPublisher).postUpdate("Item1", new DecimalType(10));
        verify(eventPublisher).postUpdate("Item1", new DecimalType(9));
        verifyNoMoreInteractions(eventPublisher);

        binding.receiveCommand("Item1", IncreaseDecreaseType.INCREASE);
        waitForConnectionsReceived(2);
        // Binding cannot execute the command since there is no polled value
        // -> no change in registers
        assertThat(spi.getRegister(0).getValue(), is(equalTo(10)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteIncreaseWithRead3()
            throws BindingConfigParseException, ConfigurationException, InterruptedException {
        setLongPoll();
        // same as testRegisterWriteIncreaseWithRead2 but order of read connections is flipped
        provider.processBindingConfiguration("test.items", new DimmerItem("Item1"),
                String.format(">[%1$s:0],<[%1$s:0],<[%1$s:1]", SLAVE_NAME));
        binding.updated(config);
        waitForConnectionsReceived(1);
        verify(eventPublisher).postUpdate("Item1", new DecimalType(10));
        verify(eventPublisher).postUpdate("Item1", new DecimalType(9));
        verifyNoMoreInteractions(eventPublisher);

        binding.receiveCommand("Item1", IncreaseDecreaseType.INCREASE);
        waitForConnectionsReceived(2);
        // Binding cannot execute the command since there is no polled value
        // -> no change in registers
        assertThat(spi.getRegister(0).getValue(), is(equalTo(11)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteIncreaseWithTransformation()
            throws BindingConfigParseException, ConfigurationException {
        provider.processBindingConfiguration("test.items", new DimmerItem("Item1"),
                String.format(">[%1$s:0:transformation=3],<[%1$s:0]", SLAVE_NAME));
        binding.updated(config);
        verifyNoMoreInteractions(eventPublisher);

        binding.receiveCommand("Item1", IncreaseDecreaseType.INCREASE);
        waitForConnectionsReceived(1);
        // Binding will be able to write the value even without previously polled value since the transformation
        // converts INCREASE to constant 3
        assertThat(spi.getRegister(0).getValue(), is(equalTo(3)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteRollershutterItemManyConnections2()
            throws BindingConfigParseException, ConfigurationException, InterruptedException {
        // Inspired by https://github.com/openhab/openhab/pull/4654
        provider.processBindingConfiguration("test.items", new RollershutterItem("Item1"),
                String.format(
                        ">[%1$s:0:trigger=UP,transformation=1],>[%1$s:0:trigger=DOWN,transformation=-1]"
                                + ",>[%1$s:1:trigger=MOVE,transformation=1],>[%1$s:1:trigger=STOP,transformation=0]",
                        SLAVE_NAME));
        binding.updated(config);
        verifyNoMoreInteractions(eventPublisher); // write-only item, no event sent
        binding.receiveCommand("Item1", UpDownType.DOWN);
        waitForConnectionsReceived(1);
        // 65535 is same as -1, the SimpleRegister.getValue just returns the unsigned 16bit representation of the
        // register
        assertThat(spi.getRegister(0).getValue(), is(equalTo(65535)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteRollershutterItemManyConnections3()
            throws BindingConfigParseException, ConfigurationException, InterruptedException {
        // Inspired by https://github.com/openhab/openhab/pull/4654
        provider.processBindingConfiguration("test.items", new RollershutterItem("Item1"),
                String.format(
                        ">[%1$s:0:trigger=UP,transformation=1],>[%1$s:0:trigger=DOWN,transformation=-1]"
                                + ",>[%1$s:1:trigger=MOVE,transformation=1],>[%1$s:1:trigger=STOP,transformation=0]",
                        SLAVE_NAME));
        binding.updated(config);
        verifyNoMoreInteractions(eventPublisher); // write-only item, no event sent
        binding.receiveCommand("Item1", StopMoveType.MOVE);
        waitForConnectionsReceived(1);
        assertThat(spi.getRegister(0).getValue(), is(equalTo(9)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(1)));
    }

    @Test
    public void testRegisterWriteRollershutterItemManyConnections4()
            throws BindingConfigParseException, ConfigurationException, InterruptedException {
        // Inspired by https://github.com/openhab/openhab/pull/4654
        provider.processBindingConfiguration("test.items", new RollershutterItem("Item1"),
                String.format(
                        ">[%1$s:0:trigger=UP,transformation=1],>[%1$s:0:trigger=DOWN,transformation=-1]"
                                + ",>[%1$s:1:trigger=MOVE,transformation=1],>[%1$s:1:trigger=STOP,transformation=0]",
                        SLAVE_NAME));
        binding.updated(config);
        verifyNoMoreInteractions(eventPublisher); // write-only item, no event sent
        binding.receiveCommand("Item1", StopMoveType.STOP);
        waitForConnectionsReceived(1);
        assertThat(spi.getRegister(0).getValue(), is(equalTo(9)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(0)));
    }

    @Test
    public void testRegisterWriteRollershutterWriteFiltered()
            throws BindingConfigParseException, ConfigurationException, InterruptedException {
        provider.processBindingConfiguration("test.items", new RollershutterItem("Item1"),
                String.format(">[%1$s:0:trigger=UP,transformation=1],>[%1$s:0:trigger=DOWN,transformation=-1]"
                        + ",>[%1$s:1:trigger=MOVE,transformation=1]", SLAVE_NAME));
        binding.updated(config);
        verifyNoMoreInteractions(eventPublisher);
        binding.receiveCommand("Item1", StopMoveType.STOP);
        // Stop was not processed, even after some time
        Thread.sleep(SLEEP_AFTER_REQUEST);
        assertThat(spi.getRegister(0).getValue(), is(equalTo(9)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteSwitchItemNonNumbericTransformationAndTwoRegistersManyConnections4()
            throws BindingConfigParseException, ConfigurationException, InterruptedException {
        provider.processBindingConfiguration("test.items", new SwitchItem("Item1"), String.format(
                ">[%1$s:0:trigger=OFF,transformation=ON],>[%1$s:1:trigger=OFF,transformation=OFF]", SLAVE_NAME));
        binding.updated(config);
        verifyNoMoreInteractions(eventPublisher); // write-only item, no event sent
        binding.receiveCommand("Item1", OnOffType.OFF);
        waitForConnectionsReceived(2);
        // two registers were changed at the same time
        assertThat(spi.getRegister(0).getValue(), is(equalTo(1)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(0)));
    }

    @Test
    public void testRegisterWritePercentTypeWithTransformation()
            throws BindingConfigParseException, ConfigurationException, InterruptedException {
        provider.processBindingConfiguration("test.items", new NumberItem("Item1"),
                String.format(">[%1$s:0]", SLAVE_NAME));
        binding.updated(config);
        verifyNoMoreInteractions(eventPublisher); // write-only item, no event sent
        binding.receiveCommand("Item1", new PercentType("3.4"));
        waitForConnectionsReceived(1);
        // percent rounded down
        assertThat(spi.getRegister(0).getValue(), is(equalTo(3)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteNumberItemComplexTransformation()
            throws BindingConfigParseException, ConfigurationException {

        provider.processBindingConfiguration("test.items", new NumberItem("Item1"),
                String.format(">[%1$s:0:trigger=*,transformation=MULTIPLY(3)]", SLAVE_NAME));

        ModbusBindingConfig config = provider.getConfig("Item1");

        // Inject transformation
        for (ItemIOConnection itemIOConnection : config.getWriteConnections()) {
            itemIOConnection.getTransformation().setTransformationHelper(new TransformationHelperWrapper() {

                @Override
                public TransformationService getTransformationService(BundleContext context,
                        String transformationServiceName) {
                    if ("MULTIPLY".equals(transformationServiceName)) {
                        return new TransformationService() {

                            @Override
                            public String transform(String multiplier, String arg) throws TransformationException {
                                return String.valueOf(Integer.valueOf(multiplier) * Integer.valueOf(arg));
                            }
                        };
                    } else {
                        throw new AssertionError("unexpected transformation");
                    }
                }

            });
        }

        binding.updated(this.config);
        verifyNoMoreInteractions(eventPublisher); // write-only item, no event sent
        binding.receiveCommand("Item1", new DecimalType("4"));
        waitForConnectionsReceived(1);
        assertThat(spi.getRegister(0).getValue(), is(equalTo(12)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteNumberItemComplexTransformation2()
            throws BindingConfigParseException, ConfigurationException {

        provider.processBindingConfiguration("test.items", new StringItem("Item1"),
                String.format(">[%1$s:0:trigger=*,transformation=LEN()]", SLAVE_NAME));

        ModbusBindingConfig config = provider.getConfig("Item1");

        // Inject transformation
        for (ItemIOConnection itemIOConnection : config.getWriteConnections()) {
            itemIOConnection.getTransformation().setTransformationHelper(new TransformationHelperWrapper() {

                @Override
                public TransformationService getTransformationService(BundleContext context,
                        String transformationServiceName) {
                    if ("LEN".equals(transformationServiceName)) {
                        return new TransformationService() {

                            @Override
                            public String transform(String multiplier, String arg) throws TransformationException {
                                return String.valueOf(arg.length());
                            }
                        };
                    } else {
                        throw new AssertionError("unexpected transformation");
                    }
                }

            });
        }

        binding.updated(this.config);
        verifyNoMoreInteractions(eventPublisher); // write-only item, no event sent
        binding.receiveCommand("Item1", new StringType("foob"));
        waitForConnectionsReceived(1);
        assertThat(spi.getRegister(0).getValue(), is(equalTo(4)));
        assertThat(spi.getRegister(1).getValue(), is(equalTo(10)));
    }

    @Test
    public void testRegisterWriteNumberItemComplexTransformationTwoOutputs()
            throws BindingConfigParseException, ConfigurationException {

        provider.processBindingConfiguration("test.items", new StringItem("Item1"),
                String.format(">[%1$s:0:transformation=CHAR(0)],>[%1$s:1:transformation=CHAR(1)]", SLAVE_NAME));

        ModbusBindingConfig config = provider.getConfig("Item1");

        // Inject transformation
        for (ItemIOConnection itemIOConnection : config.getWriteConnections()) {
            itemIOConnection.getTransformation().setTransformationHelper(new TransformationHelperWrapper() {

                @Override
                public TransformationService getTransformationService(BundleContext context,
                        String transformationServiceName) {
                    if ("CHAR".equals(transformationServiceName)) {
                        return new TransformationService() {

                            @Override
                            public String transform(String index, String arg) throws TransformationException {
                                int charIdx = arg.charAt(Integer.valueOf(index));
                                return String.valueOf(charIdx);
                            }
                        };
                    } else {
                        throw new AssertionError("unexpected transformation");
                    }
                }

            });
        }

        binding.updated(this.config);
        verifyNoMoreInteractions(eventPublisher); // write-only item, no event sent
        binding.receiveCommand("Item1", new StringType("foob"));
        waitForConnectionsReceived(2);
        // two registers were changed at the same time
        assertThat(spi.getRegister(0).getValue(), is(equalTo(102))); // 102 = f
        assertThat(spi.getRegister(1).getValue(), is(equalTo(111))); // 111 = o
    }

}
