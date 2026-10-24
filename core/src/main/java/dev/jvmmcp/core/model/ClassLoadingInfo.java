package dev.jvmmcp.core.model;

/**
 * JVM class loading statistics for Metaspace and classloader leak diagnosis.
 *
 * @param loadedClassCount currently loaded classes in the target JVM
 * @param totalLoadedClasses total number of classes loaded since JVM startup
 * @param unloadedClassCount total number of classes unloaded since JVM startup
 */
public record ClassLoadingInfo(
    long loadedClassCount,
    long totalLoadedClasses,
    long unloadedClassCount
) {}
