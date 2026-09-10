package com.example.urlshortener.infra;

import com.example.urlshortener.application.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Fail-fast guards that can't be expressed as bean-validation annotations.
 *
 * <p>Most importantly: refuse to run in a production profile with the shipped
 * placeholder {@code ip-hash-salt}. A weak salt makes the stored IP hashes
 * brute-forceable back to raw addresses, so this is a hard stop, not a warning.
 */
@Component
public class StartupSanityChecks implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupSanityChecks.class);
    private static final Set<String> INSECURE_SALTS = Set.of(
            "dev-insecure-salt", "dev-insecure-salt-change-me", "change-me", "changeme", "");
    private static final Set<String> PROD_PROFILES = Set.of("prod", "production");

    private final AppProperties props;
    private final Environment environment;

    public StartupSanityChecks(AppProperties props, Environment environment) {
        this.props = props;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        boolean prod = Set.of(environment.getActiveProfiles()).stream().anyMatch(PROD_PROFILES::contains);

        if (INSECURE_SALTS.contains(props.ipHashSalt().strip())) {
            String msg = "app.ip-hash-salt is set to an insecure placeholder value";
            if (prod) {
                throw new IllegalStateException(msg + " — refusing to start in a production profile");
            }
            log.warn("{} — set IP_HASH_SALT before deploying", msg);
        }

        if (prod && props.trustForwardedFor() && !props.blockPrivateAddresses()) {
            log.warn("running with trust-forwarded-for=true and block-private-addresses=false in production");
        }
        log.info("startup sanity checks passed (profiles={})", (Object) environment.getActiveProfiles());
    }
}
