/***
 * Copyright 2002-2010 jamod development team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***/

package org.openhab.binding.modbus;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.io.ModbusSerialTransaction;
import net.wimpi.modbus.io.ModbusTCPTransaction;
import net.wimpi.modbus.io.ModbusTransaction;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.msg.ReadMultipleRegistersRequest;
import net.wimpi.modbus.msg.ReadMultipleRegistersResponse;
import net.wimpi.modbus.net.ModbusSlaveConnection;
import net.wimpi.modbus.net.SerialConnection;
import net.wimpi.modbus.net.TCPMasterConnection;
import net.wimpi.modbus.procimg.Register;
import net.wimpi.modbus.util.SerialParameters;

/**
 * Class that implements a simple commandline
 * tool for reading from a slave
 *
 */
public class CommandLineTest {
    // private static class Logger {
    // public void info(String msg, Object... args) {
    // System.out.println(String.format("INFO: " + msg, args));
    // }
    //
    // public void error(String msg, Object... args) {
    // System.out.println(String.format("ERROR: " + msg, args));
    // }
    // }
    //
    // private static final Logger logger = new Logger();

    private static final Logger logger = LoggerFactory.getLogger(CommandLineTest.class);

    private static void printUsage() {
        /**
         * Emulating modpoll CLI (see below)
         *
         * Major difference being zero-based indexing
         *
         * Usage: modpoll [options] serialport|host
         * Arguments:
         * serialport Serial port when using Modbus ASCII or Modbus RTU protocol
         * COM1, COM2 ... on Windows
         * /dev/ttyS0, /dev/ttyS1 ... on Linux
         * /dev/ser1, /dev/ser2 ... on QNX
         * host Host name or dotted ip address when using MODBUS/TCP protocol
         * General options:
         * -m ascii Modbus ASCII protocol
         * -m rtu Modbus RTU protocol (default)
         * -m tcp MODBUS/TCP protocol
         * -m enc Encapsulated Modbus RTU over TCP
         * -a # Slave address (1-255, 1 is default)
         * -r # Start reference (1-65536, 100 is default)
         * -c # Number of values to poll (1-100, 1 is default)
         * -t 0 Discrete output (coil) data type
         * -t 1 Discrete input data type
         * -t 3 16-bit input register data type
         * -t 3:hex 16-bit input register data type with hex display
         * -t 3:int 32-bit integer data type in input register table
         * -t 3:mod 32-bit module 10000 data type in input register table
         * -t 3:float 32-bit float data type in input register table
         * -t 4 16-bit output (holding) register data type (default)
         * -t 4:hex 16-bit output (holding) register data type with hex display
         * -t 4:int 32-bit integer data type in output (holding) register table
         * -t 4:mod 32-bit module 10000 type in output (holding) register table
         * -t 4:float 32-bit float data type in output (holding) register table
         * -i Slave operates on big-endian 32-bit integers
         * -f Slave operates on big-endian 32-bit floats
         * -1 Poll only once, otherwise poll every second
         * -e Use Daniel/Enron single register 32-bit mode
         * -0 First reference is 0 (PDU addressing) instead 1
         * Options for MODBUS/TCP:
         * -p # TCP port number (502 is default)
         * Options for Modbus ASCII and Modbus RTU:
         * -b # Baudrate (e.g. 9600, 19200, ...) (9600 is default)
         * -d # Databits (7 or 8 for ASCII protocol, 8 for RTU)
         * -s # Stopbits (1 or 2, 1 is default)
         * -p none No parity
         * -p even Even parity (default)
         * -p odd Odd parity
         * -4 # RS-485 mode, RTS on while transmitting and another # ms after
         * -o # Time-out in seconds (0.01 - 10.0, 1.0 s is default)
         */
        System.out.printf("\nUsage:\n    "//
                + "java -cp \"/path/to/openhab1/server/plugins/*:path/to/org.openhab.binding.modbus-1.9.0-SNAPSHOT.jar\" org.openhab.binding.modbus.CommandLineTest [commands]  serialport|host"//
                + "Arguments:\n"//
                + "- serialport   Serialport to use, e.g. COM1, COM2 or /dev/ttyUSB0\n"//
                + "- host hostname to connect to (modbus tcp)\n"//
                + "Options:\n"//
                + "-m rtu   Modbus RTU (implies serialport)\n"//
                + "-m tcp   Modbus TCP (implies host)\n"//
                + "-a #     slave id\n"//
                + "-r #     start index, zero based (default 0)\n"//
                + "-c #     number of values to poll (default 1)\n"//
                + "-t 4     16-bit holding register data\n"//
                + "-rr #     retries, default 1\n"//
                + "\n\n"//
                + "Options for TCP:\n"//
                + "-p # TCP port number (default 502)\n"//
                + "Options for serial:\n"//
                + "-b # baud rate\n"//
                + "-d # data bits\n"//
                + "-s # stop bits\n"//
                + "-p # parity\n"//
                + "\n");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage();
            System.exit(1);
        }

