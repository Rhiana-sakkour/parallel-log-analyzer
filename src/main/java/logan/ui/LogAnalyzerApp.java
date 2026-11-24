package logan.ui;

import logan.core.AnalysisResult;
import logan.core.LogStats;
import logan.core.ParallelAnalyzer;
import logan.core.ParallelConfig;
import logan.core.ProgressTracker;
import logan.core.SequentialAnalyzer;
import logan.core.Strategy;
import logan.gen.LogGenerator;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Desktop front-end ("Parallel Log Analyzer"). It contains NO threading logic of its own: all analysis runs
 * through the logan.core API inside a SwingWorker (a background thread), so the Event Dispatch Thread (EDT)
 * never blocks. Two timers on the EDT only READ shared state:
 *   - progress timer : polls ProgressTracker (atomic counters written by the worker threads)
 *   - heartbeat timer: a visible counter that keeps ticking during heavy processing - if the UI froze, it would stop.
 */
public final class LogAnalyzerApp extends JFrame {
    private enum Mode { SEQUENTIAL, PARALLEL, COMPARE }

    private final JTextField filePath = new JTextField(46);
    private final JRadioButton rbSeq = new JRadioButton("Sequential (1 thread)");
    private final JRadioButton rbPar = new JRadioButton("Multi-threaded", true);
    private final JRadioButton rbBoth = new JRadioButton("Compare both (speedup)");
    private final JSpinner threads = new JSpinner(new SpinnerNumberModel(
            Math.min(Runtime.getRuntime().availableProcessors(), ParallelConfig.MAX_THREADS), 1, ParallelConfig.MAX_THREADS, 1));
    private final JSpinner runs = new JSpinner(new SpinnerNumberModel(3, 1, 50, 1));
    private final JSpinner chunkKb = new JSpinner(new SpinnerNumberModel(1024, 4, 1 << 20, 256));
    private final JComboBox<Strategy> strategy = new JComboBox<>(Strategy.values());
    private final JButton browseBtn = new JButton("Browse...");
    private final JButton generateBtn = new JButton("Generate sample log...");
    private final JButton analyzeBtn = new JButton("Analyze");
    private final JButton cancelBtn = new JButton("Cancel");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel("Choose a log file (or generate a sample) and press Analyze.");
    private final JLabel heartbeat = new JLabel();
    private final JLabel seqLabel = bold("Sequential: -"), parLabel = bold("Parallel: -"), speedLabel = bold("Speedup: -"),
            effLabel = bold("Efficiency: -"), sameLabel = bold("Results identical: -");
    private final JTextArea summary = new JTextArea();
    private final DefaultTableModel statusModel = model("Status", "Count");
    private final DefaultTableModel pathModel = model("Rank", "Path", "Requests");
    private final DefaultTableModel ipModel = model("Rank", "Client IP (5xx responses)", "Errors");
    private final DefaultTableModel hourModel = model("Hour", "Requests");
    private final DefaultTableModel workerModel = model("Thread", "Chunks processed", "Lines", "Busy (ms)");

    private final Timer progressTimer = new Timer(100, e -> refreshProgress());
    private long ticks;
    private AnalysisTask current;

