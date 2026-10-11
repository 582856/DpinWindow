package com.maple.dpinviewer;

final class Protocol {
    static final String APP = "com.maple.dpinviewer";
    static final String TARGET = "com.milink.service";
    static final String SERVICE = "com.milink.client.ClientV2PublicService";
    static final String ACTION = APP + ".PREVIEW";
    static final int HELLO = 1, START = 2, SURFACE = 3, STOP = 4, BYE = 5;
    static final int READY = 101, SIGNAL = 102, STATUS = 103, FRAMES = 104, AUDIO = 105, NATIVE_MODE = 106, CONNECTION = 107;
    private Protocol() {}
}
