package com.example.myhelper;

import com.example.myhelper.application.TaskRecoveryConfiguration;
import com.example.myhelper.interaction.ConsoleInput;
import com.example.myhelper.interaction.TaskEntry;
import com.example.myhelper.interaction.TaskRuntimeConfiguration;
import com.example.myhelper.world.WorldModelConfiguration;
import com.example.myhelper.interaction.desktop.DesktopSession;
import com.example.myhelper.llm.ModelClientConfiguration;
import com.example.myhelper.records.RunArchivePort;
import com.example.myhelper.tasklog.TaskLogHistory;
import com.example.myhelper.tools.DeviceStateToolConfiguration;
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
import org.springframework.core.env.SimpleCommandLinePropertySource;

/** MyHelper 的正式进程入口。 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({ModelClientConfiguration.class, TaskRuntimeConfiguration.class, TaskRecoveryConfiguration.class,
        WorldModelConfiguration.class, DeviceStateToolConfiguration.class})
public class MyHelperApplication {
    public static void main(String[] args) throws IOException {
        SpringApplication application = application();
        var arguments = new SimpleCommandLinePropertySource(args);
        String ui = arguments.getProperty("myhelper.ui");
        if (ui == null) { ui = System.getenv("MYHELPER_UI"); }
        if ("desktop".equalsIgnoreCase(ui)) {
            application.setHeadless(false);
        }
        try (ConfigurableApplicationContext context = application.run(args)) {
            if ("desktop".equalsIgnoreCase(context.getEnvironment().getProperty("myhelper.ui"))) {
                DesktopSession.run(context);
                return;
            }
            Charset encoding = System.console() == null ? Charset.defaultCharset() : System.console().charset();
            RunArchivePort archives = context.getBeanProvider(RunArchivePort.class).getIfAvailable();
            ConsoleInput console = new ConsoleInput(context.getBean(TaskEntry.class),
                    new BufferedReader(new InputStreamReader(System.in, encoding)),
                    new PrintWriter(System.out, false, encoding), archives == null ? null : new TaskLogHistory(archives));
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
