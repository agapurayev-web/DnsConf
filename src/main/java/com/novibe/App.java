package com.novibe;


import com.novibe.common.DnsTaskRunner;
import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.exception.CredentialsException;
import com.novibe.common.exception.UserInputException;
import com.novibe.common.util.EnvParser;
import com.novibe.common.util.Log;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static com.novibe.common.config.EnvironmentVariables.ALLOW_EMPTY_CONFIG;
import static com.novibe.common.config.EnvironmentVariables.BLOCK;
import static com.novibe.common.config.EnvironmentVariables.REDIRECT;
import static java.util.Objects.nonNull;

public class App {

    static void main() {
        validateConfiguration();
        final List<DnsProfile> dnsProfiles = EnvParser.parseProfiles();
        final AnnotationConfigApplicationContext commonContext = loadCommonApplicationContext();
        int failedProfiles = 0;

        try {
            for (DnsProfile dnsProfile : dnsProfiles) {
                AnnotationConfigApplicationContext currentContext = null;
                try {
                    currentContext = loadCurrentProfileContext(dnsProfile, commonContext);

                    DnsTaskRunner runner = currentContext.getBean(DnsTaskRunner.class);
                    runner.run();

                } catch (CredentialsException credentialsException) {
                    failedProfiles++;
                    Log.fail("CredentialsException on profile " + dnsProfile.number());
                    Log.fail(credentialsException.getMessage());
                } catch (Exception exception) {
                    failedProfiles++;
                    Log.fail("Unexpected exception on profile " + dnsProfile.number());
                    exception.printStackTrace(System.out);
                } finally {
                    if (nonNull(currentContext)) currentContext.close();
                }
            }
        } finally {
            commonContext.close();
        }

        if (failedProfiles > 0) {
            throw new IllegalStateException("Failed to update %s DNS profile(s)".formatted(failedProfiles));
        }
    }

    private static void validateConfiguration() {
        boolean noBlockSources = EnvParser.parse(BLOCK).isEmpty();
        boolean noRedirectSources = EnvParser.parse(REDIRECT).isEmpty();
        if (noBlockSources && noRedirectSources && !ALLOW_EMPTY_CONFIG) {
            throw UserInputException.noStackTrace("BLOCK and REDIRECT are both empty. "
                    + "Refusing to remove DNS settings. Set ALLOW_EMPTY_CONFIG=true to confirm an intentional cleanup.");
        }
    }

    private static AnnotationConfigApplicationContext loadCommonApplicationContext() {
        String commonsBasePackage = "com.novibe.common";
        return new AnnotationConfigApplicationContext(commonsBasePackage);
    }

    private static @NonNull AnnotationConfigApplicationContext loadCurrentProfileContext(DnsProfile dnsProfile, ApplicationContext commonContext) {
        String dnsBasePackage = switch (dnsProfile.dnsProvider()) {
            case "CLOUDFLARE" -> "com.novibe.dns.cloudflare";
            case "NEXTDNS" -> "com.novibe.dns.next_dns";
            default ->
                    throw UserInputException.noStackTrace("Unsupported DNS provider! Must be CLOUDFLARE or NEXTDNS. Was: " + dnsProfile.dnsProvider());
        };
        AnnotationConfigApplicationContext currentContext = new AnnotationConfigApplicationContext();
        currentContext.setParent(commonContext);
        currentContext.scan(dnsBasePackage);
        currentContext.registerBean("DnsProfile", DnsProfile.class, () -> dnsProfile);
        currentContext.refresh();
        return currentContext;
    }

}
