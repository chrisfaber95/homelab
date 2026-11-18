package dev.homelab.inbox.frontdoor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The front door (H-026 K8) exists in Java but is not in front of anything yet: its beans are only created when
 * {@code inbox.front-door.enabled=true}, which nothing sets until H-027 switches the routes. With the default (off)
 * the running backend answers exactly as before: no filter, no header, no extra route.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(name = "inbox.front-door.enabled", havingValue = "true")
@interface FrontDoorEnabled {}
