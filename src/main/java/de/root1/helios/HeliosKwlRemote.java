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
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Handler;
import java.util.logging.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author achristian
 */
public class HeliosKwlRemote {

    static {
        LogFormatter formatter = new LogFormatter();
        Handler[] handlers = java.util.logging.Logger.getLogger("").getHandlers();
        for (Handler handler : handlers) {
            handler.setFormatter(formatter);
//            handler.setLevel(Level.parse(System.getProperty("loglevel", "info")));
        }
    }
    private long STANDBY_DELAY = 60000; // 1min
    private long lastStandbySwitch = System.currentTimeMillis() - STANDBY_DELAY;
    private boolean timerScheduled;
    private StandbySwitcher is;

    class StandbySwitcher extends TimerTask {

        private final boolean newIdleState;

        StandbySwitcher(boolean newIdleState) {
            this.newIdleState = newIdleState;
        }

        public boolean getNewStandbyState() {
            return newIdleState;
        }

        @Override
        public void run() {
            if (newIdleState == idleState) {
                log.info("Nothing to do for idle switcher");
                timerScheduled = false;
                return;
            }
            try {
                if (idleSpeed == -1) {
                    log.warn("received idle-trigger '{}', but idle is disabled! Will skip this.", newIdleState);
                } else {
                    log.info("Triggering idle state({}) to {}", idleState, newIdleState);
                    if (newIdleState && !idleState) {
                        lastFanspeed = h.readValue("fanspeed");
                        switch (idleSpeed) {
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
                                log.info("Switching fanspeed to idle speed {}", idleSpeed);
                                h.writeValue("fanspeed", idleSpeed);
                                break;
                        }
                    } else if (!newIdleState && idleState) {
                        switch (idleSpeed) {
                            case 0:
                                log.info("Switching power-state to ON (takes some time...)");
                                h.writeValue("power_state", 1);
                                Thread.sleep(15000);
                                break;
                            default:
                                log.info("Restore last fanspeed: {}", lastFanspeed);
                                h.writeValue("fanspeed", lastFanspeed);
                                break;
                        }
                    }
                    idleState = !idleState;
                }
            } catch (Exception ex) {
                log.error("Error triggering idle state", ex);
            }
            timerScheduled = false;
            lastStandbySwitch = System.currentTimeMillis();
        }

    }

    private final Timer t = new Timer("IdleStateSwitcher", true);

    private final Logger log = LoggerFactory.getLogger(HeliosKwlRemote.class);

    private Properties p;
    private final Helios h;
    private final Knx knx;
    private String individualAddress;
    Map<String, HeliosVariableCache> cachedVariables;
    private int idleSpeed = -1;
    private boolean idleState = false;
    private int lastFanspeed;

    public HeliosKwlRemote() throws IOException {
        readConfig();
        log.info("Connecting to Helios KWL on {}:{}", p.getProperty("host"), p.getProperty("port"));
        h = new Helios(p.getProperty("host"), Integer.parseInt(p.getProperty("port")));
        h.setRestoreFanspeedAfterBoost(Boolean.parseBoolean(p.getProperty("restore_fanspeed_after_boost", "false")));
        h.connect();
        knx = new Knx();
        int keeptime = Integer.parseInt(p.getProperty("cache_keep", "60000"));
        log.info("Initialize cache variables with {}ms cache-keep-time", keeptime);
        cachedVariables = h.getCachedVariables(keeptime);

        boolean sendOnUpdate = Boolean.parseBoolean(p.getProperty("send_on_update", "false"));
        idleSpeed = Integer.parseInt(p.getProperty("standby_speed", "-1"));
        STANDBY_DELAY = Integer.parseInt(p.getProperty("standby_delay", "60000"));

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
                                            log.info("ReadRequest for '{}' --> {}", varname, idleState);
                                            knx.writeBoolean(true, ga, idleState);
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

    private void triggerStandbyState(boolean standby) {

        if (is != null) {
            if (standby == is.getNewStandbyState()) {
                log.info("new standby state '{}' is alreay scheduled. Nothing to do for now.", standby);
                return;
            } else {
                log.info("replaceding scheduled standby state '{}' with '{}'", is.getNewStandbyState(), standby);
                is.cancel();
            }
        }
        is = new StandbySwitcher(standby);

        if (System.currentTimeMillis() - lastStandbySwitch < STANDBY_DELAY || timerScheduled) {
            // run with delay
            log.info("Delaying standby state switch to '{}' by {}ms", standby, STANDBY_DELAY);

            int boostRemaining = 0;
            try {
                boostRemaining = h.readValue("boost_remaining");
            } catch (IOException ex) {
                ex.printStackTrace();
            } catch (TelegramException ex) {
                ex.printStackTrace();
            }
            
            if (boostRemaining>0) {
                log.info("Additional delay standby state switch by {}min due to running boost", boostRemaining);
            }

            timerScheduled = true;
            t.schedule(is, STANDBY_DELAY + (boostRemaining * 60/*sec*/ * 1000/* millisec */));

        } else {
            // run now
            is.run();
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

    private void readConfig() throws FileNotFoundException, IOException {
        p = new Properties();
        p.load(new FileReader("config.properties"));
    }

    public static void main(String[] args) throws IOException {

//        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%1$tL %4$-6s %2$s %5$s%6$s%n");
        new HeliosKwlRemote();
        while (!Thread.interrupted()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
    }

}
