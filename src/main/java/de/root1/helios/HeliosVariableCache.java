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
