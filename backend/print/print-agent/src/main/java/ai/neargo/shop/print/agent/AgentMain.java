package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** No embedded web server or business database. Pair once, then run under the same Windows user. */
public final class AgentMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 1)
            throw new IllegalArgumentException(
                    "Usage: AgentMain agent.properties [--init | --enroll sealed-enrollment-file]");
        var properties = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            properties.load(
                    new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        }
        Path directory =
                Path.of(
                        properties.getProperty(
                                "stateDirectory",
                                System.getenv("LOCALAPPDATA") + "/ai-shop-print"));
        var credentials = new WindowsAgentCredentialStore(directory);
        String alias = "agent-key";
        String publicKey = credentials.initializeClientKey(alias);
        if (args.length > 1 && args[1].equals("--init")) {
            System.out.println(publicKey);
            return;
        }
        var client =
                new DefaultAgentHttpClient(
                        URI.create(properties.getProperty("server")), credentials);
        if (args.length > 2 && args[1].equals("--enroll")) {
            String sealed = Files.readString(Path.of(args[2])).strip();
            var enrolled =
                    client.enroll(
                            new DeviceEnrollReq(
                                    properties.getProperty("agentNo"),
                                    credentials.unseal(sealed, alias),
                                    publicKey,
                                    UUID.randomUUID().toString(),
                                    "WINDOWS",
                                    "1"));
            credentials.saveSealedCredential(enrolled.sealedCredential(), alias);
            return;
        }
        java.nio.channels.FileChannel instanceChannel =
                java.nio.channels.FileChannel.open(
                        directory.resolve("agent.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        java.nio.channels.FileLock instanceLock = instanceChannel.tryLock();
        if (instanceLock == null) {
            instanceChannel.close();
            throw new IllegalStateException("AGENT_ALREADY_RUNNING");
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        instanceLock.release();
                                        instanceChannel.close();
                                    } catch (java.io.IOException ignored) {
                                    }
                                }));
        var store = new SqliteLocalDispatchStore(directory.resolve("dispatch.sqlite"));
        var executor = new JavaPrintServiceExecutor();
        var session =
                client.openSession(
                        new DeviceOpenSessionReq(UUID.randomUUID().toString(), "1", null));
        var budget = new PrintWorkerBudget(100, Duration.ofSeconds(20), "desktop-agent");
        new AgentRecoveryCoordinator(store, executor).recoverOnStartup(budget);
        var printers =
                Arrays.stream(properties.getProperty("printerNos", "").split(","))
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .toList();
        var discovery = new ArrayList<DiscoveredPrinter>();
        for (var key : properties.stringPropertyNames())
            if (key.startsWith("physical."))
                discovery.add(new DiscoveredPrinter(key.substring(9), properties.getProperty(key)));
        try (var loop =
                new AgentExecutionLoop(
                        client, store, executor, session.sessionEpoch(), printers, discovery)) {
            var stopped = new java.util.concurrent.CountDownLatch(1);
            var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        scheduler.shutdownNow();
                                        stopped.countDown();
                                    }));
            scheduler.scheduleWithFixedDelay(
                    () -> {
                        try {
                            loop.tick(budget);
                        } catch (RuntimeException ex) {
                            System.err.println(
                                    "PRINT_AGENT_ATTENTION: " + ex.getClass().getSimpleName());
                        }
                    },
                    0,
                    15,
                    java.util.concurrent.TimeUnit.SECONDS);
            stopped.await();
        }
    }
}
