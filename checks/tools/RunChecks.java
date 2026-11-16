import com.example.myhelper.persistence.DualWriteStoreCheck;
import com.example.myhelper.persistence.Neo4jEntityStoreCheck;
import com.example.myhelper.persistence.PersistenceConfigurationCheck;
import com.example.myhelper.persistence.QdrantEntityStoreCheck;
import java.io.PrintWriter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

/** 独立检查入口；不属于生产启动或根工程的默认测试生命周期。 */
public final class RunChecks {
    private RunChecks() {
    }

    public static void main(String[] args) throws Exception {
        // 只从本次编译的 classpath 发现项目测试，不读取历史快照或旧编译目录。
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectPackage("com.example.myhelper"))
                .build();
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        var summary = listener.getSummary();
        var output = new PrintWriter(System.out, true);
        summary.printTo(output);
        summary.printFailuresTo(output);
        // 无测试或异常中止也视为失败，避免路径错误后产生“全部通过”的假象。
        if (summary.getTestsFoundCount() == 0 || summary.getTestsFailedCount() > 0
                || summary.getContainersFailedCount() > 0 || summary.getTestsAbortedCount() > 0) {
            throw new AssertionError("JUnit checks failed or no tests were discovered");
        }

        // 这四组为独立 main 检查，不会由 JUnit 自动发现。
        String[] noArguments = new String[0];
        DualWriteStoreCheck.main(noArguments);
        Neo4jEntityStoreCheck.main(noArguments);
        QdrantEntityStoreCheck.main(noArguments);
        PersistenceConfigurationCheck.main(noArguments);
        System.out.println("All JUnit tests and four persistence checks passed.");
    }
}
