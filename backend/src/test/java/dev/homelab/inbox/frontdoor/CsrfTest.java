package dev.homelab.inbox.frontdoor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The token and the Origin rule of {@code access.py} / {@code csrf_ok}. */
class CsrfTest {

    @TempDir
    Path dir;

    @Test
    void theTokenIsPythonsHmacOfInboxFormWithTheSecretFile() throws Exception {
        Path secret = dir.resolve("csrf-secret");
        Files.writeString(secret, "abc\n");  // Python strips the file
        // hmac.new(b"abc", b"inbox-form", sha256).hexdigest()
        assertThat(new Csrf(secret).token()).isEqualTo("a4e0311a18bf9ae0f4298ea67a18863698acde3930175e4380aec9c77b1f694e");
    }

    @Test
    void aMissingSecretIsCreatedPrivateAndStaysTheSame() throws Exception {
        Path secret = dir.resolve("csrf-secret");
        Csrf csrf = new Csrf(secret);
        String first = csrf.token();
        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(Files.readString(secret)).matches("[0-9a-f]{64}");
        assertThat(Files.getPosixFilePermissions(secret)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(new Csrf(secret).token()).isEqualTo(first);
    }

    @Test
    void onlyTheExactTokenMatches() throws Exception {
        Path secret = dir.resolve("csrf-secret");
        Files.writeString(secret, "abc");
        Csrf csrf = new Csrf(secret);
        String token = csrf.token();
        assertThat(csrf.matches(token)).isTrue();
        for (String bad : new String[] {null, "", "wrong", token.substring(1), token + "0", token.toUpperCase()}) {
            assertThat(csrf.matches(bad)).as("token %s", bad).isFalse();
        }
    }

    @Test
    void theOriginMustBeThisPage() {
        assertThat(Csrf.originMatches("http://127.0.0.1:8105", "127.0.0.1:8105")).isTrue();
        assertThat(Csrf.originMatches("http://homelab.tail4eb878.ts.net:8105/x?y#z", "homelab.tail4eb878.ts.net:8105")).isTrue();
        assertThat(Csrf.originMatches("http://evil.example", "127.0.0.1:8105")).isFalse();
        assertThat(Csrf.originMatches("http://127.0.0.1:8106", "127.0.0.1:8105")).isFalse();
        assertThat(Csrf.originMatches(null, "127.0.0.1:8105")).isFalse();
        assertThat(Csrf.originMatches("", "127.0.0.1:8105")).isFalse();
        assertThat(Csrf.originMatches("null", "127.0.0.1:8105")).isFalse();
        assertThat(Csrf.originMatches("http://127.0.0.1:8105.evil.example", "127.0.0.1:8105")).isFalse();
        assertThat(Csrf.originMatches("http://a@127.0.0.1:8105", "127.0.0.1:8105")).isFalse();
        // urlsplit("//x").netloc == "x": a scheme-relative Origin counts, a bare word does not.
        assertThat(Csrf.originMatches("//127.0.0.1:8105", "127.0.0.1:8105")).isTrue();
        assertThat(Csrf.originMatches("127.0.0.1:8105", "127.0.0.1:8105")).isFalse();
        // No Host and no Origin: both netlocs are empty, which Python counts as equal.
        assertThat(Csrf.originMatches("", "")).isTrue();
    }
}
