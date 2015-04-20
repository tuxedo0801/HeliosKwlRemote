/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.root1.helios;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author achristian
 */
public class HeliosVariableCache {

    private static final Logger log = LoggerFactory.getLogger(HeliosVariableCache.class);

    private final String varname;
    private boolean firstRun = true;
    private int value;
    private long maxtime;
    private long lastaccess = System.currentTimeMillis() - maxtime;
    private final Helios h;

    public HeliosVariableCache(Helios h, String varname, long maxtime) {
        this.h = h;
        this.varname = varname;
        this.maxtime = maxtime;
    }

    public boolean hasChanged() throws IOException, TelegramException {

        if (System.currentTimeMillis() - lastaccess > maxtime) {

            //log.info("Checking if {} has changed", varname);
            int x = h.readValue(varname);
            lastaccess = System.currentTimeMillis();
            if (firstRun) {
                log.debug("{} has value {}", varname, x);
                value = x;
                firstRun = false;
                return true;
            } else if (x != value) {
                log.debug("{} has changed from {} to {}", varname, value, x);
                value = x;
                return true;
            }
        }
        return false;
    }

    public int getValue() {
        return value;
    }

    int forcedGet() throws IOException, TelegramException {
        hasChanged();
        return getValue();
    }

}
