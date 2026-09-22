package com.github.highcumontoa.sandboxleasecontrolplanejava.dto;

/**
 * 配额对账视图。
 */
public class PoolStatusResponse {

    private int capacity;
    private int occupied;
    private int available;
    private int activeLeases;

    public PoolStatusResponse() {
    }

    public PoolStatusResponse(int capacity, int occupied, int activeLeases) {
        this.capacity = capacity;
        this.occupied = occupied;
        this.available = capacity - occupied;
        this.activeLeases = activeLeases;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getOccupied() {
        return occupied;
    }

    public int getAvailable() {
        return available;
    }

    public int getActiveLeases() {
        return activeLeases;
    }
}
