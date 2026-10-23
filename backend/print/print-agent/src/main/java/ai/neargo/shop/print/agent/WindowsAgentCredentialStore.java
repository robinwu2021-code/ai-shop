package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

public final class WindowsAgentCredentialStore implements AgentCredentialStore {
    private final Path directory;

    public WindowsAgentCredentialStore(Path directory) {
        this.directory = directory.toAbsolutePath();
    }

    private byte[] dpapi(byte[] input, boolean decrypt) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"))
            throw new IllegalStateException("WINDOWS_DPAPI_REQUIRED");
        String protect =
                "$v=[Console]::In.ReadToEnd();ConvertTo-SecureString -String $v -AsPlainText -Force"
                        + " | ConvertFrom-SecureString";
        String unprotect =
                "$v=[Console]::In.ReadToEnd();$s=ConvertTo-SecureString -String"
                        + " $v;[Console]::Out.Write(([System.Net.NetworkCredential]::new('',"
                        + " $s)).Password)";
        try {
            var process =
                    new ProcessBuilder(
                                    "powershell.exe",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-Command",
                                    decrypt ? unprotect : protect)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            try (var out = process.getOutputStream()) {
                out.write(input);
            }
            var reader =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return process.getInputStream().readNBytes(65537);
                                } catch (java.io.IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                            });
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("DPAPI_TIMEOUT");
            }
            byte[] bytes = reader.get(2, java.util.concurrent.TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > 65536)
                throw new IllegalStateException("DPAPI_FAILED");
            return decrypt
                    ? bytes
                    : new String(bytes, StandardCharsets.UTF_8)
                            .strip()
                            .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AGENT_CREDENTIAL_PROTECTION_FAILED", e);
        }
    }

    private void saveProtected(Path path, byte[] raw) {
        try {
            Files.createDirectories(directory);
            byte[] encrypted = dpapi(raw, false);
            Path temp = Files.createTempFile(directory, "credential-", ".tmp");
            try {
                Files.write(temp, encrypted);
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
                Arrays.fill(encrypted, (byte) 0);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String initializeClientKey(String alias) {
        checkAlias(alias);
        try {
            Path file = directory.resolve(alias + ".pkcs8.dpapi");
            KeyPair pair;
            if (Files.exists(file)) {
                var privateKey = readKey(alias);
                var spec =
                        KeyFactory.getInstance("RSA")
                                .getKeySpec(privateKey, RSAPrivateCrtKeySpec.class);
                var pub =
                        KeyFactory.getInstance("RSA")
                                .generatePublic(
                                        new RSAPublicKeySpec(
                                                spec.getModulus(), spec.getPublicExponent()));
                return Base64.getEncoder().encodeToString(pub.getEncoded());
            }
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            pair = generator.generateKeyPair();
            saveProtected(file, Base64.getEncoder().encode(pair.getPrivate().getEncoded()));
            return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("AGENT_KEY_INITIALIZATION_FAILED", e);
        }
    }

    private void checkAlias(String alias) {
        if (alias == null || !alias.matches("[A-Za-z0-9_-]{1,50}"))
            throw new IllegalArgumentException("key alias");
    }

    private PrivateKey readKey(String alias) {
        checkAlias(alias);
        try {
            byte[] encoded =
                    dpapi(Files.readAllBytes(directory.resolve(alias + ".pkcs8.dpapi")), true);
            try {
                return KeyFactory.getInstance("RSA")
                        .generatePrivate(
                                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        } catch (Exception e) {
            throw new IllegalStateException("AGENT_KEY_UNAVAILABLE", e);
        }
    }

    public String unseal(String sealed, String alias) {
        try {
            var cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    readKey(alias),
                    new OAEPParameterSpec(
                            "SHA-256",
                            "MGF1",
                            MGF1ParameterSpec.SHA256,
                            PSource.PSpecified.DEFAULT));
            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(sealed)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AGENT_UNSEAL_FAILED", e);
        }
    }

    public Optional<OpaqueAgentCredential> load() {
        try {
            Path file = directory.resolve("agent.dpapi");
            if (!Files.exists(file)) return Optional.empty();
            byte[] raw = dpapi(Files.readAllBytes(file), true);
            try {
                return Optional.of(
                        new OpaqueAgentCredential(new String(raw, StandardCharsets.UTF_8)));
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void saveSealedCredential(String sealed, String alias) {
        saveProtected(
                directory.resolve("agent.dpapi"),
                unseal(sealed, alias).getBytes(StandardCharsets.UTF_8));
    }

    public void revokeLocalCredential() {
        try {
            Files.deleteIfExists(directory.resolve("agent.dpapi"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
