package com.gigapixel.server;

import com.gigapixel.server.cli.GtpCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GigapixelServerApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "cli".equals(args[0])) {
            int code = new GtpCli().run(java.util.Arrays.copyOfRange(args, 1, args.length), System.out, System.err);
            System.exit(code);
            return;
        }
        SpringApplication.run(GigapixelServerApplication.class, args);
    }
}
