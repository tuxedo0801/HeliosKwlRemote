/*
 * Copyright (C) 2015 Alexander Christian <alex(at)root1.de>. All rights reserved.
 * 
 * This file is part of HeliosKwlRemote.
 *
 *   HeliosKwlRemote is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   slicKnx is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with HeliosKwlRemote.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.root1.helios;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author achristian
 */
public class Helios {

    private static final Logger log = LoggerFactory.getLogger(Helios.class);

    public final byte CONST_BUS_ALL_MAINBOARDS = 0x10;
    public final byte CONST_BUS_ALL_REMOTES = 0x20;

    public final byte CONST_BUS_MAINBOARD1 = 0x11; // 1st of max 15 ventilation units (mainboards 1-F)
    public final byte CONST_BUS_REMOTE1 = 0x21; // 1st of max 15 remote controls /remotes 1-F, default jumper = 1)
    public final byte CONST_BUS_ME = 0x2F; // stealth mode - we are behaving like a regular controle

    Variable[] CONST_MAP_VARIABLES_TO_ID = {
        //           name                      varid type                    bitpos read, write
        new Variable("outside_temp", (byte) 0x32, Variable.Type.temperature, -1, true, false),
        new Variable("exhaust_temp", (byte) 0x33, Variable.Type.temperature, -1, true, false),
        new Variable("inside_temp", (byte) 0x34, Variable.Type.temperature, -1, true, false),
        new Variable("incoming_temp", (byte) 0x35, Variable.Type.temperature, -1, true, false),
        new Variable("bypass_temp", (byte) 0xAF, Variable.Type.temperature, -1, true, true),
        new Variable("fanspeed", (byte) 0x29, Variable.Type.fanspeed, -1, true, true),
        new Variable("max_fanspeed", (byte) 0xA5, Variable.Type.fanspeed, -1, true, true),
        new Variable("min_fanspeed", (byte) 0xA9, Variable.Type.fanspeed, -1, true, true),
        new Variable("power_state", (byte) 0xA3, Variable.Type.bit, 0, true, true),
//        new Variable("bypass_disabled", (byte) 0xA3, Variable.Type.bit, 3, true, true),
        new Variable("bypass", (byte) 0x08, Variable.Type.bit, 1, true, false),
        new Variable("clean_filter", (byte) 0xAB, Variable.Type.dec, -1, true, true),
        new Variable("boost_setting", (byte) 0xAA, Variable.Type.bit, 5, true, true),
        new Variable("boost_on", (byte) 0x71, Variable.Type.bit, 5, true, true),
        new Variable("boost_status", (byte) 0x71, Variable.Type.bit, 6, true, false),
        new Variable("boost_remaining", (byte) 0x79, Variable.Type.dec, -1, true, false),
        new Variable("fan_in_on_off", (byte) 0x08, Variable.Type.bit, 3, true, true),
        new Variable("fan_in_percent", (byte) 0xB0, Variable.Type.percent, -1, true, true),
        new Variable("fan_out_on_off", (byte) 0x08, Variable.Type.bit, 5, true, true),
        new Variable("fan_out_percent", (byte) 0xB1, Variable.Type.percent, -1, true, true),
        new Variable("device_error", (byte) 0x36, Variable.Type.bit, -1, true, false)
    };

    private final Map<String, Variable> variables = new HashMap<>();

    public static final int[] CONST_TEMPERATURE = {
        -74, -70, -66, -62, -59, -56, -54, -52, -50, -48, -47, -46, -44, -43, -42, -41, -40, -39, -38, -37, -36,
        -35, -34, -33, -33, -32, -31, -30, -30, -29, -28, -28, -27, -27, -26, -25, -25, -24, -24, -23, -23, -22,
        -22, -21, -21, -20, -20, -19, -19, -19, -18, -18, -17, -17, -16, -16, -16, -15, -15, -14, -14, -14, -13,
        -13, -12, -12, -12, -11, -11, -11, -10, -10, -9, -9, -9, -8, -8, -8, -7, -7, -7, -6, -6, -6, -5, -5, -5, -4,
        -4, -4, -3, -3, -3, -2, -2, -2, -1, -1, -1, -1, 0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 7, 7,
        7, 8, 8, 8, 9, 9, 9, 10, 10, 10, 11, 11, 11, 12, 12, 12, 13, 13, 13, 14, 14, 14, 15, 15, 15, 16, 16, 16, 17, 17,
        18, 18, 18, 19, 19, 19, 20, 20, 21, 21, 21, 22, 22, 22, 23, 23, 24, 24, 24, 25, 25, 26, 26, 27, 27, 27, 28, 28,
        29, 29, 30, 30, 31, 31, 32, 32, 33, 33, 34, 34, 35, 35, 36, 36, 37, 37, 38, 38, 39, 40, 40, 41, 41, 42, 43, 43,
        44, 45, 45, 46, 47, 48, 48, 49, 50, 51, 52, 53, 53, 54, 55, 56, 57, 59, 60, 61, 62, 63, 65, 66, 68, 69, 71, 73,
        75, 77, 79, 81, 82, 86, 90, 93, 97, 100, 100, 100, 100, 100, 100, 100, 100, 100};

