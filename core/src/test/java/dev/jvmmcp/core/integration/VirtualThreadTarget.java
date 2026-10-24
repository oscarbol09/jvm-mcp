package dev.jvmmcp.core.integration;

import java.util.concurrent.CountDownLatch;

public class VirtualThreadTarget {
    public static void main(String[] args) throws Exception {
        CountDownLatch releaseLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(2);

        Thread.ofVirtual().name("integration-vthread-1").start(() -> {
            startedLatch.countDown();
            try {
                releaseLatch.await();
            } catch (InterruptedException ignored) {
            }
        });

        Thread.ofVirtual().name("integration-vthread-2").start(() -> {
            startedLatch.countDown();
            try {
                releaseLatch.await();
            } catch (InterruptedException ignored) {
            }
        });

        startedLatch.await();
        System.out.println("READY");
        System.out.flush();

        Object lock = new Object();
        synchronized (lock) {
            lock.wait();
        }
    }
}
