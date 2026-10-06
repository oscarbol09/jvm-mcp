package dev.jvmmcp.core.integration;

public class DummyTarget {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("READY");
        // Keep process alive until killed
        Object lock = new Object();
        synchronized (lock) {
            lock.wait();
        }
    }
}
