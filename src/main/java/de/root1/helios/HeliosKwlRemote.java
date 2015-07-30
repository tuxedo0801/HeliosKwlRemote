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

import de.root1.slicknx.GroupAddressEvent;
import de.root1.slicknx.GroupAddressListener;
import de.root1.slicknx.Knx;
import de.root1.slicknx.KnxException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author achristian
 */
public class HeliosKwlRemote {

    private final Logger log = LoggerFactory.getLogger(HeliosKwlRemote.class);
    /**
     * 5min -> default value
     */
    private static final int DEFAULT_STANDBY_DELAY = 300000;
    private static final int MINIMUM_STANDBY_DELAY = 15000; // 10sec
    private static final int STANDBY_MODE_DISABLED = -1;

    static {
        if (System.getProperty("java.util.logging.config.file") == null) {
            System.out.println("Please specify logfile by passing '-Djava.util.logging.config.file=<logconfig-file>' to JVM to get advanced log possibilities.");
            LogFormatter formatter = new LogFormatter();
            Handler[] handlers = java.util.logging.Logger.getLogger("").getHandlers();
            for (Handler handler : handlers) {
                handler.setFormatter(formatter);
            }
        }
    }
    private final Helios h;
    private final Knx knx;
    private String individualAddress;
    private Properties p;

    private final Timer t = new Timer("StandbyStateSwitcher", true);
    private boolean standbySwitcherScheduled;
    private StandbySwitcher standbySwitcher = new StandbySwitcher(false);

    private boolean currentStandbyState = false; // default: not in standby
    private int standbySpeed = STANDBY_MODE_DISABLED; //default: no stand by 
    private long standbyDelay = DEFAULT_STANDBY_DELAY;
    private long lastStandbySwitch = System.currentTimeMillis();

    private int lastFanspeed;

    private Map<String, HeliosVariableCache> cachedVariables;

    class StandbySwitcher extends TimerTask {

        private boolean targetStandbyState;

        StandbySwitcher(boolean targetStandbyState) {
            this.targetStandbyState = targetStandbyState;
        }

        @Override
        public void run() {

            if (targetStandbyState == currentStandbyState) {
                log.debug("Nothing to do for standby switcher, as target state equals current state: {}", currentStandbyState);
                standbySwitcherScheduled = false;
                return;
            }

            try {

                // check if boost is running
                int boostRemain = h.readValue("boost_remaining");
                if (boostRemain > 0) {
                    log.info("Cannot switch standby state while boost is running. Will delay it.");
                    triggerStandbyState(targetStandbyState);
                    return;
                }

                if (standbySpeed == STANDBY_MODE_DISABLED) {

                    log.warn("received standby-trigger '{}', but standby is disabled! Will skip this.", targetStandbyState);

                } else {

                    log.info("Switching standby state from {} to {}", currentStandbyState, targetStandbyState);

                    // switching into STANDBY
                    if (targetStandbyState && !currentStandbyState) {

                        // store fanspeed before going into standby
                        lastFanspeed = h.readValue("fanspeed");
                        log.info("Store last fanspeed for later restore: {}", lastFanspeed);

                        switch (standbySpeed) {
                            case 0:
                                log.info("Switching power-state to OFF");
                                h.writeValue("power_state", 0);
                                break;
                            case 1:
                            case 2:
                            case 3:
                            case 4:
                            case 5:
                            case 6:
                            case 7:
                            case 8:
                                log.info("Switching fanspeed to standby speed {}", standbySpeed);
                                h.writeValue("fanspeed", standbySpeed);
                                break;
                        }
                    } else // switching into WORKING
                    if (!targetStandbyState && currentStandbyState) {
                        switch (standbySpeed) {
                            case 0:
                                log.info("Switching power-state to ON (takes some time...)");
                                h.writeValue("power_state", 1);
                            default:
                                log.info("Restore last fanspeed: {}", lastFanspeed);
                                h.writeValue("fanspeed", lastFanspeed);
                                break;
                        }
                    }

                    // toggle standby state
                    currentStandbyState = !currentStandbyState;
                    lastStandbySwitch = System.currentTimeMillis();
                    standbySwitcherScheduled = false;
                    log.info("*done*");
                }

            } catch (Throwable ex) {
                log.error("Error triggering idle state. Will retrigger in 10sec.", ex);
                t.schedule(this, 10000); // retrigger after 10sec.
            }

        }

