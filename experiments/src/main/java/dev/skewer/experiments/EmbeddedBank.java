package dev.skewer.experiments;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import dev.skewer.bank.BankServiceApplication;

/** Runs the bank service inside the CLI's JVM on a free port, so one command needs nothing but Docker. */
public final class EmbeddedBank implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final URI url;

    private EmbeddedBank(ConfigurableApplicationContext context, URI url) {
        this.context = context;
        this.url = url;
    }

    public static EmbeddedBank start(String jdbcUrl) {
        int port = freePort();
        SpringApplication app = new SpringApplication(BankServiceApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        ConfigurableApplicationContext ctx = app.run(
                "--server.port=" + port,
                "--spring.datasource.url=" + jdbcUrl,
                "--logging.level.root=WARN",
                "--logging.level.org.hibernate.orm.deprecation=ERROR");
        return new EmbeddedBank(ctx, URI.create("http://localhost:" + port));
    }

    public URI url() {
        return url;
    }

    @Override
    public void close() {
        context.close();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