    private Socket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private boolean isConnected;

    // delay between two waitForSilence+send commands
    private final long SEND_DELAY = 1;

    // delay before retry reading
    private final long RETRY_DELAY = 10;
    
    // init lastSend so, that 1st send can run immediately
    private long lastSend = System.currentTimeMillis() - SEND_DELAY;
    private final int port;
    private final String host;
    private boolean reconnect;
    
    private boolean restoreFanspeed;

    public Helios(String host, int port) {
        this.host = host;
        this.port = port;

        // fill into lookupable hashmap
        for (Variable var : CONST_MAP_VARIABLES_TO_ID) {
            variables.put(var.name, var);
        }

    }
    
    public List<String> getVariables(){
        return new ArrayList(variables.keySet());
    }
    
    public Map<String, HeliosVariableCache> getCachedVariables(int maxtime) {
        Map<String, HeliosVariableCache> map = new HashMap<>();
        for (String varname : variables.keySet()) {
            map.put(varname, new HeliosVariableCache(this, varname, maxtime));
        }
        return map;
    }
    
    public Variable getVariable(String variableName) {
        return variables.get(variableName);
    }

    public void connect() throws IOException {
        if (!reconnect)
            log.info("Connecting...");
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
//        socket.setSoTimeout(2000); // ms
        inputStream = new BufferedInputStream(socket.getInputStream());
        outputStream = new BufferedOutputStream(socket.getOutputStream());
        isConnected = true;
        if (!reconnect)
            log.info("Connected!");
    }

    public void disconnect() throws IOException {
        if (!reconnect)
            log.info("Disconnecting...");
        if (inputStream != null) {
            inputStream.close();
        }
        if (outputStream != null) {
            outputStream.close();
        }
        if (socket != null) {
            socket.close();
        }
        isConnected = false;
        if (!reconnect)
            log.info("Disconnected!");
    }

    /**
     *
     * @param sender
     * @param receiver
     * @param function 0x00 = read, otherwise register address to write to
     * @param value if function=0x00, register address to read, otherwise value
     * to write
     * @return
     */
    private byte[] createTelegram(byte sender, byte receiver, byte function, byte value) {
        byte[] telegram = new byte[]{1, sender, receiver, function, value, 0};
        telegram[5] = calculateCRC(telegram);
        return telegram;
    }

    private boolean waitForSilence() throws SocketException, IOException {

        long time = System.currentTimeMillis();
        if (time - lastSend < SEND_DELAY) {
            try {
                long sleep = SEND_DELAY - (time - lastSend);
                log.debug("#### Sleep for SEND_DELAY: {} ms", sleep);
                Thread.sleep(sleep);
            } catch (InterruptedException ex) {
            }
        }

        log.debug("Waiting for silence...");
        long start = System.currentTimeMillis();
        /*
         Modbus RTU only allows one master (client which controls communication).
         So lets try to wait a bit and jump in when nobody's speaking.
         Modbus defines a waittime of 3,5 Characters between telegrams:
         (1/9600baud * (1 Start bit + 8 Data bits + 1 Parity bit + 1 Stop bit) 
         => about 4ms
         Lets go with 7ms!  ;O)
         */
        boolean gotSlot = false;
        int backupTimeout = socket.getSoTimeout();
        long end = System.currentTimeMillis() + 3000;
        socket.setSoTimeout(7);
        while (end > System.currentTimeMillis() && !gotSlot) {
            try {
                inputStream.read();
                gotSlot = false;
            } catch (SocketTimeoutException ex) {
                gotSlot = true;
            }
        }
        socket.setSoTimeout(backupTimeout);
        long stop = System.currentTimeMillis();
        log.debug("Waiting fo silence....*done* gotSlot={} waited: {} ms", gotSlot, (stop - start));
        return gotSlot;
    }

