package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.support.Problems;
import io.github.terabold.skycosmetics.support.Report;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;

/** "Report a bug": failures are recorded once per kind, and the report says what a bug needs without personal data. */
public class ReportTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            // Later tests read chat; the one-time alert is checked here, then switched off for the rest of the run.
            Problems.alerts = false;
            int before = Problems.count();
            Io.failed("Testing a report", new IllegalStateException("test failure"));
            Io.failed("Testing a report", new IllegalStateException("test failure"));
            check(Problems.count() == before + 1, "one entry per kind of failure");
            String r = Report.build();
            check(r.contains("SkyCosmetics ") && r.contains("Minecraft ") && r.contains("Testing a report x2"), "the report names versions and problems: " + r);
            check(r.contains("ReportTest.lambda") || r.contains("test.ReportTest"), "the report points at the failing code");
            String home = System.getProperty("user.home");
            check(home == null || !r.contains(home), "the report holds no local paths");
            check(Report.issueUrl().startsWith("https://github.com/Terabold/SkyCosmetics/issues/new?template=bug_report.yml"), "the report goes to the issue form");
        });
        System.out.println("[SkyCosmeticsTest] bug report checks passed");
    }
}
