package dev.homelab.inbox.runner;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import dev.homelab.inbox.runner.RunnerResult.Ending;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RunnerService against {@code src/test/resources/runner/fake_runner.py}, which follows the runner contract. */
class RunnerServiceTest {

    @TempDir Path dir;
    String fake;
    RunnerService service = new RunnerService();

    @BeforeEach
    void locate() throws URISyntaxException {
        fake = Path.of(getClass().getResource("/runner/fake_runner.py").toURI()).toString();
    }

    List<String> runner(String mode, String... more) {
        List<String> c = new ArrayList<>(List.of("python3", "-I", fake, mode, dir.toString()));
        c.addAll(List.of(more));
        return c;
    }

    List<String> killAction() {
        return List.of("python3", "-I", fake, "kill", dir.toString());
    }

    RunnerRequest req(List<String> cmd, Duration deadline) {
        return new RunnerRequest(cmd, killAction(), deadline, Duration.ofSeconds(1), null, null);
    }

    @Test
    void normalResultAndEvents() {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        RunnerResult r = service.run(req(runner("ok"), Duration.ofSeconds(20)), events::add);
        assertThat(r.ending()).isEqualTo(Ending.EXITED);
        assertThat(r.exitCode()).isZero();
        assertThat(r.ok()).isTrue();
        assertThat(r.result().get().path("text").asText()).isEqualTo("hello");
        assertThat(r.stderrTail()).contains("diagnostic line");
        // the "not json" line is ignored, the result line is not an event
        assertThat(events).extracting(e -> e.path("event").asText()).containsExactly("model", "turn");
        assertThat(dir.resolve("kill-called")).doesNotExist();
    }

    @Test
    void nonZeroExitCodeIsReportedWithTheResultLine() {
        RunnerResult r = service.run(req(runner("fail"), Duration.ofSeconds(20)), null);
        assertThat(r.ending()).isEqualTo(Ending.EXITED);
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.ok()).isFalse();
        assertThat(r.result()).isPresent();
    }

    @Test
    void deadlineRunsTheKillActionFirst() throws Exception {
        Files.writeString(dir.resolve("obey"), "1");
        RunnerResult r = service.run(req(runner("hang"), Duration.ofSeconds(2)), null);
        assertThat(r.ending()).isEqualTo(Ending.DEADLINE);
        assertThat(r.result()).isEmpty();
        assertThat(Files.readString(dir.resolve("kill-called"))).startsWith("kill ");
        assertThat(r.exitCode()).isNotZero();
    }

    @Test
    void cancelRunsTheKillAction() throws Exception {
        Files.writeString(dir.resolve("obey"), "1");
        RunnerRun run = service.start(req(runner("hang"), Duration.ofSeconds(60)), null);
        waitFor("ready");
        run.cancel();
        RunnerResult r = run.result().get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(r.ending()).isEqualTo(Ending.CANCELLED);
        assertThat(dir.resolve("kill-called")).exists();
    }

    @Test
    void cancelKillsTheProcessGroupWhenTheRunnerIgnoresTheKillAction() throws Exception {
        // no "obey" file: the kill action does nothing, and the runner ignores SIGTERM and has a child
        RunnerRun run = service.start(req(runner("stubborn"), Duration.ofSeconds(60)), null);
        waitFor("ready");
        long child = Long.parseLong(Files.readString(dir.resolve("child")));
        assertThat(ProcessHandle.of(child)).isPresent();
        run.cancel();
        RunnerResult r = run.result().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(r.ending()).isEqualTo(Ending.CANCELLED);
        assertThat(dir.resolve("kill-called")).exists();
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (ProcessHandle.of(child).filter(ProcessHandle::isAlive).isPresent() && System.nanoTime() < until) Thread.sleep(50);
        assertThat(ProcessHandle.of(child).filter(ProcessHandle::isAlive)).isEmpty();
    }

    @Test
    void noShellArgumentsArriveVerbatim() throws Exception {
        String evil = "$(touch " + dir.resolve("pwned") + ");`touch " + dir.resolve("pwned2") + "`; echo *";
        RunnerRequest request = new RunnerRequest(runner("echo", evil, "a b"), killAction(), Duration.ofSeconds(20),
                null, null, "the prompt".getBytes(StandardCharsets.UTF_8));
        RunnerResult r = service.run(request, null);
        JsonNode res = r.result().orElseThrow();
        assertThat(res.path("argv").get(0).asText()).isEqualTo(evil);
        assertThat(res.path("argv").get(1).asText()).isEqualTo("a b");
        assertThat(res.path("stdin").asText()).isEqualTo("the prompt");
        assertThat(dir.resolve("pwned")).doesNotExist();
        assertThat(dir.resolve("pwned2")).doesNotExist();
    }

    @Test
    void aMissingProgramIsNeverOk() {
        RunnerRequest missing = RunnerRequest.of(List.of("/nonexistent/runner"), List.of(), Duration.ofSeconds(5));
        // through setsid the failure is its exit code (127), without the launcher the start itself fails
        RunnerResult viaSetsid = service.run(missing, null);
        assertThat(viaSetsid.ok()).isFalse();
        assertThat(viaSetsid.exitCode()).isNotZero();
        RunnerResult direct = new RunnerService(List.of()).run(missing, null);
        assertThat(direct.ending()).isEqualTo(Ending.START_FAILED);
        assertThat(direct.ok()).isFalse();
    }

    private void waitFor(String marker) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(dir.resolve(marker)) && System.nanoTime() < until) Thread.sleep(50);
        assertThat(dir.resolve(marker)).exists();
    }
}