    private void sendTelegram(byte[] telegram) throws IOException {
        outputStream.write(telegram);
        outputStream.flush();
        lastSend = System.currentTimeMillis();
    }

    private byte readTelegram(byte sender, byte receiver, byte datapoint) throws IOException, TelegramException {
        log.debug("Reading telegram...");
        long start = System.currentTimeMillis();
        /*
         sometimes a lot of garbage is received...so lets get a bit robust
         and read a bit of this junk and see whether we are getting something
         useful out of it!
         How long does it take until something useful is received???
         */
        long timeout = System.currentTimeMillis() + 100;

        byte[] telegram = new byte[]{0, 0, 0, 0, 0, 0};

        while (isConnected && timeout > System.currentTimeMillis()) {
            try {
                int chr = inputStream.read();

                log.trace("read: {}(dec)|{}", chr, String.format("%02x(hex)", chr));

                // vorne ein byte rausschieben
                System.arraycopy(telegram, 1, telegram, 0, telegram.length - 1);
                // hinten ein byte anfügen
                telegram[5] = (byte) chr;

                log.trace("Telegram array now is   [{}]", telegramToString(telegram));
                log.trace("Telegram array expected [{} {} {} {} {} {}]", new Object[]{
                    "01",
                    String.format("%02x", sender),
                    String.format("%02x", receiver),
                    String.format("%02x", datapoint),
                    "??",
                    String.format("%02x", calculateCRC(telegram))});

                // Telegrams always start with a 0x01, is the CRC valid?, ...
                if (telegram[0] == 0x01
                        && telegram[1] == sender
                        && telegram[2] == receiver
                        && telegram[3] == datapoint
                        && telegram[5] == calculateCRC(telegram)) {
                    long end = System.currentTimeMillis();
                    log.trace("****** Time taken to read: {} ms", (end - start));
                    log.trace("Telegram received [{}]", telegramToString(telegram));
                    log.debug("Reading telegram...*done*");
                    return telegram[4];
                }
            } catch (SocketTimeoutException ex) {
                throw new TelegramException("Socket-Timeout while reading telegram", ex);
            }
        }

        throw new TelegramException("Protocol-Timeout while reading telegram");
    }

    private byte calculateCRC(byte[] telegram) {
        int sum = 0;
        // sum bytes 0..4, exclude byte #5 which is crc
        for (int i = 0; i < telegram.length - 1; i++) {
            sum += telegram[i];
        }
        return (byte) (sum % 256);
    }

    private String telegramToString(byte[] telegram) {
        return String.format("%02x %02x %02x %02x %02x %02x", telegram[0], telegram[1], telegram[2], telegram[3], telegram[4], telegram[5]);
    }

    private int convertFromRawValue(String varname, byte rawvalue) {
        int value;
        Variable vardef = variables.get(varname);

        switch (vardef.type) {
            case temperature:
                value = CONST_TEMPERATURE[rawvalue & 0xFF];
                break;
            case fanspeed:
                switch (rawvalue) {
                    case 0x01:
                        value = 1;
                        break;
                    case 0x03:
                        value = 2;
                        break;
                    case 0x07:
                        value = 3;
                        break;
                    case 0x0F:
                        value = 4;
                        break;
                    case 0x1F:
                        value = 5;
                        break;
                    case 0x3F:
                        value = 6;
                        break;
                    case 0x7F:
                        value = 7;
                        break;
                    case (byte) 0xFF:
                        value = 8;
                        break;
                    default:
                        throw new IllegalArgumentException("raw value '" + rawvalue + "(dec)'/'" + String.format("%02x", rawvalue) + "(hex)' not known for fanspeed.");

                }
                break;
            case bit:
                value = rawvalue >> vardef.bitposition & 0x01;
                break;
            case dec:
            case percent:
                value = rawvalue;
                break;
            default:
                // should not happen, as we use reliable enums
                throw new IllegalArgumentException("Unsupported variable type: " + vardef.type + ". Pls. contact developer ...");
        }

        return value;
    }