        public boolean getTargetStandbyState() {
            return targetStandbyState;
        }

        private void setTargetStandbyState(boolean targetStandbyState) {
            this.targetStandbyState = targetStandbyState;
        }

        protected StandbySwitcher createNew() {
            standbySwitcher = new StandbySwitcher(targetStandbyState);
            return standbySwitcher;
        }

    }

    public HeliosKwlRemote(File configfile) throws IOException, KnxException {
        readConfig(configfile);

        int port = getIntFromProperties("port", 4000);
        String host = p.getProperty("host");
        boolean restoreFanspeedAfterBoost = getBooleanFromProperties("restore_fanspeed_after_boost", false);
        int keeptime = getIntFromProperties("cache_keep", 1000);
        boolean sendOnUpdate = getBooleanFromProperties("send_on_update", false);
        standbySpeed = getIntFromProperties("standby_speed", -1);
        standbyDelay = getIntFromProperties("standby_delay", DEFAULT_STANDBY_DELAY);
        if (standbyDelay < MINIMUM_STANDBY_DELAY) {
            standbyDelay = MINIMUM_STANDBY_DELAY;
            log.info("Increasing standbydelay to minimum allowed value: {}ms", MINIMUM_STANDBY_DELAY);
        }

        log.info("Connecting to Helios KWL on {}:{}", p.getProperty("host"), p.getProperty("port"));
        h = new Helios(host, port);
        h.setRestoreFanspeedAfterBoost(restoreFanspeedAfterBoost);
        h.connect();
        knx = new Knx();
        log.info("Initialize cache variables with {}ms cache-keep-time", keeptime);
        cachedVariables = h.getCachedVariables(keeptime);

        Thread updater = new Thread() {

            @Override
            public void run() {
                while (!knx.hasIndividualAddress()) {
                    try {
                        log.info("Waiting for KNX individual address");
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        interrupt();
                    }
                }
                log.info("running!");
                while (!interrupted()) {
                    for (Map.Entry<String, HeliosVariableCache> entrySet : cachedVariables.entrySet()) {
                        String varname = entrySet.getKey();

                        String ga = p.getProperty("knx_ga." + varname, "not known");
                        if (!ga.equals("not known")) {
                            HeliosVariableCache cachedVariable = entrySet.getValue();
                            try {
                                int oldValue = cachedVariable.getValue();
                                if (cachedVariable.hasChanged()) {

                                    int newValue = cachedVariable.getValue();

                                    log.info("'{}' changed value from {} to {}. Sending update to {}", new Object[]{varname, oldValue, newValue, ga});

                                    Variable variable = h.getVariable(varname);
                                    send(false, newValue, ga, variable);

                                }

                            } catch (Exception ex) {
                                log.error("Error updating variable '" + varname + "'", ex);
                                ex.printStackTrace();
                            }
                        }

                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        interrupt();
                    }
                }
                log.info("Thread interrupted...");
            }

        };
        updater.setName("SendOnUpdate");

        if (sendOnUpdate) {
            log.info("Starting SendOnUpdate thread");
            updater.start();
        } else {
            log.info("Not using SendOnUpdate thread");
        }

        Enumeration<String> propertyNames = (Enumeration<String>) p.propertyNames();
        while (propertyNames.hasMoreElements()) {
            String prop = propertyNames.nextElement();

            if (prop.startsWith("default.")) {
                String varname = prop.split("\\.")[1];
                String value = p.getProperty(prop);

                try {
                    log.info("Setting default: {} -> {}", varname, value);
                    h.writeValue(varname, Integer.parseInt(value));
                } catch (TelegramException ex) {
                    ex.printStackTrace();
                } catch (IllegalArgumentException ex) {
                    ex.printStackTrace();
                } catch (UnsupportedOperationException ex) {
                    ex.printStackTrace();
                }
            } else if (prop.equals("knx_pa.softwaredevice")) {
                individualAddress = p.getProperty(prop);
                log.info("Setting individual address to {}", individualAddress);
                try {
                    knx.setIndividualAddress(individualAddress);
                } catch (KnxException ex) {
                    ex.printStackTrace();
                }
            } else if (prop.startsWith("knx_ga.")) {
                final String varname = prop.split("\\.")[1];
                final String ga = p.getProperty(prop);
                log.info("Register listener for '{}' on {}", varname, ga);
                knx.addGroupAddressListener(ga, new GroupAddressListener() {

                    @Override
                    public void readRequest(GroupAddressEvent event) {
                        if (knx.hasIndividualAddress()) {
                            Variable variable = h.getVariable(varname);
                            if (variable != null) {
                                try {
                                    //                                int value = h.readValue(varname);
                                    int value = cachedVariables.get(varname).forcedGet();
                                    log.info("ReadRequest for '{}' --> {}", varname, value);
                                    send(true, value, ga, variable);

                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                } catch (TelegramException ex) {
                                    ex.printStackTrace();
                                } catch (KnxException ex) {
                                    ex.printStackTrace();
                                }
                            } else {
                                switch (varname) {
                                    case "standby":
                                        try {
                                            log.info("ReadRequest for '{}' --> {}", varname, currentStandbyState);
                                            knx.writeBoolean(true, ga, currentStandbyState);
                                        } catch (KnxException ex) {
                                            ex.printStackTrace();
                                        }
                                        break;
                                }
                            }
                        }
                    }

                    @Override
                    public void readResponse(GroupAddressEvent event) {
                    }

                    @Override
                    public void write(GroupAddressEvent event) {
                        // if event is not from us and is not a response
                        if (knx.hasIndividualAddress() & !event.getSource().equals(individualAddress) && event.getType() == GroupAddressEvent.Type.GROUP_WRITE) {
                            Variable variable = h.getVariable(varname);
                            if (variable != null) {
                                int value = -1;
                                try {

                                    switch (variable.type) {
                                        case fanspeed: // DPT5.005
                                            value = event.asUnscaled();
                                            break;
                                        case dec: // DPT6.010
                                            value = event.asDpt6();
                                            break;
                                        case temperature: // DPT9
                                            value = (int) event.as2ByteFloat();
                                            break;
                                        case percent: // DPT5.001
                                            value = event.asScaled();
                                            break;
                                        case bit: // DPT1
                                            value = (event.asBool() ? 1 : 0);
                                            break;
                                    }
                                    log.info("Write for '{}' --> {}", varname, value);
                                    h.writeValue(varname, value);

                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                } catch (TelegramException ex) {
                                    ex.printStackTrace();
                                } catch (KnxException ex) {
                                    ex.printStackTrace();
                                }
                            } else {
                                switch (varname) {
                                    case "standby": {
                                        try {
                                            triggerStandbyState(event.asBool());
                                        } catch (Exception ex) {
                                            ex.printStackTrace();
                                        }
                                    }
                                    break;
                                }

                            }
                        }
                    }

                });
            }
        }
    }