    private LogAnalyzerApp() {
        super("Parallel Log Analyzer - sequential vs multi-threaded");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        buildUi();
        setSize(1020, 740);
        setLocationRelativeTo(null);
        new Timer(100, e -> heartbeat.setText("UI heartbeat: tick " + (++ticks))).start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            new LogAnalyzerApp().setVisible(true);
        });
    }

    // ------------------------------------------------------------------ UI construction

    private void buildUi() {
        ButtonGroup g = new ButtonGroup();
        g.add(rbSeq); g.add(rbPar); g.add(rbBoth);
        threads.setToolTipText("Number of worker threads N (this machine reports "
                + Runtime.getRuntime().availableProcessors() + " logical processors)");
        runs.setToolTipText("In 'Compare' mode each version runs this many times; the median time is used");
        chunkKb.setToolTipText("Size of one unit of work handed to a thread");
        strategy.setToolTipText("How worker threads combine their partial results (see the report)");
        cancelBtn.setEnabled(false);
        analyzeBtn.addActionListener(e -> startAnalysis());
        cancelBtn.addActionListener(e -> { if (current != null) current.abort(); });
        browseBtn.addActionListener(e -> browse());
        generateBtn.addActionListener(e -> generateSample());
        progress.setStringPainted(true);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
        top.add(flow(new JLabel("Log file:"), filePath, browseBtn, generateBtn));
        top.add(flow(new JLabel("Mode:"), rbSeq, rbPar, rbBoth));
        top.add(flow(new JLabel("Threads N:"), threads, new JLabel("   Chunk (KiB):"), chunkKb,
                new JLabel("   Strategy:"), strategy, new JLabel("   Runs (compare):"), runs));
        top.add(flow(analyzeBtn, cancelBtn, new JLabel("   "), heartbeat));
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(progress, BorderLayout.CENTER);
        top.add(bar);

        summary.setEditable(false);
        summary.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Summary", new JScrollPane(summary));
        tabs.addTab("Status codes", new JScrollPane(new JTable(statusModel)));
        tabs.addTab("Top paths", new JScrollPane(new JTable(pathModel)));
        tabs.addTab("Top error clients", new JScrollPane(new JTable(ipModel)));
        tabs.addTab("Requests per hour", new JScrollPane(new JTable(hourModel)));
        tabs.addTab("Threads", new JScrollPane(new JTable(workerModel)));

        JPanel timing = new JPanel(new GridLayout(1, 5, 12, 0));
        timing.setBorder(BorderFactory.createTitledBorder("Execution time & speedup  (Speedup = T_sequential / T_parallel)"));
        timing.add(seqLabel); timing.add(parLabel); timing.add(speedLabel); timing.add(effLabel); timing.add(sameLabel);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(timing, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);
        status.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        progressTimer.start();
    }

    private static JPanel flow(java.awt.Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        for (java.awt.Component c : cs) p.add(c);
        return p;
    }

    private static JLabel bold(String t) { JLabel l = new JLabel(t); l.setFont(l.getFont().deriveFont(Font.BOLD)); return l; }

    private static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
    }

    // ------------------------------------------------------------------ actions

    private void browse() {
        JFileChooser fc = new JFileChooser(new File("."));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) filePath.setText(fc.getSelectedFile().getAbsolutePath());
    }

    private void startAnalysis() {
        Path file;
        try { file = Path.of(filePath.getText().trim()); } catch (RuntimeException ex) { error("Invalid file path."); return; }
        if (filePath.getText().isBlank() || !Files.isRegularFile(file)) { error("File not found:\n" + filePath.getText()); return; }
        Mode mode = rbSeq.isSelected() ? Mode.SEQUENTIAL : rbPar.isSelected() ? Mode.PARALLEL : Mode.COMPARE;
        ParallelConfig cfg;
        try {
            cfg = new ParallelConfig((Integer) threads.getValue(), ((Integer) chunkKb.getValue()) * 1024L, (Strategy) strategy.getSelectedItem());
        } catch (IllegalArgumentException ex) { error(ex.getMessage()); return; }
        current = new AnalysisTask(file, mode, cfg, mode == Mode.COMPARE ? (Integer) runs.getValue() : 1);
        setRunning(true);
        current.execute();
    }

    private void generateSample() {
        JSpinner lines = new JSpinner(new SpinnerNumberModel(1_000_000, 1_000, 100_000_000, 100_000));
        if (JOptionPane.showConfirmDialog(this, new Object[] {"Number of log lines to generate (~90 bytes each):", lines},
                "Generate sample log", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        JFileChooser fc = new JFileChooser(new File("."));
        fc.setSelectedFile(new File("sample_access.log"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final long n = ((Number) lines.getValue()).longValue();
        final Path out = fc.getSelectedFile().toPath();
        setRunning(true);
        cancelBtn.setEnabled(false);
        status.setText("Generating " + n + " lines ...");
        new SwingWorker<Void, Long>() {
            @Override protected Void doInBackground() throws Exception {
                LogGenerator.generate(out, n, LogGenerator.DEFAULT_SEED, done -> publish(done));
                return null;
            }
            @Override protected void process(List<Long> chunks) { progress.setValue((int) (1000 * chunks.get(chunks.size() - 1) / n)); }
            @Override protected void done() {
                setRunning(false);
                try { get(); filePath.setText(out.toString()); status.setText("Generated " + out); progress.setValue(0); }
                catch (InterruptedException | ExecutionException ex) { error("Could not generate file: " + ex.getCause()); }
            }
        }.execute();
    }

    private void setRunning(boolean running) {
        for (java.awt.Component c : new java.awt.Component[] {analyzeBtn, browseBtn, generateBtn, filePath, rbSeq, rbPar, rbBoth,
                threads, runs, chunkKb, strategy}) c.setEnabled(!running);
        cancelBtn.setEnabled(running);
    }

    private void refreshProgress() {
        AnalysisTask t = current;
        if (t == null || t.isDone()) return;
        ProgressTracker tr = t.tracker;
        if (tr == null) return;
        int pct = (int) (tr.fraction() * 100);
        progress.setValue((int) (tr.fraction() * 1000));
        progress.setString(pct + " %");
        status.setText(t.phase + "  -  " + pct + " %");
    }

    private void error(String msg) { JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE); }

    // ------------------------------------------------------------------ background task

    private record Outcome(AnalysisResult shown, List<Double> seqMs, List<Double> parMs, boolean identical, int threads) { }

    /** Runs the analyses off the EDT. The only things shared with the EDT are the volatile fields below. */
    private final class AnalysisTask extends SwingWorker<Outcome, Void> {
        private final Path file; private final Mode mode; private final ParallelConfig cfg; private final int repeat;
        volatile ProgressTracker tracker;
        volatile String phase = "starting";

        AnalysisTask(Path file, Mode mode, ParallelConfig cfg, int repeat) {
            this.file = file; this.mode = mode; this.cfg = cfg; this.repeat = repeat;
        }

        void abort() {
            ProgressTracker t = tracker;
            if (t != null) t.cancel();            // cooperative: workers notice at the next buffer refill
            cancel(true);                         // and interrupt the coordinating thread
        }

        @Override
        protected Outcome doInBackground() throws Exception {
            List<Double> seqMs = new ArrayList<>(), parMs = new ArrayList<>();
            AnalysisResult lastSeq = null, lastPar = null;
            boolean identical = true;
            for (int i = 1; i <= repeat; i++) {
                String run = repeat > 1 ? " (run " + i + "/" + repeat + ")" : "";
                if (mode != Mode.PARALLEL) {
                    phase = "Sequential" + run; tracker = new ProgressTracker();
                    lastSeq = new SequentialAnalyzer().analyze(file, tracker);
                    seqMs.add(lastSeq.timing().totalMs());
                }
                if (mode != Mode.SEQUENTIAL) {
                    phase = "Multi-threaded, N=" + cfg.threads() + run; tracker = new ProgressTracker();
                    lastPar = new ParallelAnalyzer(cfg).analyze(file, tracker);
                    parMs.add(lastPar.timing().totalMs());
                }
                if (lastSeq != null && lastPar != null) identical &= lastSeq.stats().equals(lastPar.stats());
            }
            return new Outcome(lastPar != null ? lastPar : lastSeq, seqMs, parMs, identical, cfg.threads());
        }

        @Override
        protected void done() {
            setRunning(false);
            progress.setValue(0); progress.setString("");
            if (isCancelled()) { status.setText("Cancelled."); return; }
            try {
                show(get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                Throwable c = ex.getCause();
                if (c instanceof java.util.concurrent.CancellationException) status.setText("Cancelled.");
                else { status.setText("Failed."); error("Analysis failed: " + c); }
            }
        }
    }

    // ------------------------------------------------------------------ presenting results

    private static double median(List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }

    private void show(Outcome o) {
        LogStats s = o.shown().stats();
        AnalysisResult.Timing t = o.shown().timing();
        StringBuilder sb = new StringBuilder(s.summary(10));
        if (o.shown().mode().equals("parallel"))
            sb.append(String.format("%nPhases of the last parallel run: plan+thread start %.1f ms | parallel scan %.1f ms | merge %.1f ms%n",
                    t.planMs(), t.scanMs(), t.mergeMs()));
        summary.setText(sb.toString());
        summary.setCaretPosition(0);

        statusModel.setRowCount(0);
        for (LogStats.Ranked r : s.statusTable()) statusModel.addRow(new Object[] {r.key(), r.count()});
        pathModel.setRowCount(0);
        int rank = 1;
        for (LogStats.Ranked r : s.topPaths(20)) pathModel.addRow(new Object[] {rank++, r.key(), r.count()});
        ipModel.setRowCount(0);
        rank = 1;
        for (LogStats.Ranked r : s.topErrorIps(20)) ipModel.addRow(new Object[] {rank++, r.key(), r.count()});
        hourModel.setRowCount(0);
        long[] hours = s.hourCounts();
        for (int h = 0; h < 24; h++) hourModel.addRow(new Object[] {String.format("%02d:00", h), hours[h]});
        workerModel.setRowCount(0);
        for (AnalysisResult.WorkerReport w : o.shown().workers())
            workerModel.addRow(new Object[] {"worker-" + w.workerId(), w.chunks(), w.lines(), String.format("%.1f", w.busyMs())});

        boolean haveSeq = !o.seqMs().isEmpty(), havePar = !o.parMs().isEmpty();
        double seq = haveSeq ? median(o.seqMs()) : Double.NaN, par = havePar ? median(o.parMs()) : Double.NaN;
        seqLabel.setText(haveSeq ? String.format("Sequential: %.1f ms", seq) : "Sequential: -");
        parLabel.setText(havePar ? String.format("Parallel (N=%d): %.1f ms", o.threads(), par) : "Parallel: -");
        if (haveSeq && havePar) {
            double sp = seq / par;
            speedLabel.setText(String.format("Speedup: %.2fx", sp));
            effLabel.setText(String.format("Efficiency: %.0f %%", 100 * sp / o.threads()));
            sameLabel.setText("Results identical: " + (o.identical() ? "YES" : "NO !!"));
            status.setText(String.format("Done - median of %d run(s). GUI timings are indicative (JIT warm-up, cache); use the Benchmark tool for rigorous numbers.", o.seqMs().size()));
        } else {
            speedLabel.setText("Speedup: - (run 'Compare both')"); effLabel.setText("Efficiency: -"); sameLabel.setText("Results identical: -");
            status.setText("Done.");
        }
    }
}