        ModbusSlaveConnection connection = null;
        ModbusRequest request = null;
        ModbusTransaction transaction = null;

        String destination = args[args.length - 1];
        Map<String, String> options = parseOptions(args);
        int ref = Integer.valueOf(options.getOrDefault("-r", "0"));

        request = new ReadMultipleRegistersRequest(ref, Integer.valueOf(options.getOrDefault("-c", "1")));
        request.setUnitID(Integer.valueOf(options.getOrDefault("-a", "1")));

        String type = options.get("-m");
        if (type == null) {
            logger.error("-m is mandatory!");
            printUsage();
            System.exit(1);
        }
        if (type.equals("rtu")) {
            SerialParameters serialParameters = new SerialParameters();
            serialParameters.setPortName(destination);
            serialParameters.setBaudRate(options.getOrDefault("-b", "19200"));
            serialParameters.setDatabits(options.getOrDefault("-d", "8"));
            serialParameters.setStopbits(options.getOrDefault("-s", "1"));
            serialParameters.setParity(options.getOrDefault("-p", "even"));
            serialParameters.setEncoding("rtu");
            serialParameters.setReceiveTimeoutMillis(10000);
            connection = new SerialConnection(serialParameters);
            logger.info("Serial parameters used in connection {}", serialParameters);
            transaction = new ModbusSerialTransaction();
        } else {
            assert "tcp".equals(type);
            connection = new TCPMasterConnection(InetAddress.getByName(destination),
                    Integer.valueOf(options.getOrDefault("-p", "502")), 5000);
            logger.info("TCP host:port: {}:{}", ((TCPMasterConnection) connection).getAddress(),
                    ((TCPMasterConnection) connection).getPort());
            transaction = new ModbusTCPTransaction((TCPMasterConnection) connection);
        }
        transaction.setRequest(request);

        try {
            logger.info("Will try to connect to {}", connection);
            boolean connected = connection.connect();
            logger.info("Connected: {}", connected);

            // sleep for a while for slave to initialize itself
            logger.info("Sleep for 1s to give slave some time");
            Thread.sleep(1000);

            if (type.equals("tcp")) {
                ((ModbusTCPTransaction) transaction).setConnection((TCPMasterConnection) connection);
            } else {
                ((ModbusSerialTransaction) transaction).setSerialConnection((SerialConnection) connection);
            }
            transaction.setRetries(Integer.valueOf(options.getOrDefault("-rr", "1")));
            transaction.setRetryDelayMillis(1000);
            logger.info("Executing transactions");
            transaction.execute();
            ModbusResponse response = transaction.getResponse();
            logger.info("Got response (FC{}): HEX={}", response.getFunctionCode(), response.getHexMessage());
            ReadMultipleRegistersResponse typedResponse = (ReadMultipleRegistersResponse) response;

            int i = 0;
            for (Register register : typedResponse.getRegisters()) {
                logger.info("Register[{}] as unsigned short={}", ref + i, register.toUnsignedShort());
                i++;
            }
        } finally {
            connection.resetConnection();
        }

    }// main

    private static Map<String, String> parseOptions(String[] args) {
        logger.info("Parsing options...");
        Map<String, String> options = new HashMap<String, String>();

        try {
            if (args.length % 2 != 1) {
                throw new IllegalArgumentException(String.format("Expecting odd number of arguments!"));
            }

            String optionKey = null;
            String optionValue = null;
            for (int i = 0; i < args.length - 1; i++) {
                if (i % 2 == 0) {
                    // even arguments should be the -t, -r etc.
                    if (args[i].charAt(0) != '-') {
                        throw new IllegalArgumentException(
                                String.format("Invalid argument '%s', expected option starting with -", args[i]));
                    }
                    optionKey = args[i];
                } else {
                    optionValue = args[i];
                    options.put(optionKey, optionValue);
                }
            }

        } catch (Exception ex) {
            ex.printStackTrace();

            printUsage();
            System.exit(1);
        }
        return options;
    }

}