    private int getIntFromProperties(String name, int defaultValue) {
        String stringValue = p.getProperty(name, Integer.toString(defaultValue)).trim();
        try {
            int value = Integer.parseInt(stringValue);
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Error reading config: {} does not contain a readable integer value: '{}'. Will continue with default: {}", name, stringValue, defaultValue);
            return defaultValue;
        }
    }

    private boolean getBooleanFromProperties(String name, boolean defaultValue) {
        String stringValue = p.getProperty(name, Boolean.toString(defaultValue)).trim();
        boolean value = Boolean.parseBoolean(stringValue);
        return value;
    }

    private void triggerStandbyState(boolean standby) {

        boolean needToSchedule = false;
        long delayToSchedule = 0;

        /* ******************
         * decide what to do
         */
        if (!standbySwitcherScheduled) {

            // standby switcher not yet scheduled
            // if need to schedule
            if (currentStandbyState != standby) {
                needToSchedule = true;
                standbySwitcher.setTargetStandbyState(standby);
            } else {
                log.warn("target standby state matches current standby state ({}), no switch scheduled yet. Illegal state detected?", standby);
                return;
            }

        } else {

            // standby switcher already scheduled
            if (standby == standbySwitcher.getTargetStandbyState()) {
                log.info("new standby state '{}' is alreay scheduled. Nothing to do for now.", standby);
                return;
            } else {
                log.info("replacing scheduled standby state '{}' with '{}'", standbySwitcher.getTargetStandbyState(), standby);
                standbySwitcher.setTargetStandbyState(standby);
            }
        }

        /* ******************
         * decide when to do
         */
        if (needToSchedule) {
            int boostRemaining = -1;
            try {
                boostRemaining = h.readValue("boost_remaining");
            } catch (IOException ex) {
                ex.printStackTrace();
            } catch (TelegramException ex) {
                ex.printStackTrace();
            }

            if (boostRemaining > 0) {

                log.info("delay standby state switch from '{}' to '{}' by {}min due to running boost", currentStandbyState, standby, boostRemaining);
                delayToSchedule = (boostRemaining * 60/*sec*/ * 1000/* millisec */);

            } else {

                // no boost running
                // switch to ON (means: standby off)
                if (currentStandbyState == true && standby == false) {

                    // immediate ON
                    delayToSchedule = toMinimum0(MINIMUM_STANDBY_DELAY - (System.currentTimeMillis() - lastStandbySwitch));
                    log.info("immediate standby state switch from '{}' to '{}' in {}ms", currentStandbyState, standby, delayToSchedule);

                } else // switch to OFF (means: standby oon)
                {

                    // delay OFF up to standbyDelay
                    delayToSchedule = toMinimum0(standbyDelay - (System.currentTimeMillis() - lastStandbySwitch));
                    log.info("delay standby state switch from '{}' to '{}' for {}ms", currentStandbyState, standby, delayToSchedule);

                }

            }

            standbySwitcherScheduled = true;
            t.schedule(standbySwitcher.createNew(), delayToSchedule);
            log.info("Schedule standby switch from '{}' to '{}' with {}ms delay", currentStandbyState, standbySwitcher.getTargetStandbyState(), delayToSchedule);
        } else {
            log.info("No schedule required");
        }

    }

