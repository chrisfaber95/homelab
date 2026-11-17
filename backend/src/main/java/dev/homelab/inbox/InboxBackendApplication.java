package dev.homelab.inbox;

import dev.homelab.inbox.db.ImportSqliteCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InboxBackendApplication {

    public static void main(String[] args) {
        if (args.length > 0 && ImportSqliteCommand.NAME.equals(args[0])) {
            // One-off command run by hand: no Spring context and no web server.
            System.exit(ImportSqliteCommand.run(java.util.Arrays.copyOfRange(args, 1, args.length), System.out,
                    System.err));
        }
        SpringApplication.run(InboxBackendApplication.class, args);
    }
}
