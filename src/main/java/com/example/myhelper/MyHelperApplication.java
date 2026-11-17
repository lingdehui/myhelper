package com.example.myhelper;

import com.example.myhelper.interaction.ConsoleInput;
import com.example.myhelper.interaction.TaskEntry;
import com.example.myhelper.interaction.TaskRuntimeConfiguration;
import com.example.myhelper.llm.ModelClientConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/** MyHelper 的正式进程入口。 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({ModelClientConfiguration.class, TaskRuntimeConfiguration.class})
public class MyHelperApplication {
    public static void main(String[] args) throws IOException {
        try (ConfigurableApplicationContext context = application().run(args)) {
            Charset encoding = System.console() == null ? Charset.defaultCharset() : System.console().charset();
            ConsoleInput console = new ConsoleInput(context.getBean(TaskEntry.class),
                    new BufferedReader(new InputStreamReader(System.in, encoding)),
                    new PrintWriter(System.out, false, encoding));
            console.run();
        }
    }

    public static SpringApplication application() {
        SpringApplication application = new SpringApplication(MyHelperApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        // 旧版 MCP 配置可显式启用，终端入口默认无需连接本地机器人服务。
        application.setDefaultProperties(Map.of("spring.ai.mcp.client.enabled", "false"));
        return application;
    }
}