    /**
     *
     * @param varname
     * @param value
     * @param prevvalue required for bit-variables, otherwise ignored
     * @return
     * @throws IllegalArgumentException
     */
    private int convertFromValue(String varname, int value, byte prevvalue) throws IllegalArgumentException {
        int rawvalue;
        Variable vardef = variables.get(varname);
        switch (vardef.type) {
            case temperature:
                rawvalue = Arrays.binarySearch(CONST_TEMPERATURE, value);
                break;
            case fanspeed:
                switch (value) {
                    case 1:
                        rawvalue = 0x01;
                        break;
                    case 2:
                        rawvalue = 0x03;
                        break;
                    case 3:
                        rawvalue = 0x07;
                        break;
                    case 4:
                        rawvalue = 0x0F;
                        break;
                    case 5:
                        rawvalue = 0x1F;
                        break;
                    case 6:
                        rawvalue = 0x3F;
                        break;
                    case 7:
                        rawvalue = 0x7F;
                        break;
                    case 8:
                        rawvalue = 0xFF;
                        break;
                    default:
                        throw new IllegalArgumentException("Illegal fanspeed detected: " + value + ". Must be in range [1..8].");
                }
                break;
            case bit:
                switch (value) {
                    case 1: // On, True, 1
                        rawvalue = prevvalue | (1 << vardef.bitposition);
                        break;
                    default: // everything else is mapped to OFF/False/0
                        rawvalue = prevvalue & ~(1 << vardef.bitposition);
                        break;
                }
                break;
            case dec:
            case percent:
                rawvalue = value;
                break;
            default:
                // should not happen, as we use reliable enums
                throw new IllegalArgumentException("Unsupported variable type: " + vardef.type + ". Pls. contact developer ...");

        }
        return rawvalue;
    }

    /**
     *
     * @param varname the variable to write to
     * @param value the value to write
     * @throws IOException in case of problems with stream communication
     * @throws TelegramException in case of read/write problems with telegram
     * @throws IllegalArgumentException in case of illegal argument
     * @throws UnsupportedOperationException in case of unsupported operation
     * read/write
     */
    public synchronized void writeValue(String varname, int value) throws IOException, TelegramException, IllegalArgumentException, UnsupportedOperationException {
        log.debug("Writing value {} to '{}'",value, varname);
        Variable var = variables.get(varname);
        
        if (var == null) {
            throw new IllegalArgumentException("Variable '" + varname + "' unknown.");
        }

        if (!var.write) {
            throw new UnsupportedOperationException("Variable '" + varname + "' may not be written!");
        }
        
        
        if (restoreFanspeed && varname.equals("boost_on")) {
            
            final int lastSpeed = readValue("fanspeed");
            
            Thread restoreThread = new Thread("RestoreFanspeed"){

                @Override
                public void run() {
                    try {
                        Thread.sleep(5000);
                        int boostOn = readValue("boost_status");
                        if (boostOn == 1) {
                            
                            int remaining = readValue("boost_remaining");
                            if (remaining>0) {
                                while (remaining>0) {
                                    log.info("Will restore fanspeed in {} mins to {}", remaining, lastSpeed);
                                    Thread.sleep(65*1000); // sleep a bit more than 1min
                                    remaining = readValue("boost_remaining");    
                                }
                                writeValue("fanspeed", lastSpeed);
                            } else {
                                log.warn("Seems that boost was on, but remaining time is already (or still?) 0mins?!");
                            }
                                
                            
                        } else {
                            log.warn("Seems that setting boost_on=1 did not succeed. boost_status is still at 0.");
                        }
                        
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    } catch (TelegramException ex) {
                        ex.printStackTrace();
                    } 
                }
            };
            restoreThread.start();
        }

        // will contain our value converted to raw
        int rawvalue;

        /*
         * if we have got to write a single bit, we need the current (byte) 
         * value to reproduce the other bits...
         */
        if (var.type == Variable.Type.bit) {

            waitForSilence();
            // Send poll request
            byte[] telegram = createTelegram(CONST_BUS_ME, CONST_BUS_MAINBOARD1, (byte) 0, var.varid);
            sendTelegram(telegram);
            // Read response
            byte currentval = readTelegram(CONST_BUS_MAINBOARD1, CONST_BUS_ME, var.varid);

            rawvalue = convertFromValue(varname, value, /* previous bits */ currentval);
        } else {
            // for all other types, the previous value is not relevant, 
            // we can directly convert our value to raw value for sending
            rawvalue = convertFromValue(varname, value, /* value will be ignored, as var is not a bit-type var */ (byte) 0);
        }

        // send the new value    
        if (waitForSilence()) {

            // Broadcasting value to all remote control boards
            byte[] telegram = createTelegram(CONST_BUS_ME, CONST_BUS_ALL_REMOTES, var.varid, (byte) rawvalue);
            sendTelegram(telegram);

            // Broadcasting value to all mainboards
            telegram = createTelegram(CONST_BUS_ME, CONST_BUS_ALL_MAINBOARDS, var.varid, (byte) rawvalue);
            sendTelegram(telegram);

            // Writing value to 1st mainboard
            telegram = createTelegram(CONST_BUS_ME, CONST_BUS_MAINBOARD1, var.varid, (byte) rawvalue);
            sendTelegram(telegram);

            // Send checksum a second time
            sendTelegram(new byte[]{telegram[5]});

            // #### Special treatment to switch the remote controls on again:
            if (var.varid == 0xA3 && var.bitposition == 0) {

                log.debug("On/Off command - special treatment for the remote controls");
                telegram = createTelegram(CONST_BUS_ME, CONST_BUS_ALL_REMOTES, var.varid, (byte) rawvalue);
                sendTelegram(telegram);

                telegram = createTelegram(CONST_BUS_ME, CONST_BUS_REMOTE1, var.varid, (byte) rawvalue);
                sendTelegram(telegram);

                sendTelegram(new byte[]{telegram[5]});

            }
            // #####
        } else {
            throw new TelegramException("Sending value to ventilation system failed. No free slot for sending telegrams available.");
        }
        log.debug("Writing *done*");
    }

