package io.scenariomesh.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.coordinator.RunOutcome;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.workerruntime.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReportWriter {
    public ReportPaths write(RunOutcome outcome, Path reportingDirectory) throws Exception {
        Files.createDirectories(outcome.runDirectory());
        List<ExecutionResult> ordered = new ArrayList<>(outcome.results());
        ordered.sort(Comparator.comparing(ExecutionResult::displayName)
                .thenComparing(result -> result.scenarioId().value()));
        Summary summary = Summary.from(outcome, ordered);
        Path json = outcome.runDirectory().resolve("summary.json");
        Path junit = outcome.runDirectory().resolve("junit.xml");
        Path html = outcome.runDirectory().resolve("report.html");
        ObjectMapper mapper = JsonCodec.create();
        mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), summary);
        Files.writeString(junit, junitXml(summary), StandardCharsets.UTF_8);
        Files.writeString(html, html(summary), StandardCharsets.UTF_8);
        Files.createDirectories(reportingDirectory);
        Path latestJson = reportingDirectory.resolve("summary.json");
        Path latestJunit = reportingDirectory.resolve("junit.xml");
        Path latestHtml = reportingDirectory.resolve("report.html");
        Files.copy(json, latestJson, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(junit, latestJunit, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(html, latestHtml, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new ReportPaths(json, junit, html, latestJson, latestJunit, latestHtml);
    }

    private String junitXml(Summary summary) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"ScenarioMesh\" tests=\"").append(summary.total())
                .append("\" failures=\"").append(summary.failed())
                .append("\" errors=\"").append(summary.infrastructureFailed())
                .append("\" skipped=\"").append(summary.skipped())
                .append("\" time=\"").append(seconds(summary.durationMillis())).append("\">\n");
        for (ExecutionResult result : summary.results()) {
            xml.append("  <testcase name=\"").append(xmlEscape(result.displayName()))
                    .append("\" classname=\"").append(xmlEscape(result.workerId().value()))
                    .append("\" time=\"").append(seconds(result.duration().toMillis())).append("\">\n");
            if (result.status() == ResultStatus.TEST_FAILURE) {
                xml.append("    <failure type=\"").append(xmlEscape(nullSafe(result.failureType())))
                        .append("\" message=\"").append(xmlEscape(nullSafe(result.failureMessage()))).append("\"/>\n");
            } else if (result.status() == ResultStatus.SKIPPED) {
                xml.append("    <skipped message=\"").append(xmlEscape(nullSafe(result.failureMessage())))
                        .append("\"/>\n");
            } else if (result.status() != ResultStatus.PASSED) {
                xml.append("    <error type=\"").append(xmlEscape(nullSafe(result.failureType())))
                        .append("\" message=\"").append(xmlEscape(nullSafe(result.failureMessage()))).append("\"/>\n");
            }
            xml.append("  </testcase>\n");
        }
        xml.append("</testsuite>\n");
        return xml.toString();
    }

    private String html(Summary summary) {
        StringBuilder workerOptions = new StringBuilder("<option value=\"all\">All workers</option>");
        for (WorkerSummary worker : summary.workers()) {
            workerOptions.append("<option value=\"").append(htmlEscape(worker.workerId())).append("\">")
                    .append(htmlEscape(worker.workerId())).append("</option>");
        }

        StringBuilder workerCards = new StringBuilder();
        for (WorkerSummary worker : summary.workers()) {
            workerCards.append("<div class=\"worker-card\"><div class=\"worker-head\"><strong>")
                    .append(htmlEscape(worker.workerId())).append("</strong><span>")
                    .append(worker.scenarios()).append(" scenarios</span></div><div class=\"worker-time\">")
                    .append(formatDuration(worker.executionMillis())).append("</div><div class=\"muted\">")
                    .append(worker.passed()).append(" passed · ")
                    .append(worker.skipped()).append(" skipped · ")
                    .append(worker.failed()).append(" failed</div></div>");
        }

        StringBuilder rows = new StringBuilder();
        for (ExecutionResult result : summary.results()) {
            String category = statusCategory(result.status());
            rows.append("<tr data-status=\"").append(category).append("\" data-worker=\"")
                    .append(htmlEscape(result.workerId().value())).append("\" data-name=\"")
                    .append(htmlEscape(result.displayName().toLowerCase(Locale.ROOT))).append("\">")
                    .append("<td><span class=\"badge ").append(category).append("\">")
                    .append(htmlEscape(statusLabel(result.status()))).append("</span></td>")
                    .append("<td><div class=\"scenario-name\">").append(htmlEscape(result.displayName()))
                    .append("</div><div class=\"scenario-id\">").append(htmlEscape(result.scenarioId().value())).append("</div></td>")
                    .append("<td>").append(htmlEscape(result.workerId().value())).append("</td>")
                    .append("<td class=\"num\">").append(formatDuration(result.duration().toMillis())).append("</td>")
                    .append("<td class=\"failure\">").append(htmlEscape(nullSafe(result.failureMessage()))).append("</td>")
                    .append("</tr>");
        }

        String savedClass = summary.estimatedTimeSavedMillis() >= 0 ? "positive" : "negative";
        String savedText = (summary.estimatedTimeSavedMillis() >= 0 ? "" : "−")
                + formatDuration(Math.abs(summary.estimatedTimeSavedMillis()));

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>ScenarioMesh Report</title>
                  <style>
                    :root{color-scheme:light dark;--bg:#f6f7fb;--surface:#fff;--surface2:#f9fafb;--text:#111827;--muted:#6b7280;--border:#e5e7eb;--accent:#4f46e5;--good:#047857;--goodbg:#d1fae5;--bad:#b91c1c;--badbg:#fee2e2;--warn:#b45309;--warnbg:#fef3c7;--shadow:0 8px 30px rgba(17,24,39,.06)}
                    @media(prefers-color-scheme:dark){:root{--bg:#0b1020;--surface:#121a2c;--surface2:#182238;--text:#f3f4f6;--muted:#9ca3af;--border:#26324a;--accent:#818cf8;--good:#6ee7b7;--goodbg:#064e3b;--bad:#fca5a5;--badbg:#7f1d1d;--warn:#fcd34d;--warnbg:#78350f;--shadow:none}}
                    *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}.wrap{max-width:1440px;margin:auto;padding:32px 24px 64px}.top{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;margin-bottom:24px}.eyebrow{font-weight:700;color:var(--accent);letter-spacing:.08em;text-transform:uppercase;font-size:12px}h1{font-size:30px;line-height:1.15;margin:5px 0 4px}.muted{color:var(--muted)}.runid{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:var(--muted)}.cards{display:grid;grid-template-columns:repeat(7,minmax(125px,1fr));gap:12px;margin-bottom:22px}.card,.panel,.worker-card{background:var(--surface);border:1px solid var(--border);border-radius:14px;box-shadow:var(--shadow)}.card{padding:17px}.card .label{color:var(--muted);font-size:12px;font-weight:650}.card .value{font-size:25px;font-weight:760;margin-top:4px}.positive{color:var(--good)}.negative{color:var(--bad)}.grid{display:grid;grid-template-columns:1.15fr .85fr;gap:16px;margin-bottom:20px}.panel{padding:18px}.panel h2{font-size:15px;margin:0 0 14px}.timing-line{display:flex;justify-content:space-between;border-top:1px solid var(--border);padding:9px 0}.timing-line:first-of-type{border-top:0}.workers{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.worker-card{padding:13px;background:var(--surface2);box-shadow:none}.worker-head{display:flex;justify-content:space-between;gap:10px}.worker-head span{color:var(--muted);font-size:12px}.worker-time{font-size:20px;font-weight:720;margin:8px 0 2px}.toolbar{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin-bottom:12px}.filters{display:flex;gap:5px;padding:4px;background:var(--surface2);border:1px solid var(--border);border-radius:11px}.filter{border:0;background:transparent;color:var(--muted);padding:7px 11px;border-radius:8px;cursor:pointer;font-weight:650}.filter.active{background:var(--surface);color:var(--text);box-shadow:0 1px 4px rgba(0,0,0,.08)}input,select{background:var(--surface);color:var(--text);border:1px solid var(--border);border-radius:10px;padding:9px 11px;outline:none}input{min-width:260px;flex:1}table{width:100%%;border-collapse:separate;border-spacing:0}.table-wrap{overflow:auto;border:1px solid var(--border);border-radius:12px}th{position:sticky;top:0;background:var(--surface2);color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.05em;text-align:left;padding:10px 12px;border-bottom:1px solid var(--border)}td{padding:12px;border-bottom:1px solid var(--border);vertical-align:top}tr:last-child td{border-bottom:0}.num{white-space:nowrap}.scenario-name{font-weight:650}.scenario-id{font:11px ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--muted);margin-top:3px;max-width:520px;overflow:hidden;text-overflow:ellipsis}.failure{max-width:360px;color:var(--muted)}.badge{display:inline-flex;padding:4px 8px;border-radius:999px;font-size:10px;font-weight:800}.badge.passed{color:var(--good);background:var(--goodbg)}.badge.skipped{color:var(--warn);background:var(--warnbg)}.badge.failed{color:var(--bad);background:var(--badbg)}.badge.infrastructure{color:var(--warn);background:var(--warnbg)}.note{font-size:12px;color:var(--muted);margin-top:10px}.empty{display:none;text-align:center;color:var(--muted);padding:30px}@media(max-width:1050px){.cards{grid-template-columns:repeat(3,1fr)}.grid{grid-template-columns:1fr}}@media(max-width:620px){.wrap{padding:20px 12px}.cards{grid-template-columns:repeat(2,1fr)}.workers{grid-template-columns:1fr}.top{align-items:flex-start;flex-direction:column}}
                  </style>
                </head>
                <body><main class="wrap">
                  <header class="top"><div><div class="eyebrow">ScenarioMesh execution report</div><h1>Run summary</h1><div class="muted">Adapters: %s</div></div><div class="runid">%s</div></header>
                  <section class="cards">
                    <div class="card"><div class="label">Scenarios</div><div class="value">%d</div></div>
                    <div class="card"><div class="label">Passed</div><div class="value positive">%d</div></div>
                    <div class="card"><div class="label">Skipped</div><div class="value">%d</div></div>
                    <div class="card"><div class="label">Failed tests</div><div class="value negative">%d</div></div>
                    <div class="card"><div class="label">Infrastructure errors</div><div class="value">%d</div><div class="note">Worker/runtime errors, not assertion failures.</div></div>
                    <div class="card"><div class="label">Actual elapsed time</div><div class="value">%s</div></div>
                    <div class="card"><div class="label">Estimated parallel time saved</div><div class="value %s">%s</div></div>
                  </section>
                  <section class="grid">
                    <div class="panel"><h2>Timing explained</h2>
                      <div class="timing-line"><span>Actual elapsed time <span class="muted">— real time you waited</span></span><strong>%s</strong></div>
                      <div class="timing-line"><span>Estimated serial execution time <span class="muted">— sum of all scenario durations</span></span><strong>%s</strong></div>
                      <div class="timing-line"><span>Estimated parallel time saved <span class="muted">— serial estimate minus elapsed time</span></span><strong class="%s">%s</strong></div>
                      <div class="timing-line"><span>Estimated speedup vs serial <span class="muted">— serial estimate ÷ elapsed time</span></span><strong>%s×</strong></div>
                      <div class="note">Estimated serial execution time is calculated from the actual scenario durations observed in this parallel run. ScenarioMesh does not rerun the suite sequentially, so this is an estimate rather than a separately measured non-parallel baseline.</div>
                    </div>
                    <div class="panel"><h2>Worker execution time</h2><div class="workers">%s</div><div class="note">Worker time is the sum of scenario durations handled by that worker. Workers run at the same time, so worker times must not be added to obtain the real elapsed time. Startup and idle time are excluded.</div></div>
                  </section>
                  <section class="panel"><div class="toolbar">
                    <div class="filters"><button class="filter active" data-filter="all">All <span>%d</span></button><button class="filter" data-filter="passed">Passed <span>%d</span></button><button class="filter" data-filter="skipped">Skipped <span>%d</span></button><button class="filter" data-filter="failed">Failed <span>%d</span></button><button class="filter" data-filter="infrastructure">Infrastructure errors <span>%d</span></button></div>
                    <select id="workerFilter">%s</select><input id="search" type="search" placeholder="Search scenarios…">
                  </div>
                  <div class="table-wrap"><table><thead><tr><th>Status</th><th>Scenario</th><th>Worker</th><th>Duration</th><th>Failure</th></tr></thead><tbody id="rows">%s</tbody></table><div class="empty" id="empty">No scenarios match these filters.</div></div></section>
                </main>
                <script>
                  (()=>{let status='all';const buttons=[...document.querySelectorAll('.filter')],worker=document.getElementById('workerFilter'),search=document.getElementById('search'),rows=[...document.querySelectorAll('#rows tr')],empty=document.getElementById('empty');function apply(){const q=search.value.trim().toLowerCase();let visible=0;for(const row of rows){const okStatus=status==='all'||row.dataset.status===status,okWorker=worker.value==='all'||row.dataset.worker===worker.value,okSearch=!q||row.dataset.name.includes(q)||row.dataset.worker.toLowerCase().includes(q);const show=okStatus&&okWorker&&okSearch;row.style.display=show?'':'none';if(show)visible++}empty.style.display=visible?'none':'block'}buttons.forEach(button=>button.addEventListener('click',()=>{buttons.forEach(b=>b.classList.remove('active'));button.classList.add('active');status=button.dataset.filter;apply()}));worker.addEventListener('change',apply);search.addEventListener('input',apply);})();
                </script></body></html>
                """.formatted(
                htmlEscape(String.join(", ", summary.adapters())), htmlEscape(summary.runId()),
                summary.total(), summary.passed(), summary.skipped(), summary.failed(), summary.infrastructureFailed(),
                formatDuration(summary.durationMillis()), savedClass, savedText,
                formatDuration(summary.durationMillis()), formatDuration(summary.sequentialEquivalentMillis()),
                savedClass, savedText, formatRatio(summary.observedSpeedup()), workerCards,
                summary.total(), summary.passed(), summary.skipped(), summary.failed(), summary.infrastructureFailed(),
                workerOptions, rows);
    }

    private String statusCategory(ResultStatus status) {
        if (status == ResultStatus.PASSED) return "passed";
        if (status == ResultStatus.SKIPPED) return "skipped";
        if (status == ResultStatus.TEST_FAILURE) return "failed";
        return "infrastructure";
    }

    private String statusLabel(ResultStatus status) {
        if (status == ResultStatus.PASSED) return "PASSED";
        if (status == ResultStatus.SKIPPED) return "SKIPPED";
        if (status == ResultStatus.TEST_FAILURE) return "TEST FAILURE";
        return "INFRASTRUCTURE ERROR";
    }

    private String formatDuration(long millis) {
        long absolute = Math.max(0L, millis);
        long hours = absolute / 3_600_000L;
        long minutes = (absolute % 3_600_000L) / 60_000L;
        long seconds = (absolute % 60_000L) / 1_000L;
        if (hours > 0) return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds);
        if (minutes > 0) return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds);
        return String.format(Locale.ROOT, "%.2fs", absolute / 1000.0d);
    }

    private String formatRatio(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "0.00";
    }

    private String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1000.0d);
    }

    private String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String htmlEscape(String value) {
        return xmlEscape(value);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public record ReportPaths(
            Path json,
            Path junitXml,
            Path html,
            Path latestJson,
            Path latestJunitXml,
            Path latestHtml) {}

    public record WorkerSummary(
            String workerId,
            int scenarios,
            int passed,
            int skipped,
            int failed,
            long executionMillis) {}

    public record Summary(
            String runId,
            List<String> adapters,
            int total,
            int passed,
            int skipped,
            int failed,
            int infrastructureFailed,
            long durationMillis,
            long sequentialEquivalentMillis,
            long estimatedTimeSavedMillis,
            double observedSpeedup,
            List<WorkerSummary> workers,
            List<ExecutionResult> results) {
        static Summary from(RunOutcome outcome, List<ExecutionResult> results) {
            int passed = 0;
            int skipped = 0;
            int failed = 0;
            int infrastructure = 0;
            long sequentialEquivalent = 0L;
            Map<String, MutableWorkerSummary> workers = new LinkedHashMap<>();
            for (ExecutionResult result : results) {
                if (result.status() == ResultStatus.PASSED) {
                    passed++;
                } else if (result.status() == ResultStatus.SKIPPED) {
                    skipped++;
                } else if (result.status() == ResultStatus.TEST_FAILURE) {
                    failed++;
                } else {
                    infrastructure++;
                }
                sequentialEquivalent += Math.max(0L, result.duration().toMillis());
                workers.computeIfAbsent(result.workerId().value(), ignored -> new MutableWorkerSummary())
                        .add(result);
            }
            long wall = Math.max(0L, outcome.duration().toMillis());
            long saved = sequentialEquivalent - wall;
            double speedup = wall == 0L ? 0.0d : (double) sequentialEquivalent / wall;
            List<WorkerSummary> workerSummaries = workers.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getValue().freeze(entry.getKey()))
                    .toList();
            return new Summary(
                    outcome.runId().value(), outcome.adapters(), results.size(),
                    passed, skipped, failed, infrastructure,
                    wall, sequentialEquivalent, saved, speedup,
                    workerSummaries, List.copyOf(results));
        }
    }

    private static final class MutableWorkerSummary {
        private int scenarios;
        private int passed;
        private int skipped;
        private int failed;
        private long executionMillis;

        private void add(ExecutionResult result) {
            scenarios++;
            if (result.passed()) {
                passed++;
            } else if (result.skipped()) {
                skipped++;
            } else {
                failed++;
            }
            executionMillis += Math.max(0L, result.duration().toMillis());
        }

        private WorkerSummary freeze(String workerId) {
            return new WorkerSummary(workerId, scenarios, passed, skipped, failed, executionMillis);
        }
    }
}
