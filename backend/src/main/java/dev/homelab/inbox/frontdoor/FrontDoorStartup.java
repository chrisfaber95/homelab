package dev.homelab.inbox.frontdoor;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** {@code Access.init()} at start, as {@code serve} does in Python: learn the owner and the box's own addresses. */
@Component
@FrontDoorEnabled
class FrontDoorStartup implements ApplicationRunner {

    private final TailnetAccess access;

    FrontDoorStartup(TailnetAccess access) {
        this.access = access;
    }

    @Override
    public void run(ApplicationArguments args) {
        access.init();
    }
}
