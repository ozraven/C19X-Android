package org.c19x.beacon;

/**
 * Beacon listener for responding to beacon transmitter and receiver events.
 */
public class BeaconListener {

    public void start() {
    }

    public void startFailed(int errorCode) {
    }

    public void stop() {
    }

    public void detect(final long timestamp, long id, float rssi) {
    }
}
