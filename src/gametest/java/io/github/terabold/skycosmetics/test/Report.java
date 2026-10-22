package io.github.terabold.skycosmetics.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every check of a run, for {@code report.html} beside the screenshots: each test with its checks, its time, the
 * screenshots it took and, when it failed, why. Checks may run on the test thread or inside {@code runOnClient}.
 */
public final class Report {
    /** One check's text: how often it passed and failed. */
    static final class Check {
        long passed, failed;
    }

    static final class Test {
        final String name;
        /** By text, in first-seen order; repeats are counted, not listed again. */
        final Map<String, Check> checks = new LinkedHashMap<>();
        final List<String> shots = new ArrayList<>();
        long total, ms;
        String error;

        Test(String name) { this.name = name; }
    }

    /** Distinct check texts kept per test; failures are always kept. */
    private static final int MAX_DISTINCT = 5_000;
    /** Distinct passed checks listed per test in the page; the rest are counted. */
    private static final int SHOWN = 400;
    private static final List<Test> TESTS = new ArrayList<>();
    private static volatile Test current;

    private Report() {}

    /** A check: recorded, and on failure thrown as the test's failure. */
    public static void check(boolean ok, String what) {
        Test t = current;
        if (t != null) {
            synchronized (t) {
                t.total++;
                Check c = t.checks.get(what);
                if (c == null && (!ok || t.checks.size() < MAX_DISTINCT)) t.checks.put(what, c = new Check());
                if (c != null) { if (ok) c.passed++; else c.failed++; }
            }
        }
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] failed: " + what);
    }

    static void begin(String name) {
        Test t = new Test(name);
        TESTS.add(t);
        current = t;
    }

    static void end(long ms, Throwable error, List<String> shots) {
        Test t = current;
        current = null;
        if (t == null) return;
        t.ms = ms;
        t.shots.addAll(shots);
        if (error != null) {
            String msg = error.getMessage() == null ? error.toString() : error.getMessage();
            StringBuilder b = new StringBuilder(msg);
            StackTraceElement[] st = error.getStackTrace();
            for (int i = 0; i < Math.min(8, st.length); i++) b.append("\n    at ").append(st[i]);
            t.error = b.toString();
        }
    }

    static long failed() {
        return TESTS.stream().filter(t -> t.error != null).count();
    }

    static String summary() {
        long checks = TESTS.stream().mapToLong(t -> t.total).sum();
        return String.format(Locale.ROOT, "%d of %d tests passed, %d checks", TESTS.size() - failed(), TESTS.size(), checks);
    }

    /** report.html in {@code dir}, the screenshots linked from {@code dir/screenshots}. */
    static Path write(Path dir) throws IOException {
        long total = TESTS.stream().mapToLong(t -> t.ms).sum();
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
            .append("<title>SkyCosmetics test report</title><style>")
            .append(":root{color-scheme:light dark;--bg:#f6f7f9;--card:#fff;--text:#1d2026;--muted:#5d6470;--ok:#1a7f37;--bad:#c62828;--line:#dde1e6}")
            .append("@media (prefers-color-scheme:dark){:root{--bg:#14161a;--card:#1d2026;--text:#e6e8eb;--muted:#9aa1ab;--ok:#4cc26a;--bad:#ff6b6b;--line:#2c3038}}")
            .append("body{margin:0;padding:16px;background:var(--bg);color:var(--text);font:14px/1.45 system-ui,sans-serif}")
            .append("h1{font-size:20px;margin:0 0 4px}.muted{color:var(--muted)}")
            .append("details{background:var(--card);border:1px solid var(--line);border-radius:8px;margin:8px 0;padding:8px 12px}")
            .append("summary{cursor:pointer;font-weight:600}.ok{color:var(--ok)}.bad{color:var(--bad)}")
            .append("ul{margin:6px 0;padding-left:20px}li{margin:1px 0}pre{white-space:pre-wrap;background:var(--bg);padding:8px;border-radius:6px;overflow:auto}")
            .append(".shots{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:8px;margin-top:8px}")
            .append(".shots a{display:block;font-size:12px;color:var(--muted);text-decoration:none;word-break:break-all}.shots img{width:100%;border-radius:4px;border:1px solid var(--line)}")
            .append("</style></head><body><h1>SkyCosmetics test report</h1>")
            .append("<p class=").append(failed() == 0 ? "ok" : "bad").append("><b>").append(esc(summary())).append("</b></p>")
            .append("<p class=muted>").append(String.format(Locale.ROOT, "%.1f minutes", total / 60000.0))
            .append(". Failed tests open first; a test after a failed one may fail because of it.</p>");
        List<Test> order = new ArrayList<>(TESTS);
        order.sort((a, b) -> Boolean.compare(a.error == null, b.error == null));
        for (Test t : order) {
            boolean ok = t.error == null;
            h.append("<details").append(ok ? "" : " open").append("><summary><span class=").append(ok ? "ok>&#10003; " : "bad>&#10007; ")
                .append(esc(t.name)).append("</span> <span class=muted>")
                .append(String.format(Locale.ROOT, "%d checks, %.1f s, %d screenshots", t.total, t.ms / 1000.0, t.shots.size()))
                .append("</span></summary>");
            if (!ok) h.append("<pre class=bad>").append(esc(t.error)).append("</pre>");
            Map<String, Check> checks;
            synchronized (t) { checks = new LinkedHashMap<>(t.checks); }
            if (!checks.isEmpty()) {
                h.append("<ul>");
                int shown = 0;
                long hidden = 0;
                for (var e : checks.entrySet()) {
                    Check c = e.getValue();
                    if (c.failed > 0) {
                        h.append("<li class=bad>&#10007; ").append(esc(e.getKey())).append(count(c.failed + c.passed)).append("</li>");
                    } else if (shown < SHOWN) {
                        shown++;
                        h.append("<li class=ok>&#10003; ").append(esc(e.getKey())).append(count(c.passed)).append("</li>");
                    } else hidden += c.passed;
                }
                long unlisted = t.total - checks.values().stream().mapToLong(c -> c.passed + c.failed).sum() + hidden;
                if (unlisted > 0) h.append("<li class=muted>and ").append(unlisted).append(" more passed checks</li>");
                h.append("</ul>");
            }
            if (!t.shots.isEmpty()) {
                h.append("<div class=shots>");
                for (String s : t.shots) {
                    String href = "screenshots/" + s;
                    h.append("<a href='").append(esc(href)).append("'><img loading=lazy src='").append(esc(href)).append("' alt=''>").append(esc(s)).append("</a>");
                }
                h.append("</div>");
            }
            h.append("</details>");
        }
        h.append("</body></html>");
        Path out = dir.resolve("report.html");
        Files.writeString(out, h, StandardCharsets.UTF_8);
        return out;
    }

    private static String count(long n) {
        return n > 1 ? " <span class=muted>(&times;" + n + ")</span>" : "";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
