package pl.czymogeaktualizowac.lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the docker CLI. stdout and stderr are merged; a timeout kills the process tree. */
final class Docker {

    record Result(int exit, String out) {
        boolean ok() {
            return exit == 0;
        }
    }

    private Docker() {
    }

    static Result run(List<String> cmd, Map<String, String> env, Path workDir, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (env != null) {
            pb.environment().putAll(env);
        }
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        Process p = pb.start();
        // read in a separate thread so a chatty process cannot block on a full pipe while we wait for the timeout
        java.util.concurrent.CompletableFuture<String> out = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new Result(124, out.getNow("") + "\n[timeout after " + timeout.toSeconds() + " s]");
            }
            return new Result(p.exitValue(), out.get(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return new Result(p.exitValue(), "");
        }
    }
}
