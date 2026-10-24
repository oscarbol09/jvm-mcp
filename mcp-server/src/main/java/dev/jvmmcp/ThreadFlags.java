package dev.jvmmcp;

import picocli.CommandLine.Option;

public class ThreadFlags {
    @Option(names = {"--deadlocks", "-d"}, description = "Scan exclusively for deadlocks")
    boolean deadlocksOnly;

    @Option(names = {"--dump", "-D"}, description = "Display full thread dump with stack traces")
    boolean dump;

    @Option(names = {"--blocked", "-b"}, description = "List only blocked threads")
    boolean blockedOnly;
}

