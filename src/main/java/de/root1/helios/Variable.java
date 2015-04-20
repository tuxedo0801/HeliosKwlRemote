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

/**
 *
 * @author achristian
 */
public class Variable {
    
    String name;
    byte varid;
    public enum Type {temperature, fanspeed, bit, dec, percent}
    Type type;
    int bitposition;
    boolean read;
    boolean write;

    public Variable(String name, byte varid, Type type, int bitposition, boolean read, boolean write) {
        this.name = name;
        this.varid = varid;
        this.type = type;
        this.bitposition = bitposition;
        this.read = read;
        this.write = write;
    }
    
    
    
}