    public long toMinimum0(long l) {
        if (l < 0) {
            return 0;
        } else {
            return l;
        }
    }

    private void send(boolean isResponse, int value, String ga, Variable variable) throws KnxException {
        log.debug("isResponse={} value={}, ga={}, variable={}", new Object[]{isResponse, value, ga, variable});
        switch (variable.type) {
            case fanspeed:
                knx.writeUnscaled(isResponse, ga, value);
                break;
            case dec:
                knx.writeDpt6(isResponse, ga, value);
                break;
            case temperature:
                knx.write2ByteFloat(isResponse, ga, value);
                break;
            case percent:
                knx.writeScaled(isResponse, ga, value);
                break;
            case bit:
                knx.writeBoolean(isResponse, ga, value == 1);
                break;
        }
    }

    private void readConfig(File configfile) throws FileNotFoundException, IOException {
        p = new Properties();
        p.load(new FileReader(configfile));
    }

    public static void main(String[] args) throws IOException, KnxException {

        File option1 = new File("/etc/helioskwlremote/config.properties");
        File option2 = new File("config.properties");
        File f = option2;
        if (args.length == 2 && args[0].equals("-f")) {
            f = new File(args[1]);
            if (!f.exists() || f.isDirectory()) {
                System.err.println("Given config file '" + args[1] + "' does not exist.");
                System.exit(1);
            }
        } else {
            System.out.println("No config file specified with '-f <configfile>'. Searching for config file...");
            if (option1.exists()) {
                System.out.println("Autoselected config file '" + option1.getAbsolutePath() + "'");
                f = option1;
            } else {
                System.out.println("No config file found in: " + option2.getAbsolutePath());
                if (option2.exists()) {
                    System.out.println("Autoselected config file '" + option2.getAbsolutePath() + "'");
                    f = option2;
                } else {
                    System.out.println("No config file found in: " + option1.getAbsolutePath());
                    System.err.println("No config file found. Please specify with '-f <filename>' ...");
                    System.exit(1);
                }
            }
        }

        new HeliosKwlRemote(f);
        while (!Thread.interrupted()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
    }

}