    public synchronized int readValue(String varname) throws IOException, TelegramException {
        Variable var = variables.get(varname);

        if (var == null) {
            System.err.println("Helios: Variable '" + varname + "' unknown.");
            return -1;
        }
        if (!var.read) {
            System.err.println("Variable '" + varname + "' may not be read!");
            return -1;
        }

        log.debug("Helios: Reading value: {}", varname);
//
        int count = 0;
        int maxCount = 10;
        boolean problemReading = false;
        while (count < maxCount) {
            log.debug("Try to read, attempt #{}", count);
            try {
                if (waitForSilence()) {
                    // Send poll request
                    byte[] telegram = createTelegram(CONST_BUS_ME, CONST_BUS_MAINBOARD1, (byte) 0, var.varid);
                    sendTelegram(telegram);
                    
                    // Read response, reading can cause expception!
                    byte rawvalue = readTelegram(CONST_BUS_MAINBOARD1, CONST_BUS_ME, var.varid);
                    if (problemReading) {
                        log.debug("Now reading variable '{}' was successful", varname);
                    }
                    int value = convertFromRawValue(varname, rawvalue);

                    
                    log.debug(String.format("Value for %s (%02x) received: %02x|%s|%d --> converted = %d",
                            varname,
                            var.varid,
                            rawvalue,
                            String.format("%8s", Integer.toBinaryString(rawvalue & 0xFF)).replace(" ", "0"),
                            rawvalue,
                            value
                    ));
                    return value;

                } else {
                    throw new TelegramException("Reading value from ventilation system failed. No free slot to send poll request available.");
                }
            } catch (Exception ex) {
                
                log.debug("Did not get answer in time for '"+varname+"' in attempt #"+count+"... Wait and go for next attempt. ExceptionMessage={}", ex.getMessage());
                problemReading = true;
                count++;
                try {
                    Thread.sleep(RETRY_DELAY);
                } catch (InterruptedException ex1) {
                }
            }
        } // end of while
        reconnect();
        throw new TelegramException("Error while reading '"+varname+"'. Max attempts "+maxCount+" reached.");
    }

    protected void dump() throws IOException, TelegramException {
        for (Map.Entry<String, Variable> entrySet : variables.entrySet()) {
            String key = entrySet.getKey();
            int readValue = readValue(key);
            System.out.println(key + " = " + readValue);
        }
    }

    private void reconnect() {
        log.debug("Reconnect ...");
        reconnect = true;
        try {
            disconnect();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
        try {
            connect();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        reconnect = false;
        log.debug("Reconnect ...*done*");
    }
    
    public void setRestoreFanspeedAfterBoost(boolean flag) {
        restoreFanspeed = flag;
    }
    
    public static void main(String[] args) throws IOException, TelegramException {
        long start = System.currentTimeMillis();
        Helios h = new Helios("192.168.200.4", 4000);
        h.connect();
//        int i = h.readValue("bypass_disabled");
//        System.out.println("bypass_disabled = "+i);
        h.dump();
        h.disconnect();
        long end = System.currentTimeMillis();
        System.out.println("time: "+(end-start));
        
    }

}
